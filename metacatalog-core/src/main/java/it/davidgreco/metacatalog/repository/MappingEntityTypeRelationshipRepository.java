package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository interface for {@link MappingEntityTypeRelationship} persistence operations.
 *
 * <p>Provides methods for querying type-level mapping rules that define how entities of one type
 * should be transformed into entities of another type.
 */
public interface MappingEntityTypeRelationshipRepository
    extends JpaRepository<MappingEntityTypeRelationship, String> {

  /**
   * Finds all mapping rules where the given entity type is the source.
   *
   * @param source the source entity type
   * @return list of mapping rules from this source type
   */
  List<MappingEntityTypeRelationship> findMappingEntityTypeRelationshipBySource(EntityType source);

  /**
   * Finds all mapping rules where the given entity type is the target.
   *
   * @param target the target entity type
   * @return list of mapping rules to this target type
   */
  List<MappingEntityTypeRelationship> findMappingEntityTypeRelationshipByTarget(EntityType target);

  /**
   * Finds the mapping rules between the given source and target entity types. A pair has at most
   * one mapping under the create-or-replace contract of {@code MappingService.create}; the list
   * form tolerates rows predating that contract.
   *
   * @param source the source entity type
   * @param target the target entity type
   * @return the mapping rules between the two types
   */
  List<MappingEntityTypeRelationship> findMappingEntityTypeRelationshipBySourceAndTarget(
      EntityType source, EntityType target);
}
