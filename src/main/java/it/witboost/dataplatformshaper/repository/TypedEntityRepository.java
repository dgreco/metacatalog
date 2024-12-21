package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.entity.TypedEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TypedEntityRepository extends JpaRepository<TypedEntity, String> {
    @Override
    Optional<TypedEntity> findById(String id);

    long countTypedEntityByEntityType(EntityType entityType);
}
