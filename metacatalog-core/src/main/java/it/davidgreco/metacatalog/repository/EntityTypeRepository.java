package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.EntityType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for {@link EntityType} persistence operations.
 *
 * <p>Provides CRUD operations for entity types with caching support to improve performance for
 * frequently accessed type definitions.
 */
@Repository
public interface EntityTypeRepository extends JpaRepository<EntityType, String> {

  /**
   * Finds an entity type by its unique name, fetching its (now lazy) traits so callers that read
   * them after the transaction closes do not lazy-load them.
   *
   * @param name the name of the entity type
   * @return an Optional containing the entity type if found
   */
  @Cacheable(
      cacheNames = {"EntityTypes"},
      key = "#name")
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
   * Saves an entity type and updates the cache.
   *
   * @param entityType the entity type to save
   * @param <S> the entity type subclass
   * @return the saved entity type
   */
  @CachePut(
      cacheNames = {"EntityTypes"},
      key = "#entityType.name")
  <S extends EntityType> S save(S entityType);

  /**
   * Deletes an entity type and evicts it from the cache.
   *
   * @param entityType the entity type to delete
   */
  @Override
  @CacheEvict(
      cacheNames = {"EntityTypes"},
      key = "#entityType.name")
  void delete(EntityType entityType);

  /**
   * Checks if an entity type with the given name exists.
   *
   * @param name the name to check
   * @return true if an entity type with the name exists
   */
  @CacheEvict(
      cacheNames = {"EntityTypes"},
      key = "#name")
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
   * createVersion} calls for the same type are serialised. Not cached.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT e FROM EntityType e WHERE e.name = :name")
  Optional<EntityType> findByNameForUpdate(@Param("name") String name);
}
