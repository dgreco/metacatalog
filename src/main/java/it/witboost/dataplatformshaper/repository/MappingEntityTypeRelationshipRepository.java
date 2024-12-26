package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.entity.MappingEntityTypeRelationship;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingEntityTypeRelationshipRepository extends JpaRepository<MappingEntityTypeRelationship, String> {
    List<MappingEntityTypeRelationship> findMappingEntityTypeRelationshipBySource(EntityType source);
}
