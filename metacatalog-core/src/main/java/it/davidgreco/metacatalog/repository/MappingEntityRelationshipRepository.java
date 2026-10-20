package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.MappingEntityRelationship;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository interface for {@link MappingEntityRelationship} persistence operations.
 *
 * <p>Provides methods for querying mapping relationships between entities, including lookups by
 * source, target, and the associated type-level mapping rule.
 */
public interface MappingEntityRelationshipRepository
    extends JpaRepository<MappingEntityRelationship, String> {

  /**
   * {@inheritDoc} Fetches the (now lazy) type-level mapping rule in the same query, so the graph
   * layer that walks every instance mapping does not lazy-load it per row.
   */
  @Override
  @EntityGraph(
      attributePaths = {"mappingEntityTypeRelationship"},
      type = EntityGraph.EntityGraphType.LOAD)
  List<MappingEntityRelationship> findAll();

  /**
   * Finds all mapping relationships to a target entity with the specified relationship type.
   *
   * @param target the target entity
   * @param relationType the relationship type
   * @return list of matching mapping relationships
   */
  List<MappingEntityRelationship> findByTargetAndRelationType(
      Entity target, RelationType relationType);

  /**
   * Finds all mapping relationships from a source entity for a specific type-level mapping rule.
   *
   * @param source the source entity
   * @param mappingEntityTypeRelationship the type-level mapping rule
   * @return list of matching mapping relationships
   */
  List<MappingEntityRelationship> findBySourceAndMappingEntityTypeRelationship(
      Entity source, MappingEntityTypeRelationship mappingEntityTypeRelationship);

  /**
   * Finds a specific mapping relationship between two entities with the given type.
   *
   * @param source the source entity
   * @param relationType the relationship type
   * @param target the target entity
   * @return an Optional containing the mapping relationship if found
   */
  Optional<MappingEntityRelationship> findBySourceAndRelationTypeAndTarget(
      Entity source, RelationType relationType, Entity target);

  /**
   * Finds all mapping relationships from a source entity with the specified relationship type.
   *
   * @param source the source entity
   * @param relationType the relationship type
   * @return list of matching mapping relationships
   */
  List<MappingEntityRelationship> findBySourceAndRelationType(
      Entity source, RelationType relationType);

  /**
   * Finds every mapping instance between live entities, leaving out those inside frozen copies of
   * past aggregate versions.
   *
   * @return the mapping instances between live entities
   */
  @Query(
      "SELECT r FROM MappingEntityRelationship r LEFT JOIN FETCH r.mappingEntityTypeRelationship"
          + " WHERE r.source.frozenVersionOf IS NULL AND r.target.frozenVersionOf IS NULL")
  List<MappingEntityRelationship> findAllBetweenLiveEntities();

  /**
   * Finds one mapping instance of the given rule inside a frozen copy, if any: the rule is then
   * still needed to describe that past version, and cannot be deleted.
   *
   * @param mappingId the id of the mapping rule
   * @return a mapping instance of that rule whose source is frozen, if one exists
   */
  Optional<MappingEntityRelationship>
      findFirstByMappingEntityTypeRelationshipIdAndSourceFrozenVersionOfIsNotNull(String mappingId);

  /**
   * Deletes every mapping instance either end of which is one of the given entities.
   *
   * @param entities the entities whose mapping instances to delete
   */
  @Modifying
  @Query(
      "DELETE FROM MappingEntityRelationship r WHERE r.source IN :entities OR r.target IN"
          + " :entities")
  void deleteTouching(@Param("entities") Collection<Entity> entities);
}
