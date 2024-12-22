package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.Trait;
import it.witboost.dataplatformshaper.entity.TraitRelationship;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TraitRelationshipRepository extends JpaRepository<TraitRelationship, Long> {
    List<TraitRelationship> findBySource(Trait source);

    List<TraitRelationship> findByTarget(Trait target);

    List<TraitRelationship> findByRelationType(String relationType);

    @Query("SELECT r FROM TraitRelationship r WHERE " + "(r.source = :entity OR r.target = :entity) AND "
            + "r.relationType = :type")
    List<TraitRelationship> findRelationships(@Param("entity") Trait entity, @Param("type") String type);
}
