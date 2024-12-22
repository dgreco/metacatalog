package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.entity.TypedEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TypedEntityRepository extends JpaRepository<TypedEntity, String> {
    long countTypedEntityByEntityType(EntityType entityType);
}
