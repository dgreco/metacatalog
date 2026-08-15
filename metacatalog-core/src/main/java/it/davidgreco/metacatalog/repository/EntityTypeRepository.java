package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.EntityType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository interface for {@link EntityType} persistence operations. */
@Repository
public interface EntityTypeRepository extends JpaRepository<EntityType, String> {

  /**
   * Finds an entity type by its unique name, fetching its (now lazy) traits so callers that read
   * them after the transaction closes do not lazy-load them.
   *
   * @param name the name of the entity type
   * @return an Optional containing the entity type if found
   */
  @EntityGraph(
      attributePaths = {"traits", "father"},
      type = EntityGraph.EntityGraphType.LOAD)
  Optional<EntityType> findByName(String name);

  /** {@inheritDoc} Fetches the (now lazy) traits collection in the same query. */
  @Override
  @EntityGraph(
      attributePaths = {"traits", "father"},
      type = EntityGraph.EntityGraphType.LOAD)
  List<EntityType> findAll();

  /**
   * Checks if an entity type with the given name exists.
   *
   * @param name the name to check
   * @return true if an entity type with the name exists
   */
  boolean existsByName(String name);

  /**
   * Counts the number of entity types that inherit from the given parent type.
   *
   * @param entityType the parent entity type
   * @return the number of child entity types
   */
  long countEntityTypeByFather(EntityType entityType);

  /**
   * Finds an entity type by name acquiring a pessimistic write lock, so concurrent {@code
   * createVersion} calls for the same type are serialised.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT e FROM EntityType e WHERE e.name = :name")
  Optional<EntityType> findByNameForUpdate(@Param("name") String name);
}
