package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.RelationType;
import it.witboost.dataplatformshaper.entity.Trait;
import it.witboost.dataplatformshaper.entity.TraitRelationship;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TraitRelationshipRepository extends JpaRepository<TraitRelationship, Long> {

    List<TraitRelationship> findBySourceAndRelationType(Trait source, RelationType relType);

    Optional<TraitRelationship> findBySourceAndRelationTypeAndTarget(Trait source, RelationType relType, Trait target);
}
