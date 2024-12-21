package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EntityTypeRepository extends JpaRepository<EntityType, String> {

    Optional<EntityType> findByName(String name);

    @Override
    void delete(EntityType entityType);

    boolean existsByName(String name);
}
