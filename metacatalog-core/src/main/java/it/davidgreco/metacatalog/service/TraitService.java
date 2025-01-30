package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.mergeSchemas;
import static it.davidgreco.metacatalog.common.JsonUtils.stringToJsonSchema;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitRelationship;
import it.davidgreco.metacatalog.repository.TraitRelationshipRepository;
import it.davidgreco.metacatalog.repository.TraitRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Service class for managing {@link Trait} entities. */
@Slf4j
@Service
@Getter
@Setter
@RequiredArgsConstructor
@EnableCaching
public class TraitService implements CommonTypeService<Trait, String> {

  private static final String TRAIT = "Trait ";
  private static final String NOT_FOUND = " not found";

  private final TraitRepository traitRepository;

  private final TraitRelationshipRepository traitRelationshipRepository;

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
      var eitherSchema =
          stringToJsonSchema(
              schema.orElse(
                  """
                    {
                      "type": "object",
                      "properties": {
                      }
                    }
                    """));
      if (eitherSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
      var entityType = new Trait();
      entityType.setName(name);
      entityType.setBaseSchema(eitherSchema.get().getSchemaNode());
      List<JsonNode> schemasToMerge = new java.util.ArrayList<>();
      if (fatherName.isPresent()) {
        var father =
            traitRepository
                .findByName(fatherName.get())
                .orElseThrow(() -> new ServiceError(TRAIT + fatherName.get() + " does not exist"));
        entityType.setFather(father);
        schemasToMerge.addAll(List.of(father.getSchema(), eitherSchema.get().getSchemaNode()));
        var mergedSchema = mergeSchemas(schemasToMerge);
        if (mergedSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
        entityType.setDerivedSchema(mergedSchema.get());
      } else {
        schemasToMerge.add(eitherSchema.get().getSchemaNode());
        var mergedSchema = mergeSchemas(schemasToMerge);
        if (mergedSchema.isLeft()) throw new SchemaValidationError(mergedSchema.getLeft());
        entityType.setDerivedSchema(mergedSchema.get());
      }
      return traitRepository.save(entityType);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    } finally {
      log.info("Created Trait: {}", name);
    }
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
    try {
      return traitRepository
          .findByName(name)
          .orElseThrow(() -> new ServiceError(TRAIT + name + NOT_FOUND));
    } finally {
      log.info("Read Trait: {}", name);
    }
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
      traitRepository.delete(entityType);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    } finally {
      log.info("Deleted Trait: {}", name);
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
    try {
      return traitRepository.existsByName(name);
    } finally {
      log.info("Checked if Trait exists: {}", name);
    }
  }

  /**
   * Links two Traits with a given relation type.
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
  public void link(String sourceTraitName, RelationType relType, String targetTraitName)
      throws ServiceError {
    log.info("Linking Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
    try {
      if (checkLoops(sourceTraitName, new HashSet<>(), targetTraitName, relType)) {
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
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    } finally {
      log.info("Linked Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
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
    try {
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
    } finally {
      log.info("Unlinked Trait: {} with Trait: {}", sourceTraitName, targetTraitName);
    }
  }

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public List<Trait> linked(String traitName1, RelationType relType) throws ServiceError {
    log.info("Linked Trait: {}", traitName1);
    try {
      var trait1 =
          traitRepository
              .findByName(traitName1)
              .orElseThrow(() -> new ServiceError(TRAIT + traitName1 + NOT_FOUND));
      return traitRelationshipRepository.findBySourceAndRelationType(trait1, relType).stream()
          .map(TraitRelationship::getTarget)
          .toList();
    } finally {
      log.info("Linked Trait: {}", traitName1);
    }
  }

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public long countTraitChildren(String name) {
    log.info("Counting children of Trait: {}", name);
    try {
      return traitRepository.findByName(name).map(traitRepository::countTraitByFather).orElse(0L);
    } finally {
      log.info("Counting children of Trait: {}", name);
    }
  }

  private boolean checkLoops(
      String sourceTraitName,
      Set<String> traitsNamesVisited,
      String targetTraitName,
      RelationType relType)
      throws ServiceError {
    var sourceTrait =
        traitRepository
            .findByName(sourceTraitName)
            .orElseThrow(() -> new ServiceError(TRAIT + sourceTraitName + NOT_FOUND));
    var relationships =
        traitRelationshipRepository.findBySourceAndRelationType(sourceTrait, relType);

    var targetTraits = relationships.stream().map(TraitRelationship::getTarget).toList();
    for (var targetTrait : targetTraits) {
      if (traitsNamesVisited.contains(targetTraitName)) return true;
      else {
        traitsNamesVisited.add(targetTrait.getName());
        return checkLoops(targetTrait.getName(), traitsNamesVisited, targetTraitName, relType);
      }
    }
    return traitsNamesVisited.contains(targetTraitName);
  }
}
