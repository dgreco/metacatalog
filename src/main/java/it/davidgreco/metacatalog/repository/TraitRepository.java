package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Trait;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TraitRepository extends JpaRepository<Trait, String> {

    @Cacheable(
            cacheNames = {"Traits"},
            key = "#name")
    Optional<Trait> findByName(String name);

    @CachePut(
            cacheNames = {"Traits"},
            key = "#trait.name")
    Trait save(Trait trait);

    @Override
    @CacheEvict(
            cacheNames = {"Traits"},
            key = "#trait.name")
    void delete(Trait trait);

    @CacheEvict(
            cacheNames = {"Traits"},
            key = "#name")
    boolean existsByName(String name);

    long countTraitByFather(Trait trait);
}
