package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;
import static it.davidgreco.metacatalog.common.JsonUtils.mergeSchemas;
import static it.davidgreco.metacatalog.common.JsonUtils.stringToJsonSchema;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.Type;
import it.davidgreco.metacatalog.entity.TypeLinearization;
import it.davidgreco.metacatalog.repository.EntityTypeRepository;
import it.davidgreco.metacatalog.repository.EntityTypeVersionRepository;
import it.davidgreco.metacatalog.repository.TraitRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Service class for managing {@link EntityType} entities. */
@Slf4j
@Service
@Getter
@Setter
@RequiredArgsConstructor
@EnableCaching
public class EntityTypeService implements CommonTypeService<EntityType, String> {

  private static final String ENTITYTYPE = "EntityType ";

  private final EntityTypeRepository entityTypeRepository;

  private final TraitRepository traitRepository;

  private final EntityTypeVersionRepository entityTypeVersionRepository;

  /**
   * Creates a new EntityType with the specified name, traits, optional father, and schema.
   *
   * <p>This method validates that each trait in the list exists and is unique. It also converts the
   * provided schema to a JSON schema and merges it with the schemas of the traits and the optional
   * father EntityType. The resulting EntityType is stored in the repository.
   *
   * <p>The new type starts at version 1 with a freshly generated {@code versionGroupId}.
   *
   * @param name the name for the new EntityType
   * @param traits a list of trait names to associate with the EntityType
   * @param fatherName an optional name of the father EntityType, if any
   * @param schema the JSON schema string for the EntityType
   * @return the newly created and persisted EntityType
   * @throws ServiceError if a trait does not exist, a schema validation error occurs, or a data
   *     integrity violation occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class, DataIntegrityViolationException.class})
  public EntityType create(
      String name, List<String> traits, Optional<String> fatherName, String schema)
      throws ServiceError {
    log.info("Creating EntityType: {}", name);
    try {
      List<Trait> traitsList = resolveTraits(traits);
      var baseSchemaNode = parseSchema(schema);
      var entityType = new EntityType();
      entityType.setTraits(traitsList);
      entityType.setName(name);
      entityType.setBaseSchema(baseSchemaNode);
      entityType.setVersion(1);
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
      return entityTypeRepository.save(entityType);
    } catch (ServiceRuntimeError | DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    } finally {
      log.info("Created EntityType: {}", name);
    }
  }

  /**
   * Creates a new version of an existing EntityType by snapshotting the current live state into the
   * history table and mutating the live row in place with the new schema, traits, and father.
   *
   * <p>The live row's {@code version} is bumped by one; its {@code versionGroupId} is unchanged so
   * the new version stays linked to every previous snapshot. The snapshot is self-contained: base
   * schema, derived schema, father name and trait names are all frozen at the moment the snapshot
   * was taken.
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
   * @throws ServiceError if the type does not exist, a trait does not exist, a schema validation
   *     error occurs, or a data integrity violation occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class, DataIntegrityViolationException.class})
  public EntityType createVersion(
      String name, List<String> traits, Optional<String> fatherName, String schema)
      throws ServiceError {
    log.info("Creating new version of EntityType: {}", name);
    try {
      var live =
          entityTypeRepository
              .findByNameForUpdate(name)
              .orElseThrow(() -> new ServiceError(ENTITYTYPE + name + " not found"));

      var snapshot = new EntityTypeVersion();
      snapshot.setVersionGroupId(live.getVersionGroupId());
      snapshot.setVersion(live.getVersion());
      snapshot.setName(live.getName());
      snapshot.setBaseSchema(live.getBaseSchema());
      snapshot.setDerivedSchema(live.getDerivedSchema());
      snapshot.setFatherName(live.getFather() == null ? null : live.getFather().getName());
      snapshot.setTraits(
          jsonFactory.valueToTree(live.getTraits().stream().map(Trait::getName).toList()));
      snapshot.setCreatedAt(Instant.now());
      var latestSnapshot =
          entityTypeVersionRepository.findFirstByVersionGroupIdOrderByVersionDesc(
              live.getVersionGroupId());
      snapshot.setPreviousVersionId(latestSnapshot.map(EntityTypeVersion::getId).orElse(null));
      entityTypeVersionRepository.save(snapshot);

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
      live.setVersion(live.getVersion() + 1);
      return entityTypeRepository.save(live);
    } catch (ServiceRuntimeError | DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    } finally {
      log.info("Created new version of EntityType: {}", name);
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
   * @throws ServiceError if the type does not exist or the version does not exist
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Object readVersion(String name, int version) throws ServiceError {
    log.info("Reading EntityType: {} version: {}", name, version);
    try {
      var live =
          entityTypeRepository
              .findByName(name)
              .orElseThrow(() -> new ServiceError(ENTITYTYPE + name + " not found"));
      if (version == live.getVersion()) return live;
      if (version > live.getVersion() || version < 1)
        throw new ServiceError(
            "Version " + version + " of " + ENTITYTYPE + name + " does not exist");
      return entityTypeVersionRepository
          .findByVersionGroupIdAndVersion(live.getVersionGroupId(), version)
          .orElseThrow(
              () ->
                  new ServiceError(
                      "Version " + version + " of " + ENTITYTYPE + name + " does not exist"));
    } finally {
      log.info("Read EntityType: {} version: {}", name, version);
    }
  }

  /**
   * Lists every version of an EntityType, oldest first. The live (current) version is included as
   * the last element.
   *
   * @param name the name of the EntityType
   * @return a list of {@link EntityTypeVersion} snapshots (oldest first) followed by the live
   *     {@link EntityType}
   * @throws ServiceError if the type does not exist
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public List<Object> listVersions(String name) throws ServiceError {
    log.info("Listing versions of EntityType: {}", name);
    try {
      var live =
          entityTypeRepository
              .findByName(name)
              .orElseThrow(() -> new ServiceError(ENTITYTYPE + name + " not found"));
      var history =
          entityTypeVersionRepository.findByVersionGroupIdOrderByVersionAsc(
              live.getVersionGroupId());
      var result = new java.util.ArrayList<Object>(history);
      result.add(live);
      return result;
    } finally {
      log.info("Listed versions of EntityType: {}", name);
    }
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
   * @throws ServiceError if the type does not exist, the version does not exist, or the version is
   *     the current (live) version
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void deleteVersion(String name, int version) throws ServiceError {
    log.info("Deleting EntityType: {} version: {}", name, version);
    try {
      var live =
          entityTypeRepository
              .findByName(name)
              .orElseThrow(() -> new ServiceError(ENTITYTYPE + name + " not found"));
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
      var successor = entityTypeVersionRepository.findByPreviousVersionId(snapshot.getId());
      successor.ifPresent(
          s -> {
            s.setPreviousVersionId(snapshot.getPreviousVersionId());
            entityTypeVersionRepository.save(s);
          });
      entityTypeVersionRepository.delete(snapshot);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    } finally {
      log.info("Deleted EntityType: {} version: {}", name, version);
    }
  }

  /**
   * Deletes every historical snapshot of an EntityType, keeping the live row. The live row's {@code
   * version} and {@code versionGroupId} are unchanged, so the type continues to exist at its
   * current version with no history behind it. To remove the type together with all of its history,
   * use {@link #delete(String)}.
   *
   * @param name the name of the EntityType
   * @throws ServiceError if the type does not exist
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void deleteAllVersions(String name) throws ServiceError {
    log.info("Deleting all versions of EntityType: {}", name);
    try {
      var live =
          entityTypeRepository
              .findByName(name)
              .orElseThrow(() -> new ServiceError(ENTITYTYPE + name + " not found"));
      entityTypeVersionRepository.deleteByVersionGroupId(live.getVersionGroupId());
    } finally {
      log.info("Deleted all versions of EntityType: {}", name);
    }
  }

  /**
   * Reads an entity type by its name.
   *
   * @param name the name of the entity type to read
   * @return the EntityType with the given name
   * @throws ServiceError if the entity type is not found
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public EntityType read(String name) throws ServiceError {
    log.info("Reading EntityType: {}", name);
    try {
      return entityTypeRepository
          .findByName(name)
          .orElseThrow(() -> new ServiceError(ENTITYTYPE + name + " not found"));
    } finally {
      log.info("Read EntityType: {}", name);
    }
  }

  /**
   * Deletes an entity type given its name, together with all of its version history snapshots.
   *
   * @param name the name of the entity type to delete
   * @throws ServiceError if the entity type is not found, or if a {@link
   *     DataIntegrityViolationException} occurs while deleting the entity type
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void delete(String name) throws ServiceError {
    log.info("Deleting EntityType: {}", name);
    try {
      var entityType =
          entityTypeRepository
              .findByName(name)
              .orElseThrow(() -> new ServiceError(ENTITYTYPE + name + " not found"));
      entityTypeVersionRepository.deleteByVersionGroupId(entityType.getVersionGroupId());
      entityTypeRepository.delete(entityType);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    } finally {
      log.info("Deleted EntityType: {}", name);
    }
  }

  /**
   * Checks if an entity type with the given name exists.
   *
   * @param name the name of the entity type to check
   * @return true if an entity type with the given name exists, false otherwise
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public boolean exists(String name) {
    log.info("Checking if EntityType exists: {}", name);
    try {
      return entityTypeRepository.existsByName(name);
    } finally {
      log.info("Checked if EntityType exists: {}", name);
    }
  }

  /**
   * Retrieves all EntityTypes from the repository.
   *
   * @return a list of all EntityTypes
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public List<EntityType> list() {
    log.info("Listing all EntityTypes");
    try {
      return entityTypeRepository.findAll();
    } finally {
      log.info("Listed all EntityTypes");
    }
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
    try {
      return entityTypeRepository
          .findByName(name)
          .map(entityTypeRepository::countEntityTypeByFather)
          .orElse(0L);
    } finally {
      log.info("Counted children of EntityType: {}", name);
    }
  }

  private List<Trait> resolveTraits(List<String> traits) throws ServiceError {
    Set<String> traitNamesSet = new HashSet<>();
    traits.forEach(
        trait -> {
          if (traitNamesSet.contains(trait))
            throw new ServiceRuntimeError("Trait " + trait + " already defined");
          else traitNamesSet.add(trait);
        });
    return traits.stream()
        .map(
            trait ->
                traitRepository
                    .findByName(trait)
                    .orElseThrow(
                        () -> new ServiceRuntimeError("Trait " + trait + " does not exist")))
        .toList();
  }

  private JsonNode parseSchema(String schema) throws SchemaValidationError {
    var eitherSchema = stringToJsonSchema(schema);
    if (eitherSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
    return eitherSchema.get().getSchemaNode();
  }

  private JsonNode computeDerivedSchema(EntityType entityType) throws SchemaValidationError {
    var linearization = new java.util.ArrayList<>(TypeLinearization.linearize(entityType));
    java.util.Collections.reverse(linearization);
    var schemasToMerge = linearization.stream().map(Type::getBaseSchema).toList();
    var mergedSchema = mergeSchemas(schemasToMerge);
    if (mergedSchema.isLeft()) throw new SchemaValidationError(mergedSchema.getLeft());
    return mergedSchema.get();
  }
}
