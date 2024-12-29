package it.witboost.dataplatformshaper.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

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
                    name = "idx_mapping_entity_relationship_source_id_relation_type_target_id",
                    columnList = "source_id, relation_type, target_id")
        })
@SuppressFBWarnings
public class MappingEntityRelationship extends CommonMappingRelationship<Entity> {}
