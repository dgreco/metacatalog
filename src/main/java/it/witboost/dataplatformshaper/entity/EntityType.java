package it.witboost.dataplatformshaper.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Type;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Entity
@Table(
        name = "entity_type",
        indexes = {
            @Index(name = "idx_entity_type_id_unq", columnList = "id", unique = true),
            @Index(name = "idx_entity_type_name_unq", columnList = "name", unique = true),
        })
public class EntityType {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private String id;

    @Column(name = "name", nullable = false)
    @ToString.Include
    private String name;

    @Type(JsonBinaryType.class)
    @Column(name = "schema", columnDefinition = "jsonb")
    @ToString.Include
    private JsonNode schema;
}
