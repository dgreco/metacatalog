package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.Entity;
import it.witboost.dataplatformshaper.entity.EntityRelationship;
import it.witboost.dataplatformshaper.entity.RelationType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EntityRelationshipRepository extends JpaRepository<EntityRelationship, Long> {

    List<EntityRelationship> findBySourceAndRelationType(Entity source, RelationType relType);

    List<EntityRelationship> findByTargetAndRelationType(Entity target, RelationType relType);

    Optional<EntityRelationship> findBySourceAndRelationTypeAndTarget(
            Entity source, RelationType relType, Entity target);
}
