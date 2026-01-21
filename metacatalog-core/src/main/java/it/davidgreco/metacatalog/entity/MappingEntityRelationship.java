package it.davidgreco.metacatalog.entity;

import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Represents a mapping relationship between two entities.
 *
 * <p>Mapping entity relationships are created when entities are automatically generated through the
 * mapping system. They link a source entity to a target entity that was created based on a mapping
 * rule defined at the type level.
 *
 * @see MappingEntityTypeRelationship
 * @see CommonRelationship
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "mapping_entity_relationship",
    indexes = {
      @Index(
          name = "idx_mapping_entity_relationship_source_id_relation_type",
          columnList = "source_id, relation_type"),
      @Index(
          name = "idx_mapping_entity_relationship_source_id_mapping_entity_type_relationship_id",
          columnList = "source_id, mapping_entity_type_relationship_id",
          unique = true)
    })
public class MappingEntityRelationship extends CommonRelationship<Entity> {

  /** The type-level mapping rule that this entity mapping is based on. */
  @OneToOne(fetch = FetchType.EAGER)
  private MappingEntityTypeRelationship mappingEntityTypeRelationship;
}
