package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.service.CommonTypeService.genericTraitService;
import static it.davidgreco.metacatalog.service.CommonTypeService.genericTypeService;

import it.davidgreco.metacatalog.entity.*;
import it.davidgreco.metacatalog.repository.*;
import java.util.Set;
import java.util.stream.Collectors;

/** Common service interface for all services. */
public interface CommonService<T, K> {

  String NOT_FOUND = " not found";

  String ENTITY_WITH_ID = "Entity with id ";

  /**
   * Reads an entity by its key.
   *
   * @param key the key identifying the entity
   * @return the entity
   * @throws ServiceError if the entity is not found
   */
  T read(K key) throws ServiceError;

  /**
   * Deletes an entity by its key.
   *
   * @param key the key identifying the entity to delete
   * @throws ServiceError if the entity is not found or cannot be deleted
   */
  void delete(K key) throws ServiceError;

  /**
   * Checks if an entity with the given key exists.
   *
   * @param key the key to check
   * @return true if an entity with the key exists, false otherwise
   * @throws ServiceError if an error occurs during the check
   */
  boolean exists(K key) throws ServiceError;

  /**
   * Checks if an entity type implements a trait (directly or through inheritance).
   *
   * @param entityType the entity type to check
   * @param traitName the name of the trait to look for
   * @return true if the entity type implements the trait, false otherwise
   */
  static boolean implementsTrait(EntityType entityType, String traitName) {
    var allTheTraitsForTheType =
        genericTypeService.loadInheritanceChain(entityType).stream()
            .flatMap(
                et ->
                    et.getTraits().stream()
                        .flatMap(trait -> genericTraitService.loadInheritanceChain(trait).stream()))
            .map(Trait::getName)
            .collect(Collectors.toSet());
    return allTheTraitsForTheType.contains(traitName);
  }

