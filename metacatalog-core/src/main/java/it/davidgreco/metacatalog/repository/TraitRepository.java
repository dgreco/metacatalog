package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Trait;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for {@link Trait} persistence operations.
 *
 * <p>Provides CRUD operations for traits with caching support to improve performance for frequently
 * accessed trait definitions.
 */
@Repository
public interface TraitRepository extends JpaRepository<Trait, String> {

  /**
   * Finds a trait by its unique name.
   *
   * @param name the name of the trait
   * @return an Optional containing the trait if found
   */
  @Cacheable(
      cacheNames = {"Traits"},
      key = "#name")
  Optional<Trait> findByName(String name);

  /**
   * Saves a trait and updates the cache.
   *
   * @param trait the trait to save
   * @param <S> the trait subclass
   * @return the saved trait
   */
  @CachePut(
      cacheNames = {"Traits"},
      key = "#trait.name")
  <S extends Trait> S save(S trait);

  /**
   * Deletes a trait and evicts it from the cache.
   *
   * @param trait the trait to delete
   */
  @Override
  @CacheEvict(
      cacheNames = {"Traits"},
      key = "#trait.name")
  void delete(Trait trait);

  /**
   * Checks if a trait with the given name exists.
   *
   * @param name the name to check
   * @return true if a trait with the name exists
   */
  @CacheEvict(
      cacheNames = {"Traits"},
      key = "#name")
  boolean existsByName(String name);

  /**
   * Counts the number of traits that inherit from the given parent trait.
   *
   * @param trait the parent trait
   * @return the number of child traits
   */
  long countTraitByFather(Trait trait);

  /**
   * Finds a trait by name acquiring a pessimistic write lock, so concurrent {@code createVersion}
   * calls for the same trait are serialised. Not cached.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM Trait t WHERE t.name = :name")
  Optional<Trait> findByNameForUpdate(@Param("name") String name);
}
