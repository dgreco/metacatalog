package it.witboost.dataplatformshaper.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
        name = "entity_relationship",
        indexes = {
            @Index(name = "idx_entity_relationship_source_id_relation_type", columnList = "source_id, relation_type"),
            @Index(
                    name = "idx_entity_relationship_source_id_relation_type_target_id",
                    columnList = "source_id, relation_type, target_id",
                    unique = true),
        })
@SuppressFBWarnings
public class EntityRelationship extends CommonRelationship<Entity> {}
