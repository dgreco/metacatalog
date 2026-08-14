package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitRelationship;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for {@link TraitRelationship} persistence operations.
 *
 * <p>Provides methods for querying trait relationships by source trait and relationship type.
 */
@Repository
public interface TraitRelationshipRepository extends JpaRepository<TraitRelationship, String> {

  /**
   * Finds all relationships from a source trait with the specified relationship type.
   *
   * @param source the source trait
   * @param relType the relationship type
   * @return list of matching relationships
   */
  List<TraitRelationship> findBySourceAndRelationType(Trait source, RelationType relType);

  /**
   * Finds all relationships with the specified relationship type.
   *
   * @param relType the relationship type
   * @return list of matching relationships
   */
  List<TraitRelationship> findByRelationType(RelationType relType);

  /**
   * Finds a specific relationship between two traits with the given type.
   *
   * @param source the source trait
   * @param relType the relationship type
   * @param target the target trait
   * @return an Optional containing the relationship if found
   */
  Optional<TraitRelationship> findBySourceAndRelationTypeAndTarget(
      Trait source, RelationType relType, Trait target);
}
