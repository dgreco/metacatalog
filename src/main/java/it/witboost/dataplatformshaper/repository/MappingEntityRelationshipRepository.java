package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.Entity;
import it.witboost.dataplatformshaper.entity.MappingEntityRelationship;
import it.witboost.dataplatformshaper.entity.MappingEntityTypeRelationship;
import it.witboost.dataplatformshaper.entity.RelationType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingEntityRelationshipRepository extends JpaRepository<MappingEntityRelationship, String> {

    List<MappingEntityRelationship> findByTargetAndRelationType(Entity target, RelationType relType);

    List<MappingEntityRelationship> findBySourceAndMappingEntityTypeRelationship(
            Entity source, MappingEntityTypeRelationship mappingEntityTypeRelationship);
}
