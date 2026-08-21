package it.davidgreco.metacatalog.hive.registry;

import static it.davidgreco.metacatalog.common.RegistrySupport.jsonPathString;
import static it.davidgreco.metacatalog.common.RegistrySupport.lockId;

import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.hive.HiveMetastoreProperties;
import it.davidgreco.metacatalog.hive.HiveModel;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only class in this module that talks to the metacatalog core service layer.
 *
 * <p>Databases, tables and partitions are entities of the immutable {@code HiveDatabase} / {@code
 * HiveTable} / {@code HivePartition} types; a table is contained in its database and a partition in
 * its table through {@code HAS_PART} entity links; and every read goes through {@link
 * EntityService#list(String, String)} with a PostgreSQL jsonpath pushed into the database.
 *
 * <p>Entities have no name column and no uniqueness constraint on their jsonb values, so name
 * uniqueness is enforced here: every mutating method runs in one transaction holding a
 * transaction-scoped advisory lock derived from the name it is about to touch, and re-checks
 * existence under that lock.
 *
 * <p>Failures are the unchecked types in {@link HiveErrors}, not Thrift's checked ones — see that
 * class for why. The handler translates them at the protocol boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HiveRegistryService {

  private final EntityService entityService;
  private final AggregateService aggregateService;
  private final EntityRelationshipRepository entityRelationshipRepository;
  private final AdvisoryLockManager advisoryLockManager;
  private final JsonUtils jsonUtils;
  private final HiveMetastoreProperties properties;

  // ---------------------------------------------------------------------------------------------
  // Databases
  // ---------------------------------------------------------------------------------------------

  @Transactional
  public void createDatabase(Database database) {
    var name = database.getName();
    acquireLockOrFail("database " + name, lockId("hive-db:" + name));
    if (findDatabaseEntity(name).isPresent()) {
      throw new HiveErrors.AlreadyExists("Database already exists: " + name);
    }
    entityService.create(HiveModel.DATABASE_TYPE, databaseValues(database));
  }

  @Transactional(readOnly = true)
  public Database getDatabase(String name) {
    return HiveValues.toDatabase(requireDatabaseEntity(name).getValues());
  }

  @Transactional(readOnly = true)
  public List<String> getAllDatabases() {
    return entityService.list(HiveModel.DATABASE_TYPE, "").stream()
        .map(entity -> entity.getValues().path("name").asText())
        .sorted()
        .toList();
  }

  @Transactional
  public void alterDatabase(String name, Database database) {
    acquireLockOrFail("database " + name, lockId("hive-db:" + name));
    var entity = requireDatabaseEntity(name);
    if (!name.equals(database.getName())) {
      // Hive has no rename-database, and allowing one here would orphan every table whose
      // databaseName still says otherwise.
      throw new HiveErrors.InvalidOperation("A database cannot be renamed: " + name);
    }
    entityService.updateValues(entity.getId(), databaseValues(database));
  }

  /**
   * @param cascade drop the database's tables with it; without it a non-empty database is refused,
   *     which is Hive's own behaviour
   */
  @Transactional
  public void dropDatabase(String name, boolean cascade) {
    acquireLockOrFail("database " + name, lockId("hive-db:" + name));
    var entity = requireDatabaseEntity(name);
    var tables = tableEntitiesOf(name);
    if (!tables.isEmpty() && !cascade) {
      throw new HiveErrors.InvalidOperation(
          "Database " + name + " is not empty and cascade was not requested");
    }
    for (var table : tables) {
      dropTableEntity(entity, table);
    }
    entityService.delete(entity.getId());
  }

  // ---------------------------------------------------------------------------------------------
  // Tables
  // ---------------------------------------------------------------------------------------------

  @Transactional
  public void createTable(Table table) {
    var databaseName = table.getDbName();
    var name = table.getTableName();
    acquireLockOrFail(
        "table " + key(databaseName, name), lockId("hive-table:" + key(databaseName, name)));
    var database = requireDatabaseEntity(databaseName);
    if (findTableEntity(databaseName, name).isPresent()) {
      throw new HiveErrors.AlreadyExists("Table already exists: " + key(databaseName, name));
    }
    var created = entityService.create(HiveModel.TABLE_TYPE, tableValues(table));
    entityService.link(database.getId(), RelationType.HAS_PART, created.getId());
  }

  @Transactional(readOnly = true)
  public Table getTable(String databaseName, String name) {
    return HiveValues.toTable(requireTableEntity(databaseName, name).getValues());
  }

  @Transactional(readOnly = true)
  public boolean tableExists(String databaseName, String name) {
    return findTableEntity(databaseName, name).isPresent();
  }

  @Transactional(readOnly = true)
  public List<String> getTables(String databaseName) {
    return tableEntitiesOf(databaseName).stream()
        .map(entity -> entity.getValues().path("name").asText())
        .sorted()
        .toList();
  }

  /**
   * Hive folds rename into {@code alter_table}: the identifier the call addresses and the one the
   * new definition carries may differ. A rename moves the containment link too when the database
   * changed, which is legal precisely because a database is not an aggregate.
   */
  @Transactional
  public void alterTable(String databaseName, String name, Table table) {
    var fromKey = key(databaseName, name);
    var toKey = key(table.getDbName(), table.getTableName());
    // Both locks, in ascending id order, so two concurrent renames cannot deadlock against
    // each other by taking them in opposite orders.
    var lockIds =
        new ArrayList<>(List.of(lockId("hive-table:" + fromKey), lockId("hive-table:" + toKey)));
    lockIds.sort(Integer::compareTo);
    for (var id : lockIds) {
      acquireLockOrFail("table " + fromKey, id);
    }

    var entity = requireTableEntity(databaseName, name);
    if (!fromKey.equals(toKey)) {
      if (findTableEntity(table.getDbName(), table.getTableName()).isPresent()) {
        throw new HiveErrors.AlreadyExists("Table already exists: " + toKey);
      }
      var targetDatabase = requireDatabaseEntity(table.getDbName());
      if (!databaseName.equals(table.getDbName())) {
        var sourceDatabase = requireDatabaseEntity(databaseName);
        entityService.unlink(sourceDatabase.getId(), RelationType.HAS_PART, entity.getId());
        entityService.link(targetDatabase.getId(), RelationType.HAS_PART, entity.getId());
      }
      // The partitions carry their table's identity in their own values.
      for (var partition : partitionEntitiesOf(databaseName, name)) {
        var values = (com.fasterxml.jackson.databind.node.ObjectNode) partition.getValues();
        values.put("databaseName", table.getDbName());
        values.put("tableName", table.getTableName());
        entityService.updateValues(partition.getId(), values.toPrettyString());
      }
    }
    entityService.updateValues(entity.getId(), tableValues(table));
  }

  @Transactional
  public void dropTable(String databaseName, String name) {
    acquireLockOrFail(
        "table " + key(databaseName, name), lockId("hive-table:" + key(databaseName, name)));
    var database = requireDatabaseEntity(databaseName);
    var table = requireTableEntity(databaseName, name);
    dropTableEntity(database, table);
  }

  // ---------------------------------------------------------------------------------------------
  // Partitions
  // ---------------------------------------------------------------------------------------------

  @Transactional
  public void addPartition(Partition partition) {
    var databaseName = partition.getDbName();
    var tableName = partition.getTableName();
    acquireLockOrFail(
        "table " + key(databaseName, tableName),
        lockId("hive-table:" + key(databaseName, tableName)));
    var table = requireTableEntity(databaseName, tableName);
    if (findPartitionEntity(databaseName, tableName, partition.getValues()).isPresent()) {
      throw new HiveErrors.AlreadyExists(
          "Partition already exists: "
              + key(databaseName, tableName)
              + " "
              + partition.getValues());
    }
    var created = entityService.create(HiveModel.PARTITION_TYPE, partitionValues(partition));
    entityService.link(table.getId(), RelationType.HAS_PART, created.getId());
  }

  @Transactional(readOnly = true)
  public Partition getPartition(String databaseName, String tableName, List<String> values) {
    return HiveValues.toPartition(
        findPartitionEntity(databaseName, tableName, values)
            .orElseThrow(
                () ->
                    new HiveErrors.NotFound(
                        "Partition not found: " + key(databaseName, tableName) + " " + values))
            .getValues());
  }

  @Transactional(readOnly = true)
  public List<Partition> getPartitions(String databaseName, String tableName, int max) {
    var partitions =
        partitionEntitiesOf(databaseName, tableName).stream()
            .map(entity -> HiveValues.toPartition(entity.getValues()))
            .toList();
    return max < 0 || max >= partitions.size() ? partitions : partitions.subList(0, max);
  }

  @Transactional
  public void alterPartition(String databaseName, String tableName, Partition partition) {
    acquireLockOrFail(
        "table " + key(databaseName, tableName),
        lockId("hive-table:" + key(databaseName, tableName)));
    var entity =
        findPartitionEntity(databaseName, tableName, partition.getValues())
            .orElseThrow(
                () ->
                    new HiveErrors.NotFound(
                        "Partition not found: "
                            + key(databaseName, tableName)
                            + " "
                            + partition.getValues()));
    entityService.updateValues(entity.getId(), partitionValues(partition));
  }

  /**
   * Dropping one partition needs the containment link gone first, and {@code EntityService.unlink}
   * refuses that — a partition is an {@code AggregateElement} and detaching it through the public
   * API would strand it. That refusal is the point of the model and stays: what happens here is a
   * deliberate teardown by the component that owns these entities, removing the relationship row
   * and then the entity, which is exactly what {@code AggregateService.delete} does for a whole
   * aggregate.
   */
  @Transactional
  public void dropPartition(String databaseName, String tableName, List<String> values) {
    acquireLockOrFail(
        "table " + key(databaseName, tableName),
        lockId("hive-table:" + key(databaseName, tableName)));
    var entity =
        findPartitionEntity(databaseName, tableName, values)
            .orElseThrow(
                () ->
                    new HiveErrors.NotFound(
                        "Partition not found: " + key(databaseName, tableName) + " " + values));
    detachAndDelete(entity);
  }

  // ---------------------------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------------------------

  /** Unlink from the database first — legal, a database is not an aggregate — then cascade. */
  private void dropTableEntity(Entity database, Entity table) {
    entityService.unlink(database.getId(), RelationType.HAS_PART, table.getId());
    aggregateService.delete(table.getId());
  }

  private void detachAndDelete(Entity entity) {
    entityRelationshipRepository.deleteAll(entityRelationshipRepository.findBySource(entity));
    entityRelationshipRepository.deleteAll(entityRelationshipRepository.findByTarget(entity));
    entityService.deleteInternal(entity.getId());
  }

  private Entity requireDatabaseEntity(String name) {
    return findDatabaseEntity(name)
        .orElseThrow(() -> new HiveErrors.NotFound("Database not found: " + name));
  }

  private Entity requireTableEntity(String databaseName, String name) {
    return findTableEntity(databaseName, name)
        .orElseThrow(() -> new HiveErrors.NotFound("Table not found: " + key(databaseName, name)));
  }

  private Optional<Entity> findDatabaseEntity(String name) {
    return single(
        entityService.list(
            HiveModel.DATABASE_TYPE, "$ ? (@.name == " + jsonPathString(name) + ")"));
  }

  private Optional<Entity> findTableEntity(String databaseName, String name) {
    return single(
        entityService.list(
            HiveModel.TABLE_TYPE,
            "$ ? (@.databaseName == "
                + jsonPathString(databaseName)
                + " && @.name == "
                + jsonPathString(name)
                + ")"));
  }

  private List<Entity> tableEntitiesOf(String databaseName) {
    return entityService.list(
        HiveModel.TABLE_TYPE, "$ ? (@.databaseName == " + jsonPathString(databaseName) + ")");
  }

  private List<Entity> partitionEntitiesOf(String databaseName, String tableName) {
    return entityService.list(
        HiveModel.PARTITION_TYPE,
        "$ ? (@.databaseName == "
            + jsonPathString(databaseName)
            + " && @.tableName == "
            + jsonPathString(tableName)
            + ")");
  }

  /**
   * Partition identity is the ordered value list, which jsonpath cannot compare as a whole, so the
   * table's partitions are narrowed in the database and the list match is done here. Tables with
   * very many partitions would want a derived key column; nothing in the protocol needs one yet.
   */
  private Optional<Entity> findPartitionEntity(
      String databaseName, String tableName, List<String> values) {
    return partitionEntitiesOf(databaseName, tableName).stream()
        .filter(entity -> partitionValuesOf(entity).equals(values))
        .findFirst();
  }

  private static List<String> partitionValuesOf(Entity entity) {
    var values = new ArrayList<String>();
    entity.getValues().path("values").forEach(value -> values.add(value.asText()));
    return values;
  }

  private String databaseValues(Database database) {
    return HiveValues.fromDatabase(jsonUtils.jsonMapper().createObjectNode(), database)
        .toPrettyString();
  }

  private String tableValues(Table table) {
    return HiveValues.fromTable(jsonUtils.jsonMapper().createObjectNode(), table).toPrettyString();
  }

  private String partitionValues(Partition partition) {
    return HiveValues.fromPartition(jsonUtils.jsonMapper().createObjectNode(), partition)
        .toPrettyString();
  }

  private static String key(String databaseName, String name) {
    return databaseName + HiveModel.KEY_SEPARATOR + name;
  }

  /** A name matching more than one entity is corruption, not a choice to make silently. */
  private static Optional<Entity> single(List<Entity> entities) {
    if (entities.size() > 1) {
      throw new ServiceError("Registry corruption: " + entities.size() + " entities share a name");
    }
    return entities.stream().findFirst();
  }

  private void acquireLockOrFail(String what, int id) {
    if (!acquireLockWithRetry(id)) {
      throw new HiveErrors.Contended(
          "Could not lock " + what + ": concurrent operation in progress");
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
