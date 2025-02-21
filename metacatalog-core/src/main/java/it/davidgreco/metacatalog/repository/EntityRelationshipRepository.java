package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EntityRelationshipRepository extends JpaRepository<EntityRelationship, String> {

  List<EntityRelationship> findBySourceAndRelationType(Entity source, RelationType relType);

  List<EntityRelationship> findByTargetAndRelationType(Entity target, RelationType relType);

  Optional<EntityRelationship> findBySourceAndRelationTypeAndTarget(
      Entity source, RelationType relType, Entity target);
}
