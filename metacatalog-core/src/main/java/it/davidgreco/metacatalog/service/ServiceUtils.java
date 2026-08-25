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
    return traitNamesOf(entityType).contains(traitName);
  }

  /**
   * Checks if a trait carries another trait: the trait itself or any ancestor of its father chain.
   *
   * @param trait the trait to check
   * @param traitName the name of the trait to look for
   * @return true if the trait is or inherits from the named trait, false otherwise
   */
  public static boolean carriesTrait(Trait trait, String traitName) {
    return loadInheritanceChain(trait).stream().map(Trait::getName).anyMatch(traitName::equals);
  }

  /**
   * Enforces the aggregate-model containment rules on a trait-level {@code HAS_PART}: a trait
   * carrying {@code Aggregate} may only declare parts carrying {@code AggregateElement}, and a
   * trait carrying only {@code AggregateElement} may not declare parts at all. A trait carrying
   * neither stays free — containment outside the aggregate model (an Iceberg namespace containing
   * tables) is not the model's business.
   *
   * <p>A single trait can never carry both built-ins (single inheritance, both father-less), so
   * "carries both" only exists at the type level; the entity-level twin below handles it.
   *
   * @param whole the containing side of the relationship
   * @param part the contained side of the relationship
   */
  public static void checkAggregateTraitContainment(Trait whole, Trait part) {
    if (carriesTrait(whole, BuiltInTraits.AGGREGATE)) {
      if (!carriesTrait(part, BuiltInTraits.AGGREGATE_ELEMENT))
        throw new ServiceError(
            "Trait "
                + whole.getName()
                + " carries Aggregate; a HAS_PART part must carry AggregateElement — trait "
                + part.getName()
                + " does not.");
    } else if (carriesTrait(whole, BuiltInTraits.AGGREGATE_ELEMENT)) {
      throw new ServiceError(
          "Trait "
              + whole.getName()
              + " carries only AggregateElement; an aggregate element cannot have parts.");
    }
  }

  /**
   * The entity-level twin of {@link #checkAggregateTraitContainment}: the same rules, tested
   * against the carriage of the two entities' <em>types</em>. Necessary even with the trait-level
   * check in place, because a link's sanction is existential — a relationship between two neutral
   * traits can sanction a link whose types carry the built-ins through other mixins.
   *
   * <p>A type carrying both built-ins is an intermediate node and is handled by the first branch:
   * its Aggregate role is what grants it parts, and those parts must be elements.
   *
   * @param whole the containing entity
   * @param part the contained entity
   */
  public static void checkAggregateEntityContainment(Entity whole, Entity part) {
    var wholeType = whole.getEntityType();
    if (implementsTrait(wholeType, BuiltInTraits.AGGREGATE)) {
      if (!implementsTrait(part.getEntityType(), BuiltInTraits.AGGREGATE_ELEMENT))
        throw new ServiceError(
            ENTITY_WITH_ID
                + whole.getId()
                + " is an aggregate (type "
                + wholeType.getName()
                + " carries Aggregate); a HAS_PART part must carry AggregateElement — type "
                + part.getEntityType().getName()
                + " of entity with id "
                + part.getId()
                + " does not.");
    } else if (implementsTrait(wholeType, BuiltInTraits.AGGREGATE_ELEMENT)) {
      throw new ServiceError(
          ENTITY_WITH_ID
              + whole.getId()
              + " (type "
              + wholeType.getName()
              + ") carries only AggregateElement; an aggregate element cannot have parts.");
    }
  }

  /**
   * Every trait an entity type carries: the traits associated with each type of its inheritance
   * chain, plus each of those traits' own ancestors.
   *
   * @param entityType the entity type to collect the traits of
   * @return the traits, with duplicates preserved (a trait reachable twice appears twice)
   */
  private static List<Trait> allTraitsOf(EntityType entityType) {
    return loadInheritanceChain(entityType).stream()
        .flatMap(
            et -> et.getTraits().stream().flatMap(trait -> loadInheritanceChain(trait).stream()))
        .toList();
  }

  /**
   * The names of {@link #allTraitsOf(EntityType)}.
   *
   * <p>Public because {@link #implementsTrait} rebuilds this set on every call: a caller asking
   * several trait questions about the same type — which capabilities it offers, whether it is an
   * aggregate root — should walk the father chains once and test the set it gets back.
   *
   * @param entityType the entity type to collect the trait names of
   * @return every trait name the type carries, directly or through either inheritance chain
   */
  public static Set<String> traitNamesOf(EntityType entityType) {
    return allTraitsOf(entityType).stream().map(Trait::getName).collect(Collectors.toSet());
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

    return relIsLegit(
        traitRelationshipRepository,
        sourceEntity.getEntityType(),
        relType,
        targetEntity.getEntityType(),
        Set.of());
  }

  /**
   * Whether a {@code relType} link between instances of the two given types is sanctioned by a
   * trait relationship, ignoring the trait relationships whose ids are in {@code
   * excludedTraitRelationshipIds}.
   *
   * <p>The exclusion set answers the question the plain check cannot: whether an existing link
   * would <em>still</em> be sanctioned if a particular trait relationship were removed. Since a
   * type participates by mixing traits in, several trait relationships can sanction the same link —
   * so a link only loses its justification when the one being removed is its last.
   *
   * @param traitRelationshipRepository the trait relationship repository
   * @param sourceEntityType the type on the source side of the link
   * @param relType the type of relationship
   * @param targetEntityType the type on the target side of the link
   * @param excludedTraitRelationshipIds ids of trait relationships to treat as absent
   * @return true if the link is sanctioned by a trait relationship outside the exclusion set
   */
  public static boolean relIsLegit(
      TraitRelationshipRepository traitRelationshipRepository,
      EntityType sourceEntityType,
      RelationType relType,
      EntityType targetEntityType,
      Set<String> excludedTraitRelationshipIds) {
    var targetTraitNames = traitNamesOf(targetEntityType);
    return allTraitsOf(sourceEntityType).stream()
        .flatMap(
            sourceTrait ->
                traitRelationshipRepository
                    .findBySourceAndRelationType(sourceTrait, relType)
                    .stream())
        .filter(rel -> !excludedTraitRelationshipIds.contains(rel.getId()))
        .anyMatch(rel -> targetTraitNames.contains(rel.getTarget().getName()));
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
    /**
     * Returns the neighbours of the given node.
     *
     * @param node the node whose neighbours to compute
     * @return the neighbouring nodes
     * @throws ServiceError if the neighbours cannot be computed
     */
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
    return implementsTrait(entity.getEntityType(), traitName);
  }
}
