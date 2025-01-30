package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.MappingEntityRelationship;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingEntityRelationshipRepository
    extends JpaRepository<MappingEntityRelationship, String> {

  List<MappingEntityRelationship> findByTargetAndRelationType(
      Entity target, RelationType relationType);

  List<MappingEntityRelationship> findBySourceAndMappingEntityTypeRelationship(
      Entity source, MappingEntityTypeRelationship mappingEntityTypeRelationship);

  Optional<MappingEntityRelationship> findBySourceAndRelationTypeAndTarget(
      Entity source, RelationType relationType, Entity target);

  List<MappingEntityRelationship> findBySourceAndRelationType(
      Entity source, RelationType relationType);
}
