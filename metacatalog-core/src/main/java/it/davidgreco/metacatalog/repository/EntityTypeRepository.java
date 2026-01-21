package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.EntityType;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
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
   * Finds an entity type by its unique name.
   *
   * @param name the name of the entity type
   * @return an Optional containing the entity type if found
   */
  @Cacheable(
      cacheNames = {"EntityTypes"},
      key = "#name")
  Optional<EntityType> findByName(String name);

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
}
