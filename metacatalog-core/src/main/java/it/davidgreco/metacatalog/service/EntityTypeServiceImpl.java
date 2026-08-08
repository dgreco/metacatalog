package it.davidgreco.metacatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.ValidationMessage;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.Type;
import it.davidgreco.metacatalog.entity.TypeLinearization;
import it.davidgreco.metacatalog.repository.EntityRepository;
import it.davidgreco.metacatalog.repository.EntityTypeRepository;
import it.davidgreco.metacatalog.repository.EntityTypeVersionRepository;
import it.davidgreco.metacatalog.repository.MappingEntityTypeRelationshipRepository;
import it.davidgreco.metacatalog.repository.TraitRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link EntityTypeService}. */
@Slf4j
@RequiredArgsConstructor
public class EntityTypeServiceImpl implements EntityTypeService, ImmutableEntityTypeWriter {

  private static final String ENTITYTYPE = "EntityType ";

  private final EntityTypeRepository entityTypeRepository;

  private final TraitRepository traitRepository;

  private final EntityTypeVersionRepository entityTypeVersionRepository;

  private final EntityRepository entityRepository;

  private final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  private final JsonUtils jsonUtils;

  /**
   * Creates a new EntityType with the specified name, traits, optional father, and schema.
   *
   * <p>This method validates that each trait in the list exists and is unique. It also converts the
   * provided schema to a JSON schema and merges it with the schemas of the traits and the optional
   * father EntityType. The resulting EntityType is stored in the repository.
   *
   * <p>The new type starts at version 1 with a freshly generated {@code versionGroupId}. An {@link
   * EntityTypeVersion} snapshot is also created for v1, so entities created against this type have
   * a version row to pin to.
   *
   * @param name the name for the new EntityType
   * @param traits a list of trait names to associate with the EntityType
   * @param fatherName an optional name of the father EntityType, if any
   * @param schema the JSON schema string for the EntityType
   * @return the newly created and persisted EntityType
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public EntityType create(
      String name, List<String> traits, Optional<String> fatherName, String schema) {
    return doCreate(name, traits, fatherName, schema, false);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public EntityType createImmutable(
      String name, List<String> traits, Optional<String> fatherName, String schema) {
    return doCreate(name, traits, fatherName, schema, true);
  }

  /**
   * The shared body. A private helper rather than one public method delegating to another: a
   * self-invocation would bypass Spring's proxy, so the annotation on the inner method would be
   * ignored — harmless here only because both entry points above are themselves transactional, and
   * clearer when the shared code cannot be mistaken for an entry point at all.
   */
  private EntityType doCreate(
      String name,
      List<String> traits,
      Optional<String> fatherName,
      String schema,
      boolean immutable) {
    log.info("Creating EntityType: {}", name);
    try {
      List<Trait> traitsList = resolveTraits(traits);
      var baseSchemaNode = parseSchema(schema);
      var entityType = new EntityType();
      entityType.setTraits(traitsList);
      entityType.setName(name);
      entityType.setBaseSchema(baseSchemaNode);
      entityType.setVersion(1);
      entityType.setImmutable(immutable);
      entityType.setVersionGroupId(UUID.randomUUID().toString());
      if (fatherName.isPresent()) {
        var father =
            entityTypeRepository
                .findByName(fatherName.get())
                .orElseThrow(
                    () -> new ServiceError(ENTITYTYPE + fatherName.get() + " does not exist"));
        entityType.setFather(father);
      }
      entityType.setDerivedSchema(computeDerivedSchema(entityType));
      var saved = entityTypeRepository.save(entityType);
      saveCurrentSnapshot(saved, null);
      log.info("Created EntityType: {}", name);
      return saved;
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
    }
  }

