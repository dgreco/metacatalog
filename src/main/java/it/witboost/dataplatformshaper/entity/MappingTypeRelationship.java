package it.witboost.dataplatformshaper.entity;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.hypersistence.utils.hibernate.type.array.ListArrayType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Struct;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
        name = "mapping_type_relationship",
        indexes = {
            @Index(
                    name = "idx_mapping_type_relationship_source_id_relation_type",
                    columnList = "source_id, relation_type"),
            @Index(
                    name = "idx_mapping_type_relationship_source_id_relation_type_target_id",
                    columnList = "source_id, relation_type, target_id",
                    unique = true),
        })
@SuppressFBWarnings
public class MappingTypeRelationship extends CommonRelationship<EntityType> {
    @org.hibernate.annotations.Type(JsonBinaryType.class)
    @Column(name = "mapping_values", columnDefinition = "jsonb", nullable = false)
    @ToString.Include
    private JsonNode mappingValues;

    @org.hibernate.annotations.Type(
            value = ListArrayType.class,
            parameters = {@Parameter(name = ListArrayType.SQL_ARRAY_TYPE, value = "source_reference")})
    @Column(name = "source_references", columnDefinition = "_source_reference", nullable = false)
    @ToString.Include
    private List<SourceReference> sourceReferences;

    @Getter
    @Setter
    @Struct(
            name = "source_reference",
            attributes = {"alias", "reference_path"})
    public static class SourceReference {

        @Column
        private String alias;

        @Column
        private String referencePath;

        public SourceReference() {}

        SourceReference(String alias, String referencePath) {
            this.alias = alias;
            this.referencePath = referencePath;
        }
    }
}
