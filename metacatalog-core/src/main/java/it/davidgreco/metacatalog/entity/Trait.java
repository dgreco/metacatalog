package it.davidgreco.metacatalog.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
        name = "trait",
        indexes = {@Index(name = "idx_trait_name_unq", columnList = "name", unique = true)})
public class Trait implements Type<Trait> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private String id;

    @Column(name = "name", nullable = false)
    @ToString.Include
    private String name;

    @org.hibernate.annotations.Type(JsonBinaryType.class)
    @Column(name = "base_schema", columnDefinition = "jsonb", nullable = false)
    @ToString.Include
    private JsonNode baseSchema;

    @org.hibernate.annotations.Type(JsonBinaryType.class)
    @Column(name = "derived_schema", columnDefinition = "jsonb", nullable = true)
    @ToString.Include
    private JsonNode derivedSchema;

    @OneToOne(fetch = FetchType.EAGER)
    private Trait father;

    @ManyToMany(mappedBy = "traits", fetch = FetchType.LAZY)
    private List<EntityType> types = new ArrayList<>();

    public JsonNode getSchema() {
        if (this.derivedSchema == null) {
            return this.baseSchema;
        } else {
            return this.derivedSchema;
        }
    }
}