  /**
   * Creates a new version of an existing EntityType by mutating the live row in place with the new
   * schema, traits, and father, and snapshotting the new live state into the history table.
   *
   * <p>The live row's {@code version} is bumped by one; its {@code versionGroupId} is unchanged so
   * the new version stays linked to every previous snapshot. The new snapshot is self-contained:
   * base schema, derived schema, father name and trait names are all frozen at the moment the
   * snapshot was taken.
   *
   * <p>An {@link EntityTypeVersion} row now exists for every version, including the current live
   * one, so entities can pin to the exact version they were created against via the {@code
   * entity_type_version_id} FK.
   *
   * <p>If the previous live state somehow had no snapshot yet (e.g. the type was created before
   * version pinning was introduced), a backfill snapshot is created for it before the live row is
   * mutated, so the version chain stays complete.
   *
   * <p>The live row is locked with a pessimistic write lock for the duration of the transaction, so
   * concurrent {@code createVersion} calls for the same type are serialised and the version number
   * is generated without races.
   *
   * @param name the name of the EntityType to version
   * @param traits the new list of trait names for the new version
   * @param fatherName the new optional father name for the new version
   * @param schema the new JSON schema string for the new version
   * @return the mutated (now current) live EntityType
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public EntityType createVersion(
      String name, List<String> traits, Optional<String> fatherName, String schema) {
    log.info("Creating new version of EntityType: {}", name);
    try {
      var live =
          entityTypeRepository
              .findByNameForUpdate(name)
              .orElseThrow(() -> new NotFoundException(ENTITYTYPE + name + " not found"));
      checkIsMutable(live, "versioned");

      var existingCurrentSnapshot =
          entityTypeVersionRepository.findByVersionGroupIdAndVersion(
              live.getVersionGroupId(), live.getVersion());
      if (existingCurrentSnapshot.isEmpty()) {
        var latestBefore =
            entityTypeVersionRepository.findFirstByVersionGroupIdOrderByVersionDesc(
                live.getVersionGroupId());
        saveCurrentSnapshot(live, latestBefore.map(EntityTypeVersion::getId).orElse(null));
      }

      List<Trait> traitsList = resolveTraits(traits);
      var baseSchemaNode = parseSchema(schema);
      live.getTraits().clear();
      live.getTraits().addAll(traitsList);
      live.setBaseSchema(baseSchemaNode);
      if (fatherName.isPresent()) {
        var father =
            entityTypeRepository
                .findByName(fatherName.get())
                .orElseThrow(
                    () -> new ServiceError(ENTITYTYPE + fatherName.get() + " does not exist"));
        live.setFather(father);
      } else {
        live.setFather(null);
      }
      live.setDerivedSchema(computeDerivedSchema(live));
      checkTargetMappingsAgainstNewSchema(live);
      live.setVersion(live.getVersion() + 1);
      var saved = entityTypeRepository.save(live);
      var latestSnapshot =
          entityTypeVersionRepository.findFirstByVersionGroupIdOrderByVersionDesc(
              live.getVersionGroupId());
      saveCurrentSnapshot(saved, latestSnapshot.map(EntityTypeVersion::getId).orElse(null));
      log.info("Created new version of EntityType: {}", name);
      return saved;
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
    }
  }

  /**
   * Refuses a version change that would break an existing mapping targeting this type.
   *
   * <p>A mapping's values are validated against the mapping schema derived from the target type's
   * schema only when the mapping is created; nothing re-validates them later. Without this check, a
   * new version whose schema an existing mapping no longer satisfies would be accepted, and the
   * breakage would surface only asynchronously — as FAILED lifecycle events — the next time the
   * mapping engine tried to create a mapped entity. Validating here turns that silent, deferred
   * failure into an immediate refusal of the version, while the mapping can still be updated or
   * deleted first.
   *
   * <p>Only the target side can be checked statically: a mapping's SpEL expressions read the
   * <em>values</em> of source entities, so a source-type schema change cannot be validated against
   * the mapping document.
   *
   * @param live the live entity type, already mutated to the candidate new version's state
   * @throws ServiceError if a mapping targeting this type does not satisfy the new schema
   */
  private void checkTargetMappingsAgainstNewSchema(EntityType live) {
    var mappings =
        mappingEntityTypeRelationshipRepository.findMappingEntityTypeRelationshipByTarget(live);
    if (mappings.isEmpty()) return;
    var validatingSchemaEither =
        jsonUtils.convertToMappingSchema(jsonUtils.jsonSchemaFactory().getSchema(live.getSchema()));
    if (validatingSchemaEither.isLeft())
      throw new SchemaValidationError(validatingSchemaEither.getLeft());
    var validatingSchema = validatingSchemaEither.get();
    for (var mapping : mappings) {
      var messages = validatingSchema.validate(mapping.getMappingValues());
      if (!messages.isEmpty()) {
        throw new ServiceError(
            "Cannot create a new version of EntityType "
                + live.getName()
                + ": the mapping from "
                + mapping.getSource().getName()
                + " to "
                + live.getName()
                + " does not satisfy the new schema ("
                + messages.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(java.util.stream.Collectors.joining("; "))
                + "). Update or delete the mapping first.");
      }
    }
  }

