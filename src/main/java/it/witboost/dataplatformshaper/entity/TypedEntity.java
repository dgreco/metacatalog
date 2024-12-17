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

    @OneToOne(fetch = FetchType.EAGER)
    @ToString.Exclude
    private EntityType entityType;
}
