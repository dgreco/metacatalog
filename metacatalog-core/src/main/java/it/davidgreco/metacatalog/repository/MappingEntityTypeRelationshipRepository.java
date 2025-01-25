package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingEntityTypeRelationshipRepository
    extends JpaRepository<MappingEntityTypeRelationship, String> {
  List<MappingEntityTypeRelationship> findMappingEntityTypeRelationshipBySource(EntityType source);

  List<MappingEntityTypeRelationship> findMappingEntityTypeRelationshipByTarget(EntityType target);
}
