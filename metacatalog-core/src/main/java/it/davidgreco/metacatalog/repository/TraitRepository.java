package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Trait;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

@Repository
public interface TraitRepository extends JpaRepository<Trait, String> {

  @Cacheable(
      cacheNames = {"Traits"},
      key = "#name")
  Optional<Trait> findByName(String name);

  @NonNull
  @CachePut(
      cacheNames = {"Traits"},
      key = "#trait.name")
  <S extends Trait> S save(@NonNull S trait);

  @Override
  @CacheEvict(
      cacheNames = {"Traits"},
      key = "#trait.name")
  void delete(@NonNull Trait trait);

  @CacheEvict(
      cacheNames = {"Traits"},
      key = "#name")
  boolean existsByName(@NonNull String name);

  long countTraitByFather(@NonNull Trait trait);
}
