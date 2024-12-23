package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.Entity;
import it.witboost.dataplatformshaper.entity.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TypedEntityRepository extends JpaRepository<Entity, String> {
    long countTypedEntityByEntityType(EntityType entityType);
}
