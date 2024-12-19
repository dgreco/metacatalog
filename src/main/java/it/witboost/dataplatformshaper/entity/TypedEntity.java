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
        name = "entity",
        indexes = {
            @Index(name = "idx_entity_id_unq", columnList = "id", unique = true),
            @Index(name = "idx_entity_entity_type_id_unq", columnList = "entity_type_id")
        })
public class TypedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private String id;

    @Type(JsonBinaryType.class)
    @Column(name = "values", columnDefinition = "jsonb", nullable = false)
    @ToString.Include
    private JsonNode values;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_type_id", nullable = false)
    private EntityType entityType;
}
