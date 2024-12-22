package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.Trait;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TraitRepository extends JpaRepository<Trait, String> {

    Optional<Trait> findByName(String name);

    boolean existsByName(String name);

    long countTraitByFather(Trait trait);
}
