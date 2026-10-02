package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.MAPPED_TO;

import com.fasterxml.jackson.core.JsonProcessingException;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.*;
import it.davidgreco.metacatalog.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link MappingService}.
 *
 * <p>Mapped-entity lifecycle management (create/update/delete cascades) lives in {@link
 * MappedEntityService}; entity-path traversal lives in {@link EntityPathResolver}.
 */
@Slf4j
@RequiredArgsConstructor
public class MappingServiceImpl implements MappingService {

  private static final String DOES_NOT_EXIST = " does not exist";

  private final EntityTypeRepository entityTypeRepository;

  private final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  private final JsonUtils jsonUtils;

  /**
   * Creates a MappingEntityTypeRelationship between the specified source and target entity types,
   * replacing the existing one in place if the pair already has a mapping.
   *
   * <p>This overload parses the entity path references from a JSON string, keeping the parsing in
   * the service layer instead of leaking it to callers.
   *
   * @param sourceEntityTypeName the name of the source entity type
   * @param targetEntityTypeName the name of the target entity type
   * @param mappingValues a JSON string representing the mapping values
   * @param entityPathReferencesJson a JSON string representing the entity path references, or
   *     {@code null}/{@code ""} for an empty list
   * @return the created MappingEntityTypeRelationship
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public MappingEntityTypeRelationship create(
      String sourceEntityTypeName,
      String targetEntityTypeName,
      String mappingValues,
      String entityPathReferencesJson) {
    List<MappingEntityTypeRelationship.EntityPathReference> entityPathReferences;
    if (entityPathReferencesJson == null || entityPathReferencesJson.isBlank()) {
      entityPathReferences = List.of();
    } else {
      try {
        entityPathReferences =
            jsonUtils
                .jsonMapper()
                .readValue(
                    entityPathReferencesJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new ServiceError("Invalid entity path references JSON: " + e.getMessage(), e);
      }
    }
    return create(sourceEntityTypeName, targetEntityTypeName, mappingValues, entityPathReferences);
  }

  /**
   * Creates a MappingEntityTypeRelationship between the specified source and target entity types,
   * replacing the existing one in place if the pair already has a mapping (see {@link
   * MappingService#create(String, String, String, List)} for the contract).
   *
   * @param sourceEntityTypeName the name of the source entity type
   * @param targetEntityTypeName the name of the target entity type
   * @param mappingValues a JSON string representing the mapping values
   * @param entityPathReferences a list of entity path references used in the mapping
   * @return the created or replaced MappingEntityTypeRelationship
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public MappingEntityTypeRelationship create(
      String sourceEntityTypeName,
      String targetEntityTypeName,
      String mappingValues,
      List<MappingEntityTypeRelationship.EntityPathReference> entityPathReferences) {
    log.info(
        "Creating MappingEntityTypeRelationship from {} to {}",
        sourceEntityTypeName,
        targetEntityTypeName);
    try {
      if (ServiceUtils.checkMappingLoops(
          entityTypeRepository,
          mappingEntityTypeRelationshipRepository,
          targetEntityTypeName,
          new HashSet<>(),
          sourceEntityTypeName)) throw new ServiceError("Loops are not allowed");

      var sourceEntityType =
          entityTypeRepository
              .findByName(sourceEntityTypeName)
              .orElseThrow(
                  () ->
                      new NotFoundException("EntityType " + sourceEntityTypeName + DOES_NOT_EXIST));

      var targetEntityType =
          entityTypeRepository
              .findByName(targetEntityTypeName)
              .orElseThrow(
                  () ->
                      new NotFoundException("EntityType " + targetEntityTypeName + DOES_NOT_EXIST));

      // Create-or-replace: a (source, target) pair holds at most one mapping. Re-creating the
      // pair updates the existing relationship in place — same id, so mapped entities already
      // derived through it stay attached and are re-evaluated with the new values on the next
      // source update. Two mappings of the same pair would otherwise each derive their own
      // mapped entity, duplicating instances of the target type.
      var existing =
          mappingEntityTypeRelationshipRepository
              .findMappingEntityTypeRelationshipBySourceAndTarget(
                  sourceEntityType, targetEntityType)
              .stream()
              .findFirst();
      var mapping = existing.orElseGet(MappingEntityTypeRelationship::new);

      mapping.setSource(sourceEntityType);
      mapping.setRelationType(MAPPED_TO);
      mapping.setTarget(targetEntityType);
      mapping.setSourceEntityTypeVersion(sourceEntityType.getVersion());
      mapping.setTargetEntityTypeVersion(targetEntityType.getVersion());
      var mappingValuesNode = jsonUtils.jsonMapper().readTree(mappingValues);
      var validatingSchemaEither =
          jsonUtils.convertToMappingSchema(jsonUtils.schemaOf(targetEntityType.getSchema()));
      if (validatingSchemaEither.isLeft())
        throw new SchemaValidationError(validatingSchemaEither.getLeft());
      var validatingSchema = validatingSchemaEither.get();
      SchemaValidationError.validateOrThrow(validatingSchema, mappingValuesNode);
      mapping.setMappingValues(mappingValuesNode);
      mapping.setEntityPathReferences(entityPathReferences);
      var saved = mappingEntityTypeRelationshipRepository.save(mapping);
      log.info(
          "{} MappingEntityTypeRelationship from {} to {}",
          existing.isPresent() ? "Replaced" : "Created",
          sourceEntityTypeName,
          targetEntityTypeName);
      return saved;
    } catch (JsonProcessingException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Deletes a mapping entity type relationship by its ID.
   *
   * @param mappingId the ID of the mapping entity type relationship to delete
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void delete(String mappingId) {
    log.info("Deleting MappingEntityTypeRelationship with id: {}", mappingId);
    mappingEntityTypeRelationshipRepository
        .findById(mappingId)
        .orElseThrow(() -> new NotFoundException("Mapping " + mappingId + DOES_NOT_EXIST));
    mappingEntityTypeRelationshipRepository.deleteById(mappingId);
    log.info("Deleted MappingEntityTypeRelationship with id: {}", mappingId);
  }

  /**
   * Reads a mapping entity type relationship by its ID.
   *
   * @param mappingId the ID of the mapping entity type relationship to read
   * @return the MappingEntityTypeRelationship with the given ID
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public MappingEntityTypeRelationship read(String mappingId) {
    log.info("Reading MappingEntityTypeRelationship with id: {}", mappingId);
    var mapping =
        mappingEntityTypeRelationshipRepository
            .findById(mappingId)
            .orElseThrow(() -> new NotFoundException("Mapping " + mappingId + DOES_NOT_EXIST));
    log.info("Read MappingEntityTypeRelationship with id: {}", mappingId);
    return mapping;
  }

  /**
   * Checks if a mapping entity type relationship with the given ID exists.
   *
   * @param mappingId the ID of the mapping entity type relationship to check
   * @return true if the mapping entity type relationship with the given ID exists, false otherwise
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public boolean exists(String mappingId) {
    log.info("Checking if MappingEntityTypeRelationship with id: {}", mappingId);
    var exists = mappingEntityTypeRelationshipRepository.existsById(mappingId);
    log.info("Checked if MappingEntityTypeRelationship with id: {}", mappingId);
    return exists;
  }

  /**
   * Retrieves all mapping entity type relationships from the repository.
   *
   * @return a list of all mapping entity type relationships
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public List<MappingEntityTypeRelationship> list() {
    log.info("Listing all MappingEntityTypeRelationships");
    var mappings = mappingEntityTypeRelationshipRepository.findAll();
    log.info("Listed all MappingEntityTypeRelationships");
    return mappings;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public List<MappingEntityRelationship> listAllEntityRelationships() {
    log.info("Listing all MappingEntityRelationships");
    return mappingEntityRelationshipRepository.findAll();
  }

  /**
   * Finds all entities that map <em>to</em> the given target entity, returning the source entity
   * and the path references that describe how to traverse from source to target.
   *
   * @param target the entity that other entities map to
   * @return a list of mapping sources, each carrying the source entity and its path references
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public List<MappingSource> findMappingSources(Entity target) {
    return mappingEntityRelationshipRepository
        .findByTargetAndRelationType(target, MAPPED_TO)
        .stream()
        .map(
            mer ->
                new MappingSource(
                    mer.getSource(),
                    mer.getMappingEntityTypeRelationship().getEntityPathReferences()))
        .toList();
  }

  /**
   * Checks if the given entity type is a source entity type in any mapping relationship.
   *
   * @param entityType the entity type to check
   * @return true if the entity type is a source in at least one mapping, false otherwise
   */
  @Override
  public boolean isSourceEntityType(EntityType entityType) {
    return !mappingEntityTypeRelationshipRepository
        .findMappingEntityTypeRelationshipBySource(entityType)
        .isEmpty();
  }

  /**
   * Checks if the given entity type is a target entity type in any mapping relationship.
   *
   * @param entityType the entity type to check
   * @return true if the entity type is a target in at least one mapping, false otherwise
   */
  @Override
  public boolean isTargetEntityType(EntityType entityType) {
    return !mappingEntityTypeRelationshipRepository
        .findMappingEntityTypeRelationshipByTarget(entityType)
        .isEmpty();
  }
}
