package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.service.CommonTypeService.loadInheritanceChain;

import it.davidgreco.metacatalog.entity.*;
import it.davidgreco.metacatalog.repository.*;
import java.util.ArrayDeque;
import java.util.List;
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
        loadInheritanceChain(entityType).stream()
            .flatMap(
                et ->
                    et.getTraits().stream().flatMap(trait -> loadInheritanceChain(trait).stream()))
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
            .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + sourceEntityId + NOT_FOUND));
    var targetEntity =
        entityRepository
            .findById(targetEntityId)
            .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + targetEntityId + NOT_FOUND));
    var sourceEntityType = sourceEntity.getEntityType();
    var targetEntityType = targetEntity.getEntityType();

    var allTheTraitsForTheSourceType =
        loadInheritanceChain(sourceEntityType).stream()
            .flatMap(
                entityType ->
                    entityType.getTraits().stream()
                        .flatMap(trait -> loadInheritanceChain(trait).stream()))
            .toList();

    var allTheTraitsNamesForTheTargetType =
        loadInheritanceChain(targetEntityType).stream()
            .flatMap(
                entityType ->
                    entityType.getTraits().stream()
                        .flatMap(trait -> loadInheritanceChain(trait).stream()))
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
   *     <p>Performs an iterative depth-first search seeded with the neighbours of {@code
   *     sourceEntityId} so that every outgoing edge of every visited entity is explored (a visited
   *     set prevents re-traversal and guards against runaway recursion). If {@code targetEntityId}
   *     is reachable, adding the edge would close a cycle.
   */
  static boolean checkLoops(
      EntityRepository entityRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      String sourceEntityId,
      Set<String> entityIdsVisited,
      String targetEntityId,
      RelationType relationType)
      throws ServiceError {
    var toVisit =
        new ArrayDeque<>(
            entityNeighbours(
                entityRepository, entityRelationshipRepository, sourceEntityId, relationType));
    while (!toVisit.isEmpty()) {
      var current = toVisit.pop();
      if (current.equals(targetEntityId)) {
        return true;
      }
      if (!entityIdsVisited.add(current)) {
        continue;
      }
      toVisit.addAll(
          entityNeighbours(entityRepository, entityRelationshipRepository, current, relationType));
    }
    return false;
  }

  /** Returns the ids of the entities reachable from {@code entityId} via a single relType edge. */
  private static List<String> entityNeighbours(
      EntityRepository entityRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      String entityId,
      RelationType relationType)
      throws ServiceError {
    var entity =
        entityRepository
            .findById(entityId)
            .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + entityId + NOT_FOUND));
    return entityRelationshipRepository.findBySourceAndRelationType(entity, relationType).stream()
        .map(EntityRelationship::getTarget)
        .map(Entity::getId)
        .toList();
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
   *     <p>Performs an iterative depth-first search seeded with the neighbours of {@code
   *     sourceEntityTypeName} so that every outgoing mapping edge of every visited entity type is
   *     explored (a visited set prevents re-traversal and guards against runaway recursion). If
   *     {@code targetEntityTypeName} is reachable, adding the mapping would close a cycle.
   */
  static boolean checkMappingLoops(
      EntityTypeRepository entityTypeRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      String sourceEntityTypeName,
      Set<String> entityTypeNamesVisited,
      String targetEntityTypeName)
      throws ServiceError {
    var toVisit =
        new ArrayDeque<>(
            mappingNeighbours(
                entityTypeRepository,
                mappingEntityTypeRelationshipRepository,
                sourceEntityTypeName));
    while (!toVisit.isEmpty()) {
      var current = toVisit.pop();
      if (current.equals(targetEntityTypeName)) {
        return true;
      }
      if (!entityTypeNamesVisited.add(current)) {
        continue;
      }
      toVisit.addAll(
          mappingNeighbours(
              entityTypeRepository, mappingEntityTypeRelationshipRepository, current));
    }
    return false;
  }

  /**
   * Returns the names of the entity types reachable from {@code entityTypeName} via a single
   * mapping edge.
   */
  private static List<String> mappingNeighbours(
      EntityTypeRepository entityTypeRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      String entityTypeName)
      throws ServiceError {
    var entityType =
        entityTypeRepository
            .findByName(entityTypeName)
            .orElseThrow(() -> new NotFoundException("Entity type " + entityTypeName + NOT_FOUND));
    return mappingEntityTypeRelationshipRepository
        .findMappingEntityTypeRelationshipBySource(entityType)
        .stream()
        .map(MappingEntityTypeRelationship::getTarget)
        .map(EntityType::getName)
        .toList();
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
        .flatMap(trait -> loadInheritanceChain(trait).stream())
        .map(Trait::getName)
        .collect(Collectors.toSet())
        .contains(traitName);
  }
}
