package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.Entity;
import it.witboost.dataplatformshaper.entity.MappingEntityRelationship;
import it.witboost.dataplatformshaper.entity.RelationType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingEntityRelationshipRepository extends JpaRepository<MappingEntityRelationship, String> {
    List<MappingEntityRelationship> findBySourceAndRelationType(Entity source, RelationType relType);

    List<MappingEntityRelationship> findByTargetAndRelationType(Entity target, RelationType relType);

    Optional<MappingEntityRelationship> findBySourceAndRelationTypeAndTarget(
            Entity source, RelationType relType, Entity target);
}
