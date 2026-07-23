package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
