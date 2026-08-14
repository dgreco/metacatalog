package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.EMPTY_SCHEMA;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitRelationship;
import it.davidgreco.metacatalog.entity.TraitVersion;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.TraitRelationshipRepository;
import it.davidgreco.metacatalog.repository.TraitRepository;
import it.davidgreco.metacatalog.repository.TraitVersionRepository;
import java.time.Instant;
import java.util.ArrayList;
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

/** Default implementation of {@link TraitService}. */
@Slf4j
@RequiredArgsConstructor
public class TraitServiceImpl implements TraitService, ImmutableTraitWriter {

  private static final String TRAIT = "Trait ";
  private static final String NOT_FOUND = " not found";

  private final TraitRepository traitRepository;

  private final TraitRelationshipRepository traitRelationshipRepository;

  private final TraitVersionRepository traitVersionRepository;

  /** Used to refuse removing a trait relationship that existing instance links rely on. */
  private final EntityRelationshipRepository entityRelationshipRepository;

  private final JsonUtils jsonUtils;

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
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public Trait create(String name, Optional<String> schema, Optional<String> fatherName) {
    return doCreate(name, schema, fatherName, false);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Trait createImmutable(String name, Optional<String> schema, Optional<String> fatherName) {
    return doCreate(name, schema, fatherName, true);
  }

  /**
   * The shared body. A private helper rather than one public method delegating to another: a
   * self-invocation would bypass Spring's proxy, so the annotation on the inner method would be
   * ignored — harmless here only because both entry points above are themselves transactional, and
   * clearer when the shared code cannot be mistaken for an entry point at all.
   */
  private Trait doCreate(
      String name, Optional<String> schema, Optional<String> fatherName, boolean immutable) {
    log.info("Creating Trait: {}", name);
    try {
      var baseSchemaNode = parseSchema(schema);
      var trait = new Trait();
      trait.setName(name);
      trait.setBaseSchema(baseSchemaNode);
      trait.setVersion(1);
      trait.setImmutable(immutable);
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
      throw ServiceError.forDataIntegrity(e);
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
   * <p>Refused outright for an immutable trait: versioning mutates the live row in place, which is
   * exactly what the flag forbids.
   *
   * @param name the name of the Trait to version
   * @param schema the new optional JSON schema for the new version
   * @param fatherName the new optional father name for the new version
   * @return the mutated (now current) live Trait
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public Trait createVersion(String name, Optional<String> schema, Optional<String> fatherName) {
    log.info("Creating new version of Trait: {}", name);
    try {
      var live =
          traitRepository
              .findByNameForUpdate(name)
              .orElseThrow(() -> new NotFoundException(TRAIT + name + NOT_FOUND));
      checkIsMutable(live, "versioned");

      var carriedAggregate = ServiceUtils.carriesTrait(live, BuiltInTraits.AGGREGATE);
      var carriedElement = ServiceUtils.carriesTrait(live, BuiltInTraits.AGGREGATE_ELEMENT);

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
      if (carriedAggregate != ServiceUtils.carriesTrait(live, BuiltInTraits.AGGREGATE)
          || carriedElement != ServiceUtils.carriesTrait(live, BuiltInTraits.AGGREGATE_ELEMENT)) {
        checkAggregateContainmentsStillHold(live);
      }
      live.setVersion(live.getVersion() + 1);
      var saved = traitRepository.save(live);
      log.info("Created new version of Trait: {}", name);
      return saved;
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
    }
  }

  /**
   * Refuses a version whose father change alters the trait's built-in carriage in a way that leaves
   * an existing containment violating the aggregate-model rules — mirroring the mapping
   * re-validation {@code EntityTypeServiceImpl.createVersion} performs. Without it the violation
   * would exist silently: un-recreatable through {@code link}, and surfacing only when an aggregate
   * is next read or provisioned.
   *
   * <p>Only relationships and links whose carriage actually flows through this trait are
   * re-checked, so a pre-existing violation elsewhere never blocks an unrelated version.
   *
   * @param live the live trait, already mutated to the candidate new version's state
   * @throws ServiceError if an existing containment would violate the rules under the new carriage
   */
  private void checkAggregateContainmentsStillHold(Trait live) {
    var name = live.getName();
    try {
      traitRelationshipRepository.findByRelationType(RelationType.HAS_PART).stream()
          .filter(
              rel ->
                  ServiceUtils.carriesTrait(rel.getSource(), name)
                      || ServiceUtils.carriesTrait(rel.getTarget(), name))
          .forEach(
              rel -> ServiceUtils.checkAggregateTraitContainment(rel.getSource(), rel.getTarget()));
      entityRelationshipRepository.findByRelationType(RelationType.HAS_PART).stream()
          .filter(
              rel ->
                  ServiceUtils.implementsTrait(rel.getSource().getEntityType(), name)
                      || ServiceUtils.implementsTrait(rel.getTarget().getEntityType(), name))
          .forEach(
              rel ->
                  ServiceUtils.checkAggregateEntityContainment(rel.getSource(), rel.getTarget()));
    } catch (ServiceError e) {
      throw new ServiceError(
          "Versioning Trait "
              + name
              + " would leave an existing containment violating the aggregate model: "
              + e.getMessage()
              + " Remove or update the relationship first.");
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
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public VersionResult<
          it.davidgreco.metacatalog.entity.Trait, it.davidgreco.metacatalog.entity.TraitVersion>
      readVersion(String name, int version) {
    log.info("Reading Trait: {} version: {}", name, version);
    var live =
        traitRepository
            .findByName(name)
            .orElseThrow(() -> new NotFoundException(TRAIT + name + NOT_FOUND));
    var result =
        TypeServiceSupport.resolveVersion(
            live,
            live.getVersion(),
            live.getVersionGroupId(),
            version,
            "Version " + version + " of " + TRAIT + name + " does not exist",
            traitVersionRepository::findByVersionGroupIdAndVersion);
    log.info("Read Trait: {} version: {}", name, version);
    @SuppressWarnings("unchecked")
    var wrapped =
        (VersionResult<
                it.davidgreco.metacatalog.entity.Trait,
                it.davidgreco.metacatalog.entity.TraitVersion>)
            VersionResult.wrap(
                result,
                it.davidgreco.metacatalog.entity.Trait.class,
                it.davidgreco.metacatalog.entity.TraitVersion.class);
    return wrapped;
  }

  /**
   * Lists every version of a Trait, oldest first. The live (current) version is included as the
   * last element.
   *
   * @param name the name of the Trait
   * @return a list of {@link TraitVersion} snapshots (oldest first) followed by the live {@link
   *     Trait}
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public List<
          VersionResult<
              it.davidgreco.metacatalog.entity.Trait,
              it.davidgreco.metacatalog.entity.TraitVersion>>
      listVersions(String name) {
    log.info("Listing versions of Trait: {}", name);
    var live =
        traitRepository
            .findByName(name)
            .orElseThrow(() -> new NotFoundException(TRAIT + name + NOT_FOUND));
    var history =
        new java.util.ArrayList<
            VersionResult<
                it.davidgreco.metacatalog.entity.Trait,
                it.davidgreco.metacatalog.entity.TraitVersion>>();
    traitVersionRepository.findByVersionGroupIdOrderByVersionAsc(live.getVersionGroupId()).stream()
        .forEach(
            s ->
                history.add(
                    new VersionResult.Snapshot<
                        it.davidgreco.metacatalog.entity.Trait,
                        it.davidgreco.metacatalog.entity.TraitVersion>(s)));
    history.add(
        new VersionResult.Live<
            it.davidgreco.metacatalog.entity.Trait, it.davidgreco.metacatalog.entity.TraitVersion>(
            live));
    log.info("Listed versions of Trait: {}", name);
    return history;
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
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void deleteVersion(String name, int version) {
    log.info("Deleting Trait: {} version: {}", name, version);
    try {
      var live =
          traitRepository
              .findByName(name)
              .orElseThrow(() -> new NotFoundException(TRAIT + name + NOT_FOUND));
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
      throw ServiceError.forDataIntegrity(e);
    }
  }

  /**
   * Deletes every historical snapshot of a Trait, keeping the live row. The live row's {@code
   * version} and {@code versionGroupId} are unchanged, so the trait continues to exist at its
   * current version with no history behind it. To remove the trait together with all of its
   * history, use {@link #delete(String)}.
   *
   * @param name the name of the Trait
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void deleteAllVersions(String name) {
    log.info("Deleting all versions of Trait: {}", name);
    var live =
        traitRepository
            .findByName(name)
            .orElseThrow(() -> new NotFoundException(TRAIT + name + NOT_FOUND));
    traitVersionRepository.deleteByVersionGroupId(live.getVersionGroupId());
    log.info("Deleted all versions of Trait: {}", name);
  }

  /**
   * Reads a Trait given its name.
   *
   * @param name the name of the Trait to read
   * @return the Trait with the given name
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public Trait read(String name) {
    log.info("Reading Trait: {}", name);
    var trait =
        traitRepository
            .findByName(name)
            .orElseThrow(() -> new NotFoundException(TRAIT + name + NOT_FOUND));
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
   * <p>An immutable Trait is refused.
   *
   * @param name the name of the Trait to delete
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void delete(String name) {
    log.info("Deleting Trait: {}", name);
    try {
      var entityType =
          traitRepository
              .findByName(name)
              .orElseThrow(() -> new NotFoundException(TRAIT + name + NOT_FOUND));
      checkIsMutable(entityType, "deleted");
      traitVersionRepository.deleteByVersionGroupId(entityType.getVersionGroupId());
      traitRepository.delete(entityType);
      log.info("Deleted Trait: {}", name);
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
    }
  }

  /**
   * Checks if a Trait with the given name exists in the repository.
   *
   * @param name the name of the Trait to check for existence
   * @return true if a Trait with the given name exists, false otherwise
   */
  @Transactional(propagation = Propagation.REQUIRED)
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
  @Transactional(propagation = Propagation.REQUIRED)
  public List<Trait> list() {
    log.info("Listing all Traits");
    var traits = traitRepository.findAll();
    log.info("Listed all Traits");
    return traits;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public List<TraitVersion> listAllVersions() {
    log.info("Listing all Trait versions");
    return traitVersionRepository.findAll();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public List<TraitRelationship> listAllRelationships() {
    log.info("Listing all trait relationships");
    return traitRelationshipRepository.findAll();
  }

  /**
   * Links two Traits with a given relation type.
   *
   * <p>This method validates that the two Traits exist, and that the link does not already exist.
   * It also checks for loops before creating the link. A self-referential link (source and target
   * are the same Trait) is permitted and is not treated as a loop.
   *
   * <p>Linking to an immutable trait is allowed: the flag freezes the trait row, and creating a
   * relationship does not write to it.
   *
   * <p>A {@code HAS_PART} / {@code IS_PART_OF} link is additionally subject to the aggregate-model
   * containment rules — see {@link ServiceUtils#checkAggregateTraitContainment}.
   *
   * @param sourceTraitName the name of the first Trait
   * @param relType the relation type to use for the link
   * @param targetTraitName the name of the second Trait
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void link(String sourceTraitName, RelationType relType, String targetTraitName) {
    doLink(sourceTraitName, relType, targetTraitName, false);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void linkImmutable(String sourceTraitName, RelationType relType, String targetTraitName) {
    doLink(sourceTraitName, relType, targetTraitName, true);
  }

  /** The shared body; see {@link #doCreate} on why it is private. */
  private void doLink(
      String sourceTraitName, RelationType relType, String targetTraitName, boolean immutable) {
    log.info("Linking Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
    try {
      if (wouldCreateLoop(sourceTraitName, targetTraitName, relType)) {
        throw new ServiceError("Loops are not allowed");
      }
      var sourceTrait =
          traitRepository
              .findByName(sourceTraitName)
              .orElseThrow(() -> new NotFoundException(TRAIT + sourceTraitName + NOT_FOUND));
      var targetTrait =
          traitRepository
              .findByName(targetTraitName)
              .orElseThrow(() -> new NotFoundException(TRAIT + targetTraitName + NOT_FOUND));
      // Containment inside the aggregate model is constrained by the built-in traits: an
      // Aggregate carrier may only have AggregateElement-carrying parts, and an element-only
      // carrier may have none. Normalized to (whole, part) so the IS_PART_OF entry point is
      // subject to the same rule as HAS_PART.
      if (relType == RelationType.HAS_PART || relType == RelationType.IS_PART_OF) {
        var whole = relType == RelationType.HAS_PART ? sourceTrait : targetTrait;
        var part = relType == RelationType.HAS_PART ? targetTrait : sourceTrait;
        ServiceUtils.checkAggregateTraitContainment(whole, part);
      }
      var directRel = new TraitRelationship();
      directRel.setSource(sourceTrait);
      directRel.setTarget(targetTrait);
      directRel.setRelationType(relType);
      directRel.setImmutable(immutable);
      traitRelationshipRepository.save(directRel);
      if (relType.hasInverse()) {
        var inverseRel = new TraitRelationship();
        inverseRel.setSource(targetTrait);
        inverseRel.setTarget(sourceTrait);
        inverseRel.setRelationType(relType.inverse());
        inverseRel.setImmutable(immutable);
        traitRelationshipRepository.save(inverseRel);
      }
      log.info("Linked Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
    }
  }

  /**
   * Unlinks two Traits with a given relation type.
   *
   * <p>This method validates that the two Traits exist and that the link exists.
   *
   * <p>A trait relationship that existing instance links rely on cannot be removed — see {@link
   * #checkNoInstanceLinkReliesOn}.
   *
   * @param sourceTraitName the name of the first Trait
   * @param relType the relation type to use for the link
   * @param targetTraitName the name of the second Trait
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void unlink(String sourceTraitName, RelationType relType, String targetTraitName) {
    log.info("Unlinking Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
    var sourceTrait =
        traitRepository
            .findByName(sourceTraitName)
            .orElseThrow(() -> new NotFoundException(TRAIT + sourceTraitName + NOT_FOUND));
    var targetTrait =
        traitRepository
            .findByName(targetTraitName)
            .orElseThrow(() -> new NotFoundException(TRAIT + targetTraitName + NOT_FOUND));
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
    // Both rows of the pair are resolved before anything is deleted: the guard below has to see the
    // whole of what this unlink removes in order to decide whether a link keeps a justification.
    TraitRelationship inverseRel = null;
    if (relType.hasInverse()) {
      inverseRel =
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
    }
    checkIsMutable(rel, inverseRel);
    checkNoInstanceLinkReliesOn(rel, inverseRel);
    traitRelationshipRepository.delete(rel);
    if (inverseRel != null) traitRelationshipRepository.delete(inverseRel);
    log.info("Unlinked Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
  }

  /**
   * Rejects the removal of an immutable trait relationship.
   *
   * <p>Both rows of the pair are checked rather than just the direct one. {@link #link} always
   * marks them alike, so a disagreement can only come from a hand-edited database — and in that
   * case refusing is the safe reading: deleting the pair would remove a row someone froze.
   *
   * @param rel the relationship being removed
   * @param inverseRel the paired inverse row, or {@code null} when the relation type has no inverse
   */
  private void checkIsMutable(TraitRelationship rel, TraitRelationship inverseRel) {
    TraitRelationship frozen = null;
    if (rel.isImmutable()) frozen = rel;
    else if (inverseRel != null && inverseRel.isImmutable()) frozen = inverseRel;
    if (frozen == null) return;
    throw new ServiceError(
        "The "
            + frozen.getRelationType()
            + " relationship between trait "
            + frozen.getSource().getName()
            + " and trait "
            + frozen.getTarget().getName()
            + " is immutable and cannot be removed.");
  }

  /**
   * Rejects an operation that would delete or rewrite an immutable trait.
   *
   * @param trait the live trait the operation targets
   * @param operation the past participle naming what was attempted, e.g. {@code "deleted"}
   */
  private void checkIsMutable(Trait trait, String operation) {
    if (!trait.isImmutable()) return;
    throw new ServiceError(
        TRAIT + trait.getName() + " is immutable and cannot be " + operation + ".");
  }

  /**
   * Rejects the removal of a trait relationship that an existing link between instances relies on.
   *
   * <p>Composition and dependency are declared between <em>traits</em>; a link between two entities
   * is admitted by {@link ServiceUtils#checkRelIsLegit} only because some trait relationship
   * sanctions it. Removing the last one that sanctions an existing link would leave that link in
   * the graph with nothing justifying it: it could no longer be re-created, and {@code
   * AggregateSchemaService} — which derives the aggregate model by projecting trait relationships
   * onto the types carrying them — would stop describing the composition the link is part of.
   * Remove the instance link first.
   *
   * <p>The check is exact rather than type-level. A type participates by mixing traits in, so
   * several trait relationships can sanction the same link; each candidate link is tested for
   * legitimacy both with and without the rows being removed, and only a link that loses its
   * <em>last</em> justification blocks the removal. A link that is already unsanctioned (nothing
   * justifies it even now) is not attributed to this unlink either.
   *
   * @param rel the relationship being removed
   * @param inverseRel the paired inverse row, or {@code null} when the relation type has no inverse
   */
  private void checkNoInstanceLinkReliesOn(TraitRelationship rel, TraitRelationship inverseRel) {
    var removedIds =
        inverseRel == null ? Set.of(rel.getId()) : Set.of(rel.getId(), inverseRel.getId());
    var removedRows = inverseRel == null ? List.of(rel) : List.of(rel, inverseRel);
    for (var row : removedRows) {
      var rowRelType = row.getRelationType();
      for (var link : entityRelationshipRepository.findByRelationType(rowRelType)) {
        var linkSourceType = link.getSource().getEntityType();
        var linkTargetType = link.getTarget().getEntityType();
        // Only a link whose two ends actually carry this row's traits can be relying on it.
        if (!ServiceUtils.implementsTrait(linkSourceType, row.getSource().getName())
            || !ServiceUtils.implementsTrait(linkTargetType, row.getTarget().getName())) continue;
        if (!ServiceUtils.relIsLegit(
            traitRelationshipRepository, linkSourceType, rowRelType, linkTargetType, Set.of()))
          continue;
        if (ServiceUtils.relIsLegit(
            traitRelationshipRepository, linkSourceType, rowRelType, linkTargetType, removedIds))
          continue;
        throw new ServiceError(
            "The "
                + rowRelType
                + " link between entity with id "
                + link.getSource().getId()
                + " and entity with id "
                + link.getTarget().getId()
                + " is sanctioned by the "
                + row.getRelationType()
                + " relationship between trait "
                + row.getSource().getName()
                + " and trait "
                + row.getTarget().getName()
                + " and by no other; removing it would leave that link unjustified. Remove the link"
                + " first.");
      }
    }
  }

  /**
   * Retrieves all Traits linked to the given trait with the specified relation type.
   *
   * @param traitName1 the name of the source Trait
   * @param relType the relation type to filter the links
   * @return a list of Traits that are targets of the specified relation type from the source Trait
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public List<Trait> linked(String traitName1, RelationType relType) {
    log.info("Linked Trait: {}", traitName1);
    var trait1 =
        traitRepository
            .findByName(traitName1)
            .orElseThrow(() -> new NotFoundException(TRAIT + traitName1 + NOT_FOUND));
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
  @Transactional(propagation = Propagation.REQUIRED)
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
   */
  private boolean wouldCreateLoop(
      String sourceTraitName, String targetTraitName, RelationType relType) {
    return ServiceUtils.hasPath(
        targetTraitName, sourceTraitName, new HashSet<>(), name -> neighbours(name, relType));
  }

  /** Returns the names of the traits reachable from {@code traitName} via a single relType edge. */
  private List<String> neighbours(String traitName, RelationType relType) {
    var trait =
        traitRepository
            .findByName(traitName)
            .orElseThrow(() -> new NotFoundException(TRAIT + traitName + NOT_FOUND));
    return traitRelationshipRepository.findBySourceAndRelationType(trait, relType).stream()
        .map(rel -> rel.getTarget().getName())
        .toList();
  }

  private JsonNode parseSchema(Optional<String> schema) throws SchemaValidationError {
    return TypeServiceSupport.parseSchema(jsonUtils, schema.orElse(EMPTY_SCHEMA));
  }

  private JsonNode computeDerivedSchema(Trait trait) throws SchemaValidationError {
    List<JsonNode> schemasToMerge = new ArrayList<>();
    if (trait.getFather() != null) {
      schemasToMerge.add(trait.getFather().getSchema());
    }
    schemasToMerge.add(trait.getBaseSchema());
    var mergedSchema = jsonUtils.mergeSchemas(schemasToMerge);
    if (mergedSchema.isLeft()) throw new SchemaValidationError(mergedSchema.getLeft());
    return mergedSchema.get();
  }
}
