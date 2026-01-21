package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.MappingEntityRelationship;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository interface for {@link MappingEntityRelationship} persistence operations.
 *
 * <p>Provides methods for querying mapping relationships between entities, including lookups by
 * source, target, and the associated type-level mapping rule.
 */
public interface MappingEntityRelationshipRepository
    extends JpaRepository<MappingEntityRelationship, String> {

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
}
