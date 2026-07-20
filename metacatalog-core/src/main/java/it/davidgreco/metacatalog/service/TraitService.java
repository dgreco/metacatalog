package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.EMPTY_SCHEMA;
import static it.davidgreco.metacatalog.common.JsonUtils.mergeSchemas;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitRelationship;
import it.davidgreco.metacatalog.entity.TraitVersion;
import it.davidgreco.metacatalog.repository.TraitRelationshipRepository;
import it.davidgreco.metacatalog.repository.TraitRepository;
import it.davidgreco.metacatalog.repository.TraitVersionRepository;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Service class for managing {@link Trait} entities. */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableCaching
public class TraitService implements CommonTypeService<Trait, String> {

  private static final String TRAIT = "Trait ";
  private static final String NOT_FOUND = " not found";

  private final TraitRepository traitRepository;

  private final TraitRelationshipRepository traitRelationshipRepository;

  private final TraitVersionRepository traitVersionRepository;

  /**
   * Creates a new Trait with the specified name, optional schema, and optional father.
   *
   * <p>This method validates that the trait does not already exist, and that the father trait
   * exists, if specified. It also merges the provided schema with the schema of the father trait,
   * if any, and sets the resulting schema as the derived schema of the new trait.
   *
   * @param name the name for the new Trait
   * @param schema an optional JSON schema for the Trait
   * @param fatherName an optional name of the father Trait, if any
   * @return the newly created and persisted Trait
   * @throws ServiceError if the trait already exists, the father trait does not exist, a schema
   *     validation error occurs, or a data integrity violation occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Trait create(String name, Optional<String> schema, Optional<String> fatherName)
      throws ServiceError {
    log.info("Creating Trait: {}", name);
    try {
      var baseSchemaNode = parseSchema(schema);
      var trait = new Trait();
      trait.setName(name);
      trait.setBaseSchema(baseSchemaNode);
      trait.setVersion(1);
      trait.setVersionGroupId(UUID.randomUUID().toString());
      if (fatherName.isPresent()) {
        var father =
            traitRepository
                .findByName(fatherName.get())
                .orElseThrow(() -> new ServiceError(TRAIT + fatherName.get() + " does not exist"));
        trait.setFather(father);
      }
      trait.setDerivedSchema(computeDerivedSchema(trait));
      var saved = traitRepository.save(trait);
      log.info("Created Trait: {}", name);
      return saved;
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Creates a new version of an existing Trait by snapshotting the current live state into the
   * history table and mutating the live row in place with the new schema and father.
   *
   * <p>The live row's {@code version} is bumped by one; its {@code versionGroupId} is unchanged so
   * the new version stays linked to every previous snapshot. The snapshot is self-contained: base
   * schema, derived schema and father name are all frozen at the moment the snapshot was taken.
   *
   * <p>The live row is locked with a pessimistic write lock for the duration of the transaction, so
   * concurrent {@code createVersion} calls for the same trait are serialised.
   *
   * @param name the name of the Trait to version
   * @param schema the new optional JSON schema for the new version
   * @param fatherName the new optional father name for the new version
   * @return the mutated (now current) live Trait
   * @throws ServiceError if the trait does not exist, a schema validation error occurs, or a data
   *     integrity violation occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Trait createVersion(String name, Optional<String> schema, Optional<String> fatherName)
      throws ServiceError {
    log.info("Creating new version of Trait: {}", name);
    try {
      var live =
          traitRepository
              .findByNameForUpdate(name)
              .orElseThrow(() -> new ServiceError(TRAIT + name + NOT_FOUND));

      var snapshot = new TraitVersion();
      snapshot.setVersionGroupId(live.getVersionGroupId());
      snapshot.setVersion(live.getVersion());
      snapshot.setName(live.getName());
      snapshot.setBaseSchema(live.getBaseSchema());
      snapshot.setDerivedSchema(live.getDerivedSchema());
      snapshot.setFatherName(live.getFather() == null ? null : live.getFather().getName());
      snapshot.setCreatedAt(Instant.now());
      var latestSnapshot =
          traitVersionRepository.findFirstByVersionGroupIdOrderByVersionDesc(
              live.getVersionGroupId());
      snapshot.setPreviousVersionId(latestSnapshot.map(TraitVersion::getId).orElse(null));
      traitVersionRepository.save(snapshot);

      var baseSchemaNode = parseSchema(schema);
      live.setBaseSchema(baseSchemaNode);
      if (fatherName.isPresent()) {
        var father =
            traitRepository
                .findByName(fatherName.get())
                .orElseThrow(() -> new ServiceError(TRAIT + fatherName.get() + " does not exist"));
        live.setFather(father);
      } else {
        live.setFather(null);
      }
      live.setDerivedSchema(computeDerivedSchema(live));
      live.setVersion(live.getVersion() + 1);
      var saved = traitRepository.save(live);
      log.info("Created new version of Trait: {}", name);
      return saved;
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Reads a specific version of a Trait.
   *
   * <p>If the requested version equals the live row's current version, the live {@link Trait} is
   * returned. Otherwise the frozen {@link TraitVersion} snapshot is returned. The caller can
   * distinguish the two with {@code instanceof}.
   *
   * @param name the name of the Trait
   * @param version the version number to read
   * @return the live {@link Trait} (if {@code version} is current) or the {@link TraitVersion}
   *     snapshot
   * @throws ServiceError if the trait does not exist or the version does not exist
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Object readVersion(String name, int version) throws ServiceError {
    log.info("Reading Trait: {} version: {}", name, version);
    var live =
        traitRepository
            .findByName(name)
            .orElseThrow(() -> new ServiceError(TRAIT + name + NOT_FOUND));
    var result =
        TypeServiceSupport.resolveVersion(
            live,
            live.getVersion(),
            live.getVersionGroupId(),
            version,
            "Version " + version + " of " + TRAIT + name + " does not exist",
            traitVersionRepository::findByVersionGroupIdAndVersion);
    log.info("Read Trait: {} version: {}", name, version);
    return result;
  }

  /**
   * Lists every version of a Trait, oldest first. The live (current) version is included as the
   * last element.
   *
   * @param name the name of the Trait
   * @return a list of {@link TraitVersion} snapshots (oldest first) followed by the live {@link
   *     Trait}
   * @throws ServiceError if the trait does not exist
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public List<Object> listVersions(String name) throws ServiceError {
    log.info("Listing versions of Trait: {}", name);
    var live =
        traitRepository
            .findByName(name)
            .orElseThrow(() -> new ServiceError(TRAIT + name + NOT_FOUND));
    var history =
        traitVersionRepository.findByVersionGroupIdOrderByVersionAsc(live.getVersionGroupId());
    var result = new ArrayList<Object>(history);
    result.add(live);
    log.info("Listed versions of Trait: {}", name);
    return result;
  }

  /**
   * Deletes a specific historical version of a Trait.
   *
   * <p>The version chain is relinked so the predecessor and successor of the deleted snapshot
   * remain connected. Deleting the current (live) version is refused: use {@link #delete(String)}
   * to remove the whole trait, or create a new version to revert.
   *
   * @param name the name of the Trait
   * @param version the version number to delete
   * @throws ServiceError if the trait does not exist, the version does not exist, or the version is
   *     the current (live) version
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void deleteVersion(String name, int version) throws ServiceError {
    log.info("Deleting Trait: {} version: {}", name, version);
    try {
      var live =
          traitRepository
              .findByName(name)
              .orElseThrow(() -> new ServiceError(TRAIT + name + NOT_FOUND));
      if (version == live.getVersion())
        throw new ServiceError(
            "Cannot delete the current version of "
                + TRAIT
                + name
                + "; create a new version to revert, or delete the trait");
      if (version > live.getVersion() || version < 1)
        throw new ServiceError("Version " + version + " of " + TRAIT + name + " does not exist");
      var snapshot =
          traitVersionRepository
              .findByVersionGroupIdAndVersion(live.getVersionGroupId(), version)
              .orElseThrow(
                  () ->
                      new ServiceError(
                          "Version " + version + " of " + TRAIT + name + " does not exist"));
      var successor = traitVersionRepository.findByPreviousVersionId(snapshot.getId());
      successor.ifPresent(
          s -> {
            s.setPreviousVersionId(snapshot.getPreviousVersionId());
            traitVersionRepository.save(s);
          });
      traitVersionRepository.delete(snapshot);
      log.info("Deleted Trait: {} version: {}", name, version);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Deletes every historical snapshot of a Trait, keeping the live row. The live row's {@code
   * version} and {@code versionGroupId} are unchanged, so the trait continues to exist at its
   * current version with no history behind it. To remove the trait together with all of its
   * history, use {@link #delete(String)}.
   *
   * @param name the name of the Trait
   * @throws ServiceError if the trait does not exist
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void deleteAllVersions(String name) throws ServiceError {
    log.info("Deleting all versions of Trait: {}", name);
    var live =
        traitRepository
            .findByName(name)
            .orElseThrow(() -> new ServiceError(TRAIT + name + NOT_FOUND));
    traitVersionRepository.deleteByVersionGroupId(live.getVersionGroupId());
    log.info("Deleted all versions of Trait: {}", name);
  }

  /**
   * Reads a Trait given its name.
   *
   * @param name the name of the Trait to read
   * @return the Trait with the given name
   * @throws ServiceError if the Trait is not found
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Trait read(String name) throws ServiceError {
    log.info("Reading Trait: {}", name);
    var trait =
        traitRepository
            .findByName(name)
            .orElseThrow(() -> new ServiceError(TRAIT + name + NOT_FOUND));
    log.info("Read Trait: {}", name);
    return trait;
  }

  /**
   * Deletes a Trait given its name.
   *
   * <p>This method attempts to find the Trait by its name and delete it from the repository. If the
   * Trait is not found, a {@link ServiceError} is thrown. If a data integrity violation occurs
   * during the deletion process, a {@link ServiceError} is also thrown.
   *
   * @param name the name of the Trait to delete
   * @throws ServiceError if the Trait is not found, or if a {@link DataIntegrityViolationException}
   *     occurs while deleting the Trait
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void delete(String name) throws ServiceError {
    log.info("Deleting Trait: {}", name);
    try {
      var entityType =
          traitRepository
              .findByName(name)
              .orElseThrow(() -> new ServiceError(TRAIT + name + NOT_FOUND));
      traitVersionRepository.deleteByVersionGroupId(entityType.getVersionGroupId());
      traitRepository.delete(entityType);
      log.info("Deleted Trait: {}", name);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Checks if a Trait with the given name exists in the repository.
   *
   * @param name the name of the Trait to check for existence
   * @return true if a Trait with the given name exists, false otherwise
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public boolean exists(String name) {
    log.info("Checking if Trait exists: {}", name);
    var exists = traitRepository.existsByName(name);
    log.info("Checked if Trait exists: {}", name);
    return exists;
  }

  /**
   * Retrieves all Traits from the repository.
   *
   * @return a list of all Traits
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public List<Trait> list() {
    log.info("Listing all Traits");
    var traits = traitRepository.findAll();
    log.info("Listed all Traits");
    return traits;
  }

  /**
   * Links two Traits with a given relation type.
   *
   * <p>This method validates that the two Traits exist, and that the link does not already exist.
   * It also checks for loops before creating the link. A self-referential link (source and target
   * are the same Trait) is permitted and is not treated as a loop.
   *
   * @param sourceTraitName the name of the first Trait
   * @param relType the relation type to use for the link
   * @param targetTraitName the name of the second Trait
   * @throws ServiceError if the Traits do not exist, the link already exists, a loop is detected,
   *     or a data integrity violation occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void link(String sourceTraitName, RelationType relType, String targetTraitName)
      throws ServiceError {
    log.info("Linking Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
    try {
      if (wouldCreateLoop(sourceTraitName, targetTraitName, relType)) {
        throw new ServiceError("Loops are not allowed");
      }
      var sourceTrait =
          traitRepository
              .findByName(sourceTraitName)
              .orElseThrow(() -> new ServiceError(TRAIT + sourceTraitName + NOT_FOUND));
      var targetTrait =
          traitRepository
              .findByName(targetTraitName)
              .orElseThrow(() -> new ServiceError(TRAIT + targetTraitName + NOT_FOUND));
      var directRel = new TraitRelationship();
      directRel.setSource(sourceTrait);
      directRel.setTarget(targetTrait);
      directRel.setRelationType(relType);
      traitRelationshipRepository.save(directRel);
      if (relType.hasInverse()) {
        var inverseRel = new TraitRelationship();
        inverseRel.setSource(targetTrait);
        inverseRel.setTarget(sourceTrait);
        inverseRel.setRelationType(relType.inverse());
        traitRelationshipRepository.save(inverseRel);
      }
      log.info("Linked Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Unlinks two Traits with a given relation type.
   *
   * <p>This method validates that the two Traits exist, and that the link does not already exist.
   * It also checks for loops before creating the link.
   *
   * @param sourceTraitName the name of the first Trait
   * @param relType the relation type to use for the link
   * @param targetTraitName the name of the second Trait
   * @throws ServiceError if the Traits do not exist, the link already exists, a loop is detected,
   *     or a data integrity violation occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void unlink(String sourceTraitName, RelationType relType, String targetTraitName)
      throws ServiceError {
    log.info("Unlinking Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
    var sourceTrait =
        traitRepository
            .findByName(sourceTraitName)
            .orElseThrow(() -> new ServiceError(TRAIT + sourceTraitName + NOT_FOUND));
    var targetTrait =
        traitRepository
            .findByName(targetTraitName)
            .orElseThrow(() -> new ServiceError(TRAIT + targetTraitName + NOT_FOUND));
    var rel =
        traitRelationshipRepository
            .findBySourceAndRelationTypeAndTarget(sourceTrait, relType, targetTrait)
            .orElseThrow(
                () ->
                    new ServiceError(
                        TRAIT
                            + sourceTraitName
                            + " does not have a relationship "
                            + relType
                            + " with "
                            + targetTraitName));
    traitRelationshipRepository.delete(rel);
    if (relType.hasInverse()) {
      var inverseRel =
          traitRelationshipRepository
              .findBySourceAndRelationTypeAndTarget(targetTrait, relType.inverse(), sourceTrait)
              .orElseThrow(
                  () ->
                      new ServiceError(
                          TRAIT
                              + targetTraitName
                              + " does not have a relationship "
                              + relType
                              + " with "
                              + sourceTraitName));
      traitRelationshipRepository.delete(inverseRel);
    }
    log.info("Unlinked Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
  }

  /**
   * Retrieves all Traits linked to the given trait with the specified relation type.
   *
   * @param traitName1 the name of the source Trait
   * @param relType the relation type to filter the links
   * @return a list of Traits that are targets of the specified relation type from the source Trait
   * @throws ServiceError if the source Trait is not found
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public List<Trait> linked(String traitName1, RelationType relType) throws ServiceError {
    log.info("Linked Trait: {}", traitName1);
    var trait1 =
        traitRepository
            .findByName(traitName1)
            .orElseThrow(() -> new ServiceError(TRAIT + traitName1 + NOT_FOUND));
    var linkedTraits =
        traitRelationshipRepository.findBySourceAndRelationType(trait1, relType).stream()
            .map(TraitRelationship::getTarget)
            .toList();
    log.info("Linked Trait: {}", traitName1);
    return linkedTraits;
  }

  /**
   * Counts the number of child Traits that inherit from the given Trait.
   *
   * @param name the name of the parent Trait
   * @return the number of child Traits, or 0 if the Trait is not found
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public long countTraitChildren(String name) {
    log.info("Counting children of Trait: {}", name);
    var count =
        traitRepository.findByName(name).map(traitRepository::countTraitByFather).orElse(0L);
    log.info("Counting children of Trait: {}", name);
    return count;
  }

  /**
   * Determines whether adding a {@code source --relType--> target} edge would close a cycle, i.e.
   * whether {@code target} can already reach {@code source} through a path of one or more existing
   * {@code relType} relationships.
   *
   * <p>Performs an iterative depth-first search seeded with the neighbours of {@code target} so
   * that every outgoing edge of every visited trait is explored (a visited set prevents
   * re-traversal and guards against runaway recursion). Seeding with the neighbours rather than
   * {@code target} itself means a self-referential link (source equals target) is not treated as a
   * loop, since only paths of length one or more count.
   */
  private boolean wouldCreateLoop(
      String sourceTraitName, String targetTraitName, RelationType relType) throws ServiceError {
    var visited = new HashSet<String>();
    var toVisit = new ArrayDeque<>(neighbours(targetTraitName, relType));
    while (!toVisit.isEmpty()) {
      var current = toVisit.pop();
      if (current.equals(sourceTraitName)) {
        return true;
      }
      if (!visited.add(current)) {
        continue;
      }
      toVisit.addAll(neighbours(current, relType));
    }
    return false;
  }

  /** Returns the names of the traits reachable from {@code traitName} via a single relType edge. */
  private List<String> neighbours(String traitName, RelationType relType) throws ServiceError {
    var trait =
        traitRepository
            .findByName(traitName)
            .orElseThrow(() -> new ServiceError(TRAIT + traitName + NOT_FOUND));
    return traitRelationshipRepository.findBySourceAndRelationType(trait, relType).stream()
        .map(rel -> rel.getTarget().getName())
        .toList();
  }

  private JsonNode parseSchema(Optional<String> schema) throws SchemaValidationError {
    return TypeServiceSupport.parseSchema(schema.orElse(EMPTY_SCHEMA));
  }

  private JsonNode computeDerivedSchema(Trait trait) throws SchemaValidationError {
    List<JsonNode> schemasToMerge = new ArrayList<>();
    if (trait.getFather() != null) {
      schemasToMerge.add(trait.getFather().getSchema());
    }
    schemasToMerge.add(trait.getBaseSchema());
    var mergedSchema = mergeSchemas(schemasToMerge);
    if (mergedSchema.isLeft()) throw new SchemaValidationError(mergedSchema.getLeft());
    return mergedSchema.get();
  }
}
