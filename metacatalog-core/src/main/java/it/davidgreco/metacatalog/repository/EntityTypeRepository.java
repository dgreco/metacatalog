package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.EntityType;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EntityTypeRepository extends JpaRepository<EntityType, String> {

  @Cacheable(
      cacheNames = {"EntityTypes"},
      key = "#name")
  Optional<EntityType> findByName(String name);

  @CachePut(
      cacheNames = {"EntityTypes"},
      key = "#entityType.name")
  <S extends EntityType> S save(S entityType);

  @Override
  @CacheEvict(
      cacheNames = {"EntityTypes"},
      key = "#entityType.name")
  void delete(EntityType entityType);

  @CacheEvict(
      cacheNames = {"EntityTypes"},
      key = "#name")
  boolean existsByName(String name);

  long countEntityTypeByFather(EntityType entityType);
}
