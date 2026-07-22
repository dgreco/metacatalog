package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.service.CommonTypeService.loadInheritanceChain;

import it.davidgreco.metacatalog.entity.*;
import it.davidgreco.metacatalog.repository.*;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stateless utility methods used across the service layer.
 *
 * <p>These methods were originally static members of {@link CommonService}, which forced every
 * service implementation to inherit them whether or not they were relevant (ISP violation). They
 * have been extracted here so {@link CommonService} models only the real contract ({@code read},
 * {@code delete}, {@code exists}).
 */
public final class ServiceUtils {

  public static final String NOT_FOUND = " not found";

  public static final String ENTITY_WITH_ID = "Entity with id ";

  private ServiceUtils() {}

  /**
   * Checks if an entity type implements a trait (directly or through inheritance).
   *
   * @param entityType the entity type to check
   * @param traitName the name of the trait to look for
   * @return true if the entity type implements the trait, false otherwise
   */
  public static boolean implementsTrait(EntityType entityType, String traitName) {
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
   */
  public static boolean checkRelIsLegit(
      EntityRepository entityRepository,
      TraitRelationshipRepository traitRelationshipRepository,
      String sourceEntityId,
      RelationType relType,
      String targetEntityId) {

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
  public static boolean isMappingSourceEntityType(
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
  public static boolean isMappingTargetEntityType(
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      EntityType entityType) {
    return !mappingEntityTypeRelationshipRepository
        .findMappingEntityTypeRelationshipByTarget(entityType)
        .isEmpty();
  }

  /** Functional interface for computing the neighbours of a node, allowing checked exceptions. */
  @FunctionalInterface
  public interface Neighbours {
    List<String> apply(String node) throws ServiceError;
  }

  /**
   * Generic iterative depth-first search that checks whether {@code target} is reachable from
   * {@code start} via one or more edges supplied by {@code neighbours}.
   *
   * <p>The search is seeded with the neighbours of {@code start} (not {@code start} itself) so a
   * self-referential edge (start equals target) is not treated as a loop — only paths of length one
   * or more count. A visited set prevents re-traversal.
   *
   * @param start the node to search from
   * @param target the node to search for
   * @param visited set of already visited node IDs (populated during the search)
   * @param neighbours function returning the neighbours of a given node
   * @return true if {@code target} is reachable, false otherwise
   */
  public static boolean hasPath(
      String start, String target, Set<String> visited, Neighbours neighbours) {
    var toVisit = new ArrayDeque<>(neighbours.apply(start));
    while (!toVisit.isEmpty()) {
      var current = toVisit.pop();
      if (current.equals(target)) {
        return true;
      }
      if (!visited.add(current)) {
        continue;
      }
      toVisit.addAll(neighbours.apply(current));
    }
    return false;
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
   */
  public static boolean checkLoops(
      EntityRepository entityRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      String sourceEntityId,
      Set<String> entityIdsVisited,
      String targetEntityId,
      RelationType relationType) {
    return hasPath(
        sourceEntityId,
        targetEntityId,
        entityIdsVisited,
        id -> entityNeighbours(entityRepository, entityRelationshipRepository, id, relationType));
  }

  /** Returns the ids of the entities reachable from {@code entityId} via a single relType edge. */
  private static List<String> entityNeighbours(
      EntityRepository entityRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      String entityId,
      RelationType relationType) {
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
   */
  public static boolean checkMappingLoops(
      EntityTypeRepository entityTypeRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      String sourceEntityTypeName,
      Set<String> entityTypeNamesVisited,
      String targetEntityTypeName) {
    return hasPath(
        sourceEntityTypeName,
        targetEntityTypeName,
        entityTypeNamesVisited,
        name ->
            mappingNeighbours(entityTypeRepository, mappingEntityTypeRelationshipRepository, name));
  }

  /**
   * Returns the names of the entity types reachable from {@code entityTypeName} via a single
   * mapping edge.
   */
  private static List<String> mappingNeighbours(
      EntityTypeRepository entityTypeRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      String entityTypeName) {
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
  public static boolean hasTrait(Entity entity, String traitName) {
    return loadInheritanceChain(entity.getEntityType()).stream()
        .flatMap(
            et -> et.getTraits().stream().flatMap(trait -> loadInheritanceChain(trait).stream()))
        .map(Trait::getName)
        .collect(Collectors.toSet())
        .contains(traitName);
  }
}
