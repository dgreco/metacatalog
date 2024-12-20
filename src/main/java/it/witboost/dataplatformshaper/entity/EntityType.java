package it.witboost.dataplatformshaper.entity;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
@SuppressFBWarnings
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
    @Column(name = "base_schema", columnDefinition = "jsonb", nullable = false)
    @ToString.Include
    private JsonNode baseSchema;

    @Type(JsonBinaryType.class)
    @Column(name = "derived_schema", columnDefinition = "jsonb", nullable = true)
    @ToString.Include
    private JsonNode derivedSchema;

    @OneToOne(fetch = FetchType.EAGER)
    private EntityType father;

    @OneToMany(mappedBy = "entityType", fetch = FetchType.LAZY)
    private List<TypedEntity> entities = new ArrayList<>();

    public Optional<EntityType> getFather() {
        return Optional.ofNullable(father);
    }

    public JsonNode getSchema() {
        if (this.derivedSchema == null) {
            return this.baseSchema;
        } else {
            return this.derivedSchema;
        }
    }
}