  /**
   * Reads a specific version of an EntityType.
   *
   * <p>If the requested version equals the live row's current version, the live {@link EntityType}
   * is returned. Otherwise the frozen {@link EntityTypeVersion} snapshot is returned. The caller
   * can distinguish the two with {@code instanceof}.
   *
   * @param name the name of the EntityType
   * @param version the version number to read
   * @return the live {@link EntityType} (if {@code version} is current) or the {@link
   *     EntityTypeVersion} snapshot
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public VersionResult<
          it.davidgreco.metacatalog.entity.EntityType,
          it.davidgreco.metacatalog.entity.EntityTypeVersion>
      readVersion(String name, int version) {
    log.info("Reading EntityType: {} version: {}", name, version);
    var live =
        entityTypeRepository
            .findByName(name)
            .orElseThrow(() -> new NotFoundException(ENTITYTYPE + name + " not found"));
    var result =
        TypeServiceSupport.resolveVersion(
            live,
            live.getVersion(),
            live.getVersionGroupId(),
            version,
            "Version " + version + " of " + ENTITYTYPE + name + " does not exist",
            entityTypeVersionRepository::findByVersionGroupIdAndVersion);
    log.info("Read EntityType: {} version: {}", name, version);
    @SuppressWarnings("unchecked")
    var wrapped =
        (VersionResult<
                it.davidgreco.metacatalog.entity.EntityType,
                it.davidgreco.metacatalog.entity.EntityTypeVersion>)
            VersionResult.wrap(
                result,
                it.davidgreco.metacatalog.entity.EntityType.class,
                it.davidgreco.metacatalog.entity.EntityTypeVersion.class);
    return wrapped;
  }

  /**
   * Lists every version of an EntityType, oldest first. The live (current) version is included as
   * the last element.
   *
   * <p>The snapshot matching the current live version is filtered out of the history list to avoid
   * duplicating the live row in the result; it still exists in the {@code entity_type_version}
   * table (entities pin to it via {@code entity_type_version_id}) but is represented here by the
   * live {@link EntityType}.
   *
   * @param name the name of the EntityType
   * @return a list of {@link EntityTypeVersion} snapshots (oldest first) followed by the live
   *     {@link EntityType}
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public List<
          VersionResult<
              it.davidgreco.metacatalog.entity.EntityType,
              it.davidgreco.metacatalog.entity.EntityTypeVersion>>
      listVersions(String name) {
    log.info("Listing versions of EntityType: {}", name);
    var live =
        entityTypeRepository
            .findByName(name)
            .orElseThrow(() -> new NotFoundException(ENTITYTYPE + name + " not found"));
    var history =
        new java.util.ArrayList<
            VersionResult<
                it.davidgreco.metacatalog.entity.EntityType,
                it.davidgreco.metacatalog.entity.EntityTypeVersion>>();
    entityTypeVersionRepository
        .findByVersionGroupIdOrderByVersionAsc(live.getVersionGroupId())
        .stream()
        .filter(s -> s.getVersion() != live.getVersion())
        .forEach(
            s ->
                history.add(
                    new VersionResult.Snapshot<
                        it.davidgreco.metacatalog.entity.EntityType,
                        it.davidgreco.metacatalog.entity.EntityTypeVersion>(s)));
    history.add(
        new VersionResult.Live<
            it.davidgreco.metacatalog.entity.EntityType,
            it.davidgreco.metacatalog.entity.EntityTypeVersion>(live));
    log.info("Listed versions of EntityType: {}", name);
    return history;
  }

  /**
   * Deletes a specific historical version of an EntityType.
   *
   * <p>The version chain is relinked so the predecessor and successor of the deleted snapshot
   * remain connected. Deleting the current (live) version is refused: use {@link #delete(String)}
   * to remove the whole type, or create a new version to revert.
   *
   * @param name the name of the EntityType
   * @param version the version number to delete
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void deleteVersion(String name, int version) {
    log.info("Deleting EntityType: {} version: {}", name, version);
    try {
      var live =
          entityTypeRepository
              .findByName(name)
              .orElseThrow(() -> new NotFoundException(ENTITYTYPE + name + " not found"));
      if (version == live.getVersion())
        throw new ServiceError(
            "Cannot delete the current version of "
                + ENTITYTYPE
                + name
                + "; create a new version to revert, or delete the type");
      if (version > live.getVersion() || version < 1)
        throw new ServiceError(
            "Version " + version + " of " + ENTITYTYPE + name + " does not exist");
      var snapshot =
          entityTypeVersionRepository
              .findByVersionGroupIdAndVersion(live.getVersionGroupId(), version)
              .orElseThrow(
                  () ->
                      new ServiceError(
                          "Version " + version + " of " + ENTITYTYPE + name + " does not exist"));
      var referencedCount = entityRepository.countByEntityTypeVersion(snapshot);
      if (referencedCount > 0)
        throw new ServiceError(
            "Version "
                + version
                + " of "
                + ENTITYTYPE
                + name
                + " is referenced by "
                + referencedCount
                + " entit"
                + (referencedCount == 1 ? "y" : "ies")
                + "; delete or migrate them first");
      unlinkAndDeleteSnapshot(snapshot, name);
      entityTypeVersionRepository.flush();
      log.info("Deleted EntityType: {} version: {}", name, version);
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
    }
  }

  /**
   * Deletes every historical snapshot of an EntityType, keeping the live row and the snapshot for
   * the current live version. The live row's {@code version} and {@code versionGroupId} are
   * unchanged, so the type continues to exist at its current version with no history behind it. The
   * current version's snapshot is preserved because entities may be pinned to it via {@code
   * entity_type_version_id}. To remove the type together with all of its history, use {@link
   * #delete(String)}.
   *
   * <p>If any historical snapshot is still referenced by an entity (via {@code
   * entity_type_version_id}), the deletion is refused up-front with a friendly message instead of
   * surfacing the raw {@link DataIntegrityViolationException}.
   *
   * @param name the name of the EntityType
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void deleteAllVersions(String name) {
    log.info("Deleting all versions of EntityType: {}", name);
    try {
      var live =
          entityTypeRepository
              .findByName(name)
              .orElseThrow(() -> new NotFoundException(ENTITYTYPE + name + " not found"));
      var history =
          entityTypeVersionRepository
              .findByVersionGroupIdOrderByVersionAsc(live.getVersionGroupId())
              .stream()
              .filter(s -> s.getVersion() != live.getVersion())
              .toList();
      for (var snapshot : history) {
        var referencedCount = entityRepository.countByEntityTypeVersion(snapshot);
        if (referencedCount > 0)
          throw new ServiceError(
              "Version "
                  + snapshot.getVersion()
                  + " of "
                  + ENTITYTYPE
                  + name
                  + " is referenced by "
                  + referencedCount
                  + " entit"
                  + (referencedCount == 1 ? "y" : "ies")
                  + "; delete or migrate them first");
      }
      for (var snapshot : history) {
        unlinkAndDeleteSnapshot(snapshot, name);
      }
      entityTypeVersionRepository.flush();
      log.info("Deleted all versions of EntityType: {}", name);
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
    }
  }

  /**
   * Reads an entity type by its name.
   *
   * @param name the name of the entity type to read
   * @return the EntityType with the given name
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public EntityType read(String name) {
    log.info("Reading EntityType: {}", name);
    var entityType =
        entityTypeRepository
            .findByName(name)
            .orElseThrow(() -> new NotFoundException(ENTITYTYPE + name + " not found"));
    log.info("Read EntityType: {}", name);
    return entityType;
  }

  /**
   * Deletes an entity type given its name, together with all of its version history snapshots.
   *
   * <p>Snapshots are deleted one by one in reverse version order so the self-referencing {@code
   * previous_version_id} FK on {@code entity_type_version} is not violated. If any snapshot is
   * still referenced by an entity (via {@code entity_type_version_id}), the deletion is refused
   * up-front with a friendly message instead of surfacing the raw {@link
   * DataIntegrityViolationException}.
   *
   * @param name the name of the entity type to delete
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void delete(String name) {
    log.info("Deleting EntityType: {}", name);
    try {
      var entityType =
          entityTypeRepository
              .findByName(name)
              .orElseThrow(() -> new NotFoundException(ENTITYTYPE + name + " not found"));
      checkIsMutable(entityType, "deleted");
      var snapshots =
          entityTypeVersionRepository
              .findByVersionGroupIdOrderByVersionAsc(entityType.getVersionGroupId())
              .stream()
              .sorted(java.util.Comparator.comparingInt(EntityTypeVersion::getVersion).reversed())
              .toList();
      for (var snapshot : snapshots) {
        var referencedCount = entityRepository.countByEntityTypeVersion(snapshot);
        if (referencedCount > 0)
          throw new ServiceError(
              "Version "
                  + snapshot.getVersion()
                  + " of "
                  + ENTITYTYPE
                  + name
                  + " is referenced by "
                  + referencedCount
                  + " entit"
                  + (referencedCount == 1 ? "y" : "ies")
                  + "; delete or migrate them first");
      }
      for (var snapshot : snapshots) {
        entityTypeVersionRepository.delete(snapshot);
      }
      entityTypeVersionRepository.flush();
      entityTypeRepository.delete(entityType);
      entityTypeRepository.flush();
      log.info("Deleted EntityType: {}", name);
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
    }
  }

  /**
   * Checks if an entity type with the given name exists.
   *
   * @param name the name of the entity type to check
   * @return true if an entity type with the given name exists, false otherwise
   */
  /**
   * Rejects an operation that would delete or rewrite an immutable entity type.
   *
   * <p>Only the type definition is frozen. Entities of an immutable type are still created, updated
   * and deleted normally, and a mutable type may still inherit from an immutable one — neither
   * writes to this row.
   *
   * @param entityType the live type the operation targets
   * @param operation the past participle naming what was attempted, e.g. {@code "deleted"}
   */
  private void checkIsMutable(EntityType entityType, String operation) {
    if (!entityType.isImmutable()) return;
    throw new ServiceError(
        ENTITYTYPE + entityType.getName() + " is immutable and cannot be " + operation + ".");
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public boolean exists(String name) {
    log.info("Checking if EntityType exists: {}", name);
    var exists = entityTypeRepository.existsByName(name);
    log.info("Checked if EntityType exists: {}", name);
    return exists;
  }

  /**
   * Retrieves all EntityTypes from the repository.
   *
   * @return a list of all EntityTypes
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public List<EntityType> list() {
    log.info("Listing all EntityTypes");
    var entityTypes = entityTypeRepository.findAll();
    log.info("Listed all EntityTypes");
    return entityTypes;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public List<EntityTypeVersion> listAllVersions() {
    log.info("Listing all EntityType versions");
    return entityTypeVersionRepository.findAll();
  }

  /**
   * Counts the number of child EntityTypes that inherit from the given EntityType.
   *
   * @param name the name of the parent EntityType
   * @return the number of child EntityTypes, or 0 if the EntityType is not found
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public long countEntityTypeChildren(String name) {
    log.info("Counting children of EntityType: {}", name);
    var count =
        entityTypeRepository
            .findByName(name)
            .map(entityTypeRepository::countEntityTypeByFather)
            .orElse(0L);
    log.info("Counted children of EntityType: {}", name);
    return count;
  }

  private List<Trait> resolveTraits(List<String> traits) {
    Set<String> traitNamesSet = new HashSet<>();
    traits.forEach(
        trait -> {
          if (traitNamesSet.contains(trait))
            throw new ServiceError("Trait " + trait + " already defined");
          else traitNamesSet.add(trait);
        });
    // Fetch all requested traits in a single query (avoids one findByName per trait), then re-order
    // to the requested declaration order (which is significant for trait linearization).
    var traitsByName =
        traitRepository.findByNameIn(traitNamesSet).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    Trait::getName, java.util.function.Function.identity()));
    return traits.stream()
        .map(
            trait -> {
              var resolved = traitsByName.get(trait);
              if (resolved == null) {
                throw new ServiceError("Trait " + trait + " does not exist");
              }
              return resolved;
            })
        .toList();
  }

  private JsonNode parseSchema(String schema) throws SchemaValidationError {
    return TypeServiceSupport.parseSchema(jsonUtils, schema);
  }

  private JsonNode computeDerivedSchema(EntityType entityType) throws SchemaValidationError {
    var linearization = new java.util.ArrayList<>(TypeLinearization.linearize(entityType));
    java.util.Collections.reverse(linearization);
    var schemasToMerge = linearization.stream().map(Type::getBaseSchema).toList();
    var mergedSchema = jsonUtils.mergeSchemas(schemasToMerge);
    if (mergedSchema.isLeft()) throw new SchemaValidationError(mergedSchema.getLeft());
    return mergedSchema.get();
  }

  /**
   * Captures the current live state of {@code live} into a new {@link EntityTypeVersion} snapshot
   * and persists it. The snapshot's {@code previousVersionId} is set to {@code previousVersionId}
   * (or null for the first version). Used by {@link #create} (for v1) and {@link #createVersion}
   * (for the new bumped version), so an entity can pin to the exact version it was created against.
   */
  private EntityTypeVersion saveCurrentSnapshot(EntityType live, String previousVersionId) {
    var snapshot = new EntityTypeVersion();
    snapshot.setVersionGroupId(live.getVersionGroupId());
    snapshot.setVersion(live.getVersion());
    snapshot.setName(live.getName());
    snapshot.setBaseSchema(live.getBaseSchema());
    snapshot.setDerivedSchema(live.getDerivedSchema());
    snapshot.setFatherName(live.getFather() == null ? null : live.getFather().getName());
    snapshot.setTraits(
        jsonUtils.jsonMapper().valueToTree(live.getTraits().stream().map(Trait::getName).toList()));
    snapshot.setCreatedAt(Instant.now());
    snapshot.setPreviousVersionId(previousVersionId);
    return entityTypeVersionRepository.save(snapshot);
  }

  /**
   * Relinks the snapshot's successor to its predecessor, then deletes the snapshot. The caller is
   * responsible for checking that no entities are pinned to the snapshot beforehand.
   */
  private void unlinkAndDeleteSnapshot(EntityTypeVersion snapshot, String name) {
    var successor = entityTypeVersionRepository.findByPreviousVersionId(snapshot.getId());
    successor.ifPresent(
        s -> {
          s.setPreviousVersionId(snapshot.getPreviousVersionId());
          entityTypeVersionRepository.save(s);
        });
    entityTypeVersionRepository.delete(snapshot);
  }
}
