package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.Entity;
import it.witboost.dataplatformshaper.entity.EntityType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EntityRepository extends JpaRepository<Entity, String> {
    long countByEntityType(EntityType entityType);

    List<Entity> findByEntityType(EntityType entityType);
}
