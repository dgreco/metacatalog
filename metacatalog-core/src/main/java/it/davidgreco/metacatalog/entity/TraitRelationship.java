package it.davidgreco.metacatalog.entity;

import static it.davidgreco.metacatalog.entity.RelationType.*;

import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "trait_relationship",
    indexes = {
      @Index(
          name = "idx_trait_relationship_source_id_relation_type",
          columnList = "source_id, relation_type"),
      @Index(
          name = "idx_trait_relationship_source_id_relation_type_target_id",
          columnList = "source_id, relation_type, target_id",
          unique = true),
    })
public class TraitRelationship extends CommonRelationship<Trait> {
  public static final Map<RelationType, RelationType> INVERSE_RELATION_TYPE =
      Map.of(
          DEPENDS_ON, IS_REQUIRED_BY,
          IS_REQUIRED_BY, DEPENDS_ON,
          MAPPED_TO, IS_MAPPED_BY,
          IS_MAPPED_BY, MAPPED_TO,
          HAS_PART, IS_PART_OF,
          IS_PART_OF, HAS_PART);
}