  /**
   * Checks if a relationship between two entities is legitimate based on trait relationships.
   *
   * @param entityRepository the entity repository
   * @param traitRelationshipRepository the trait relationship repository
   * @param sourceEntityId the ID of the source entity
   * @param relType the type of relationship
   * @param targetEntityId the ID of the target entity
   * @return true if the relationship is allowed by trait definitions, false otherwise
   * @throws ServiceError if either entity is not found
   */
  static boolean checkRelIsLegit(
      EntityRepository entityRepository,
      TraitRelationshipRepository traitRelationshipRepository,
      String sourceEntityId,
      RelationType relType,
      String targetEntityId)
      throws ServiceError {

    var sourceEntity =
        entityRepository
            .findById(sourceEntityId)
            .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + sourceEntityId + NOT_FOUND));
    var targetEntity =
        entityRepository
            .findById(targetEntityId)
            .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + targetEntityId + NOT_FOUND));
    var sourceEntityType = sourceEntity.getEntityType();
    var targetEntityType = targetEntity.getEntityType();

    var allTheTraitsForTheSourceType =
        genericTypeService.loadInheritanceChain(sourceEntityType).stream()
            .flatMap(
                entityType ->
                    entityType.getTraits().stream()
                        .flatMap(trait -> genericTraitService.loadInheritanceChain(trait).stream()))
            .toList();

    var allTheTraitsNamesForTheTargetType =
        genericTypeService.loadInheritanceChain(targetEntityType).stream()
            .flatMap(
                entityType ->
                    entityType.getTraits().stream()
                        .flatMap(trait -> genericTraitService.loadInheritanceChain(trait).stream()))
            .map(Trait::getName)
            .collect(Collectors.toSet());

    var sourceRelationships =
        allTheTraitsForTheSourceType.stream()
            .flatMap(
                sourceTrait ->
                    traitRelationshipRepository
                        .findBySourceAndRelationType(sourceTrait, relType)
                        .stream())
            .toList();

    for (var sourceRelationship : sourceRelationships) {
      var targetTrait = sourceRelationship.getTarget();
      if (allTheTraitsNamesForTheTargetType.contains(targetTrait.getName())) return true;
    }
    return false;
  }

  /**
   * Checks if an entity type is a source in any mapping relationship.
   *
   * @param mappingEntityTypeRelationshipRepository the mapping relationship repository
   * @param entityType the entity type to check
   * @return true if the entity type is a mapping source, false otherwise
   */
  static boolean isMappingSourceEntityType(
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      EntityType entityType) {
    return !mappingEntityTypeRelationshipRepository
        .findMappingEntityTypeRelationshipBySource(entityType)
        .isEmpty();
  }

  /**
   * Checks if an entity type is a target in any mapping relationship.
   *
   * @param mappingEntityTypeRelationshipRepository the mapping relationship repository
   * @param entityType the entity type to check
   * @return true if the entity type is a mapping target, false otherwise
   */
  static boolean isMappingTargetEntityType(
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      EntityType entityType) {
    return !mappingEntityTypeRelationshipRepository
        .findMappingEntityTypeRelationshipByTarget(entityType)
        .isEmpty();
  }

  /**
   * Checks for loops in entity relationships to prevent circular dependencies.
   *
   * @param entityRepository the entity repository
   * @param entityRelationshipRepository the entity relationship repository
   * @param sourceEntityId the ID of the source entity
   * @param entityIdsVisited set of already visited entity IDs
   * @param targetEntityId the ID of the target entity being checked
   * @param relationType the type of relationship
   * @return true if a loop would be created, false otherwise
   * @throws ServiceError if an entity is not found
   */
  static boolean checkLoops(
      EntityRepository entityRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      String sourceEntityId,
      Set<String> entityIdsVisited,
      String targetEntityId,
      RelationType relationType)
      throws ServiceError {
    var sourceEntity =
        entityRepository
            .findById(sourceEntityId)
            .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + sourceEntityId + NOT_FOUND));
    var mappings =
        entityRelationshipRepository.findBySourceAndRelationType(sourceEntity, relationType);

    var targetIds =
        mappings.stream().map(EntityRelationship::getTarget).map(Entity::getId).toList();
    for (var targetId : targetIds) {
      if (entityIdsVisited.contains(targetEntityId)) return true;
      else {
        entityIdsVisited.add(targetId);
        return checkLoops(
            entityRepository,
            entityRelationshipRepository,
            targetId,
            entityIdsVisited,
            targetEntityId,
            relationType);
      }
    }
    return entityIdsVisited.contains(targetEntityId);
  }

  /**
   * Checks for loops in mapping relationships between entity types.
   *
   * @param entityTypeRepository the entity type repository
   * @param mappingEntityTypeRelationshipRepository the mapping relationship repository
   * @param sourceEntityTypeName the name of the source entity type
   * @param entityTypeNamesVisited set of already visited entity type names
   * @param targetEntityTypeName the name of the target entity type being checked
   * @return true if a loop would be created, false otherwise
   * @throws ServiceError if an entity type is not found
   */
  static boolean checkMappingLoops(
      EntityTypeRepository entityTypeRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      String sourceEntityTypeName,
      Set<String> entityTypeNamesVisited,
      String targetEntityTypeName)
      throws ServiceError {
    var sourceEntityType =
        entityTypeRepository
            .findByName(sourceEntityTypeName)
            .orElseThrow(() -> new ServiceError("Entity type " + targetEntityTypeName + NOT_FOUND));
    var mappings =
        mappingEntityTypeRelationshipRepository.findMappingEntityTypeRelationshipBySource(
            sourceEntityType);

    var targetTypes = mappings.stream().map(MappingEntityTypeRelationship::getTarget).toList();
    for (var targetType : targetTypes) {
      if (entityTypeNamesVisited.contains(targetEntityTypeName)) return true;
      else {
        entityTypeNamesVisited.add(targetType.getName());
        return checkMappingLoops(
            entityTypeRepository,
            mappingEntityTypeRelationshipRepository,
            targetType.getName(),
            entityTypeNamesVisited,
            targetEntityTypeName);
      }
    }
    return entityTypeNamesVisited.contains(targetEntityTypeName);
  }

  /**
   * Checks if an entity has a specific trait (directly or through inheritance).
   *
   * @param entity the entity to check
   * @param traitName the name of the trait to look for
   * @return true if the entity has the trait, false otherwise
   */
  static boolean hasTrait(Entity entity, String traitName) {
    return entity.getEntityType().getTraits().stream()
        .flatMap(trait -> genericTraitService.loadInheritanceChain(trait).stream())
        .map(Trait::getName)
        .collect(Collectors.toSet())
        .contains(traitName);
  }
}
