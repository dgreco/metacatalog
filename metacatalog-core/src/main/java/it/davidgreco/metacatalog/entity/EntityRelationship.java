package it.davidgreco.metacatalog.entity;

import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Represents a relationship between two entities.
 *
 * <p>Entity relationships define how entities are connected to each other in the metacatalog. Each
 * relationship has a source entity, a target entity, and a relationship type that describes the
 * nature of the connection.
 *
 * @see CommonRelationship
 * @see RelationType
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "entity_relationship",
    indexes = {
      @Index(
          name = "idx_entity_relationship_source_id_relation_type",
          columnList = "source_id, relation_type"),
      @Index(
          name = "idx_entity_relationship_target_id_relation_type",
          columnList = "target_id, relation_type"),
      @Index(
          name = "idx_entity_relationship_source_id_relation_type_target_id",
          columnList = "source_id, relation_type, target_id",
          unique = true)
    })
public class EntityRelationship extends CommonRelationship<Entity> {}
