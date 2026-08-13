package it.davidgreco.metacatalog.iceberg.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.iceberg.IcebergCatalogProperties;
import it.davidgreco.metacatalog.iceberg.IcebergModel;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only class in this module that talks to the metacatalog core service layer. Namespaces and
 * tables are entities of the immutable {@code IcebergNamespace} / {@code IcebergTable} types, a
 * table is contained in its namespace through a {@code HAS_PART} entity link, and every read goes
 * through {@link EntityService#list(String, String)} with a PostgreSQL jsonpath pushed into the
 * database.
 *
 * <p>Entities have no name column and no uniqueness constraint on their jsonb values, so name
 * uniqueness and commit atomicity are enforced here: every mutating method runs in one transaction
 * holding a transaction-scoped advisory lock derived from the name it is about to touch, and the
 * commit path additionally compares the stored metadata location with the caller's expected one
 * (the CAS is the real guard — correct even across lock-id hash collisions).
 *
 * <p>Failures surface as Iceberg exception types so that a single REST exception mapper covers both
 * this layer and iceberg-core internals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IcebergRegistryService {

  private final EntityService entityService;
  private final AdvisoryLockManager advisoryLockManager;
  private final JsonUtils jsonUtils;
  private final IcebergCatalogProperties properties;

  // ---------------------------------------------------------------------------------------------
  // Namespaces
  // ---------------------------------------------------------------------------------------------

  @Transactional
  public void createNamespace(Namespace namespace, Map<String, String> namespaceProperties) {
    var key = key(namespace);
    acquireLockOrFail("namespace " + key, lockId("ns:" + key));
    if (findNamespaceEntity(key).isPresent()) {
      throw new AlreadyExistsException("Namespace already exists: %s", namespace);
    }
    var values = jsonUtils.jsonMapper().createObjectNode();
    values.put("key", key);
    values.put("name", String.join(".", namespace.levels()));
    var levels = values.putArray("levels");
    for (String level : namespace.levels()) {
      levels.add(level);
    }
    values.set(
        "properties",
        jsonUtils
            .jsonMapper()
            .valueToTree(namespaceProperties == null ? Map.of() : namespaceProperties));
    entityService.create(IcebergModel.NAMESPACE_TYPE, values.toString());
  }

  @Transactional(readOnly = true)
  public boolean namespaceExists(Namespace namespace) {
    return findNamespaceEntity(key(namespace)).isPresent();
  }

  @Transactional(readOnly = true)
  public Map<String, String> loadNamespaceProperties(Namespace namespace) {
    var entity = namespaceEntityOrThrow(namespace);
    return propertiesOf(entity.getValues());
  }

  /**
   * Lists the direct children of {@code parent}; an empty parent lists the top-level namespaces.
   * The candidate set is narrowed in the database with a jsonpath prefix match, then reduced to
   * direct children by level count.
   */
  @Transactional(readOnly = true)
  public List<Namespace> listNamespaces(Namespace parent) {
    if (!parent.isEmpty()) {
      namespaceEntityOrThrow(parent);
    }
    String query =
        parent.isEmpty()
            ? ""
            : "$ ? (@.key starts with "
                + jsonPathString(key(parent) + IcebergModel.KEY_SEPARATOR)
                + ")";
    int wantedLevels = parent.length() + 1;
    return entityService.list(IcebergModel.NAMESPACE_TYPE, query).stream()
        .map(entity -> namespaceOf(entity.getValues()))
        .filter(namespace -> namespace.length() == wantedLevels)
        .toList();
  }

  @Transactional
  public void updateNamespaceProperties(
      Namespace namespace, List<String> removals, Map<String, String> updates) {
    var key = key(namespace);
    acquireLockOrFail("namespace " + key, lockId("ns:" + key));
    var entity = namespaceEntityOrThrow(namespace);
    Map<String, String> merged = new LinkedHashMap<>(propertiesOf(entity.getValues()));
    if (removals != null) {
      removals.forEach(merged::remove);
    }
    if (updates != null) {
      merged.putAll(updates);
    }
    var values = (ObjectNode) entity.getValues().deepCopy();
    values.set("properties", jsonUtils.jsonMapper().valueToTree(merged));
    entityService.update(entity.getId(), values.toString());
  }

  /**
   * Drops an empty namespace.
   *
   * @return {@code false} when the namespace does not exist
   * @throws NamespaceNotEmptyException when it still contains tables or child namespaces
   */
  @Transactional
  public boolean dropNamespace(Namespace namespace) {
    var key = key(namespace);
    acquireLockOrFail("namespace " + key, lockId("ns:" + key));
    var maybeEntity = findNamespaceEntity(key);
    if (maybeEntity.isEmpty()) {
      return false;
    }
    var entity = maybeEntity.get();
    if (!entityService.linked(entity.getId(), RelationType.HAS_PART).isEmpty()) {
      throw new NamespaceNotEmptyException("Namespace %s is not empty: it contains tables", key);
    }
    var childQuery =
        "$ ? (@.key starts with " + jsonPathString(key + IcebergModel.KEY_SEPARATOR) + ")";
    if (!entityService.list(IcebergModel.NAMESPACE_TYPE, childQuery).isEmpty()) {
      throw new NamespaceNotEmptyException(
          "Namespace %s is not empty: it contains child namespaces", key);
    }
    entityService.delete(entity.getId());
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // Tables
  // ---------------------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<TableIdentifier> listTables(Namespace namespace) {
    namespaceEntityOrThrow(namespace);
    return entityService
        .list(
            IcebergModel.TABLE_TYPE,
            "$ ? (@.namespaceKey == " + jsonPathString(key(namespace)) + ")")
        .stream()
        .map(entity -> TableIdentifier.of(namespace, entity.getValues().get("name").asText()))
        .toList();
  }

  @Transactional(readOnly = true)
  public boolean tableExists(TableIdentifier identifier) {
    return findTableEntity(identifier).isPresent();
  }

  /** Returns the current metadata location of the table, or {@code null} if it does not exist. */
  @Transactional(readOnly = true)
  public String findMetadataLocation(TableIdentifier identifier) {
    return findTableEntity(identifier)
        .map(entity -> entity.getValues().get("metadataLocation").asText())
        .orElse(null);
  }

  /**
   * The commit primitive: atomically moves the table's metadata-location pointer from {@code
   * expectedMetadataLocation} to {@code newMetadataLocation}, storing {@code metadataJson} as the
   * cached copy of the new current metadata. A {@code null} expected location means "the table must
   * not exist yet" and creates the table entity inside its namespace.
   *
   * @throws CommitFailedException when the stored location no longer matches the expected one
   * @throws AlreadyExistsException when creating and the table already exists
   * @throws NoSuchTableException when updating and the table has disappeared
   * @throws NoSuchNamespaceException when creating into a namespace that does not exist
   */
  @Transactional
  public void casCommit(
      TableIdentifier identifier,
      String expectedMetadataLocation,
      String newMetadataLocation,
      String metadataJson) {
    var tableKey = tableKey(identifier);
    if (!acquireLockWithRetry(lockId("table:" + tableKey))) {
      throw new CommitFailedException("Concurrent commit in progress for table %s", identifier);
    }
    var metadata = parseMetadata(metadataJson);
    var maybeEntity = findTableEntity(identifier);
    if (expectedMetadataLocation == null) {
      if (maybeEntity.isPresent()) {
        throw new AlreadyExistsException("Table already exists: %s", identifier);
      }
      var namespaceEntity =
          findNamespaceEntity(key(identifier.namespace()))
              .orElseThrow(
                  () ->
                      new NoSuchNamespaceException(
                          "Namespace does not exist: %s", identifier.namespace()));
      var entity =
          entityService.create(
              IcebergModel.TABLE_TYPE,
              tableValues(identifier, newMetadataLocation, null, metadata));
      entityService.link(namespaceEntity.getId(), RelationType.HAS_PART, entity.getId());
      syncSchemaEntities(entity.getId(), metadata);
    } else {
      var entity =
          maybeEntity.orElseThrow(
              () -> new NoSuchTableException("Table does not exist: %s", identifier));
      var storedLocation = entity.getValues().get("metadataLocation").asText();
      if (!storedLocation.equals(expectedMetadataLocation)) {
        throw new CommitFailedException(
            "Cannot commit %s: stale metadata location (expected %s, found %s)",
            identifier, expectedMetadataLocation, storedLocation);
      }
      entityService.updateValues(
          entity.getId(),
          tableValues(identifier, newMetadataLocation, expectedMetadataLocation, metadata));
      syncSchemaEntities(entity.getId(), metadata);
    }
  }

  /**
   * Drops the table: removes the containment link, then the entity.
   *
   * @return the last metadata location, or {@code null} when the table did not exist
   */
  @Transactional
  public String dropTable(TableIdentifier identifier) {
    var tableKey = tableKey(identifier);
    acquireLockOrFail("table " + tableKey, lockId("table:" + tableKey));
    var maybeEntity = findTableEntity(identifier);
    if (maybeEntity.isEmpty()) {
      return null;
    }
    var entity = maybeEntity.get();
    var lastMetadataLocation = entity.getValues().get("metadataLocation").asText();
    // Schema entities are parts of the table: unlink and delete them first, or the table
    // delete would be refused for still having relationships.
    for (Entity child : entityService.linked(entity.getId(), RelationType.HAS_PART)) {
      if (IcebergModel.TABLE_SCHEMA_TYPE.equals(child.getEntityType().getName())) {
        entityService.unlink(entity.getId(), RelationType.HAS_PART, child.getId());
        entityService.delete(child.getId());
      }
    }
    findNamespaceEntity(key(identifier.namespace()))
        .ifPresent(
            namespaceEntity ->
                entityService.unlink(
                    namespaceEntity.getId(), RelationType.HAS_PART, entity.getId()));
    entityService.delete(entity.getId());
    return lastMetadataLocation;
  }

  @Transactional
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    var fromLockId = lockId("table:" + tableKey(from));
    var toLockId = lockId("table:" + tableKey(to));
    // Always acquire in ascending order so two concurrent renames cannot deadlock.
    if (Integer.compare(fromLockId, toLockId) <= 0) {
      acquireLockOrFail("table " + tableKey(from), fromLockId);
      acquireLockOrFail("table " + tableKey(to), toLockId);
    } else {
      acquireLockOrFail("table " + tableKey(to), toLockId);
      acquireLockOrFail("table " + tableKey(from), fromLockId);
    }
    var entity =
        findTableEntity(from)
            .orElseThrow(() -> new NoSuchTableException("Table does not exist: %s", from));
    if (findTableEntity(to).isPresent()) {
      throw new AlreadyExistsException("Table already exists: %s", to);
    }
    var values = (ObjectNode) entity.getValues().deepCopy();
    values.put("name", to.name());
    values.put("namespaceKey", key(to.namespace()));
    if (!from.namespace().equals(to.namespace())) {
      var fromNamespaceEntity = namespaceEntityOrThrow(from.namespace());
      var toNamespaceEntity = namespaceEntityOrThrow(to.namespace());
      entityService.unlink(fromNamespaceEntity.getId(), RelationType.HAS_PART, entity.getId());
      entityService.update(entity.getId(), values.toString());
      entityService.link(toNamespaceEntity.getId(), RelationType.HAS_PART, entity.getId());
    } else {
      namespaceEntityOrThrow(to.namespace());
      entityService.update(entity.getId(), values.toString());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------------------------

  private Entity namespaceEntityOrThrow(Namespace namespace) {
    return findNamespaceEntity(key(namespace))
        .orElseThrow(() -> new NoSuchNamespaceException("Namespace does not exist: %s", namespace));
  }

  private Optional<Entity> findNamespaceEntity(String key) {
    return single(
        entityService.list(
            IcebergModel.NAMESPACE_TYPE, "$ ? (@.key == " + jsonPathString(key) + ")"));
  }

  private Optional<Entity> findTableEntity(TableIdentifier identifier) {
    return single(
        entityService.list(
            IcebergModel.TABLE_TYPE,
            "$ ? (@.namespaceKey == "
                + jsonPathString(key(identifier.namespace()))
                + " && @.name == "
                + jsonPathString(identifier.name())
                + ")"));
  }

  private static Optional<Entity> single(List<Entity> entities) {
    if (entities.size() > 1) {
      throw new ServiceError("Registry corruption: " + entities.size() + " entities share a name");
    }
    return entities.stream().findFirst();
  }

  private String tableValues(
      TableIdentifier identifier,
      String metadataLocation,
      String previousMetadataLocation,
      JsonNode metadata) {
    var values = jsonUtils.jsonMapper().createObjectNode();
    values.put("name", identifier.name());
    values.put("namespaceKey", key(identifier.namespace()));
    values.put("metadataLocation", metadataLocation);
    if (previousMetadataLocation != null) {
      values.put("previousMetadataLocation", previousMetadataLocation);
    }
    // The schema list is not duplicated into the cached copy: schemas are materialized as
    // linked IcebergTableSchema entities by syncSchemaEntities. current-schema-id stays, so
    // the current schema entity is still identifiable. The server never reads this cache back
    // (refresh loads the metadata file), so stripping is safe.
    var cachedMetadata = (ObjectNode) metadata.deepCopy();
    cachedMetadata.remove("schemas");
    cachedMetadata.remove("schema"); // legacy v1 single-schema field
    values.set("metadata", cachedMetadata);
    return values.toString();
  }

  private JsonNode parseMetadata(String metadataJson) {
    try {
      return jsonUtils.jsonMapper().readTree(metadataJson);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ServiceError("Invalid table metadata JSON", e);
    }
  }

  /**
   * Materializes the metadata's schema list as entities: one {@code IcebergTableSchema} per
   * schema-id, linked {@code table HAS_PART schema}. Iceberg schemas are immutable per id and
   * evolution only appends, so this is append-only and idempotent — existing ids are left alone.
   */
  private void syncSchemaEntities(String tableEntityId, JsonNode metadata) {
    var schemasNode = metadata.get("schemas");
    if (schemasNode == null || !schemasNode.isArray()) {
      return;
    }
    var existingIds =
        entityService.linked(tableEntityId, RelationType.HAS_PART).stream()
            .filter(child -> IcebergModel.TABLE_SCHEMA_TYPE.equals(child.getEntityType().getName()))
            .map(child -> child.getValues().get("schemaId").asInt())
            .collect(java.util.stream.Collectors.toSet());
    for (JsonNode schemaNode : schemasNode) {
      int schemaId = schemaNode.path("schema-id").asInt(0);
      if (existingIds.contains(schemaId)) {
        continue;
      }
      var values = jsonUtils.jsonMapper().createObjectNode();
      values.put("schemaId", schemaId);
      // The id lives once, in the typed top-level field: strip the copy embedded in the
      // schema document (the verbatim original is always in the metadata file anyway).
      var schemaDocument = (ObjectNode) schemaNode.deepCopy();
      schemaDocument.remove("schema-id");
      values.set("schema", schemaDocument);
      var schemaEntity = entityService.create(IcebergModel.TABLE_SCHEMA_TYPE, values.toString());
      entityService.link(tableEntityId, RelationType.HAS_PART, schemaEntity.getId());
    }
  }

  private static Map<String, String> propertiesOf(JsonNode values) {
    Map<String, String> result = new LinkedHashMap<>();
    var propertiesNode = values.get("properties");
    if (propertiesNode != null && propertiesNode.isObject()) {
      propertiesNode
          .properties()
          .forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
    }
    return result;
  }

  private static Namespace namespaceOf(JsonNode values) {
    var levelsNode = (ArrayNode) values.get("levels");
    var levels = new String[levelsNode.size()];
    for (int i = 0; i < levels.length; i++) {
      levels[i] = levelsNode.get(i).asText();
    }
    return Namespace.of(levels);
  }

  private static String key(Namespace namespace) {
    return String.join(IcebergModel.KEY_SEPARATOR, namespace.levels());
  }

  private String tableKey(TableIdentifier identifier) {
    return key(identifier.namespace()) + IcebergModel.KEY_SEPARATOR + identifier.name();
  }

  /**
   * Renders a string as a PostgreSQL jsonpath string literal. Control characters (the {@code
   * \u001F} namespace separator above all) and jsonpath metacharacters are emitted as {@code
   * \\uXXXX} escapes, which the jsonpath grammar accepts in string literals.
   */
  static String jsonPathString(String value) {
    var sb = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c == '"' || c == '\\') {
        sb.append(String.format("\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  /**
   * Derives a stable advisory-lock id from a registry name, steering clear of the ids reserved by
   * core (1 = mapping updater, 2 = immutable-model installer).
   */
  static int lockId(String name) {
    var crc = new CRC32();
    crc.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    int id = (int) crc.getValue();
    if (id == 1 || id == 2) {
      id ^= 0x5f3759df;
    }
    return id;
  }

  private void acquireLockOrFail(String what, int id) {
    if (!acquireLockWithRetry(id)) {
      throw new CommitFailedException("Could not lock %s: concurrent operation in progress", what);
    }
  }

  /**
   * {@link AdvisoryLockManager#acquireLock(int)} is a non-blocking {@code
   * pg_try_advisory_xact_lock}, so contention is handled by retrying with a small pause; once
   * acquired the lock lives until the surrounding transaction ends.
   */
  private boolean acquireLockWithRetry(int id) {
    for (int attempt = 0; ; attempt++) {
      if (advisoryLockManager.acquireLock(id)) {
        return true;
      }
      if (attempt >= properties.commitLockRetries()) {
        return false;
      }
      try {
        Thread.sleep(properties.commitLockBackoff().toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }
}
