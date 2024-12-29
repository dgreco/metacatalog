package it.witboost.dataplatformshaper.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToOne;
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
                    name = "idx_mapping_entity_relationship_source_id_mapping_entity_type_relationship_id",
                    columnList = "source_id, mapping_entity_type_relationship_id",
                    unique = true)
        })
@SuppressFBWarnings
public class MappingEntityRelationship extends CommonRelationship<Entity> {
    @OneToOne(fetch = FetchType.EAGER)
    private MappingEntityTypeRelationship mappingEntityTypeRelationship;
}
