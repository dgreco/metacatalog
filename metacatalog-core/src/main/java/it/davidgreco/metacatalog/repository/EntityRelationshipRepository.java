package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for {@link EntityRelationship} persistence operations.
 *
 * <p>Provides methods for querying entity relationships by source, target, and relationship type.
 */
@Repository
public interface EntityRelationshipRepository extends JpaRepository<EntityRelationship, String> {

  /**
   * Finds all relationships from a source entity with the specified relationship type.
   *
   * @param source the source entity
   * @param relType the relationship type
   * @return list of matching relationships
   */
  List<EntityRelationship> findBySourceAndRelationType(Entity source, RelationType relType);

  /**
   * Finds all relationships to a target entity with the specified relationship type.
   *
   * @param target the target entity
   * @param relType the relationship type
   * @return list of matching relationships
   */
  List<EntityRelationship> findByTargetAndRelationType(Entity target, RelationType relType);

  /**
   * Finds a specific relationship between two entities with the given type.
   *
   * @param source the source entity
   * @param relType the relationship type
   * @param target the target entity
   * @return an Optional containing the relationship if found
   */
  Optional<EntityRelationship> findBySourceAndRelationTypeAndTarget(
      Entity source, RelationType relType, Entity target);

  /**
   * Finds every relationship of the given type, regardless of which entities it connects.
   *
   * @param relType the relationship type
   * @return list of matching relationships
   */
  List<EntityRelationship> findByRelationType(RelationType relType);

  /**
   * Finds all relationships in which the given entity is the source, regardless of relationship
   * type.
   *
   * @param source the source entity
   * @return list of matching relationships
   */
  List<EntityRelationship> findBySource(Entity source);

  /**
   * Finds all relationships in which the given entity is the target, regardless of relationship
   * type.
   *
   * @param target the target entity
   * @return list of matching relationships
   */
  List<EntityRelationship> findByTarget(Entity target);

  /**
   * Finds every relationship between live entities, leaving out the links inside frozen copies of
   * past aggregate versions. A copy never links to a live entity, so checking the source suffices
   * for a well-formed copy; the target is checked too so that nothing half-frozen leaks.
   *
   * @return the relationships between live entities
   */
  @Query(
      "SELECT r FROM EntityRelationship r"
          + " WHERE r.source.frozenVersionOf IS NULL AND r.target.frozenVersionOf IS NULL")
  List<EntityRelationship> findAllBetweenLiveEntities();

  /**
   * Deletes every relationship either end of which is one of the given entities.
   *
   * @param entities the entities whose links to delete
   */
  @Modifying
  @Query("DELETE FROM EntityRelationship r WHERE r.source IN :entities OR r.target IN :entities")
  void deleteTouching(@Param("entities") Collection<Entity> entities);
}
