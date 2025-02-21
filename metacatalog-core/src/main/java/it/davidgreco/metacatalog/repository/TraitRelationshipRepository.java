package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitRelationship;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TraitRelationshipRepository extends JpaRepository<TraitRelationship, String> {

  List<TraitRelationship> findBySourceAndRelationType(Trait source, RelationType relType);

  Optional<TraitRelationship> findBySourceAndRelationTypeAndTarget(
      Trait source, RelationType relType, Trait target);
}
