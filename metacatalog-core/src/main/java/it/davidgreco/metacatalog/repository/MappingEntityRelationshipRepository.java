package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.MappingEntityRelationship;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingEntityRelationshipRepository extends JpaRepository<MappingEntityRelationship, String> {

    List<MappingEntityRelationship> findByTargetAndRelationType(Entity target, RelationType relType);

    List<MappingEntityRelationship> findBySourceAndMappingEntityTypeRelationship(
            Entity source, MappingEntityTypeRelationship mappingEntityTypeRelationship);

    List<MappingEntityRelationship> findBySource(Entity source);
}
