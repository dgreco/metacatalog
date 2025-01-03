package it.davidgreco.metacatalog.entity;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CacheConcurrencyStrategy;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
        name = "entity_type",
        indexes = {@Index(name = "idx_entity_type_name_unq", columnList = "name", unique = true)})
@Cacheable
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
@SuppressFBWarnings
public class EntityType implements Type<EntityType> {
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
    private EntityType father;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "type_traits",
            joinColumns = @JoinColumn(name = "entity_type_id"),
            inverseJoinColumns = @JoinColumn(name = "trait_id"))
    List<Trait> traits = new ArrayList<>();

    @OneToMany(mappedBy = "entityType", fetch = FetchType.LAZY)
    private List<Entity> entities = new ArrayList<>();

    @OneToMany(mappedBy = "father", fetch = FetchType.LAZY)
    private List<EntityType> children = new ArrayList<>();

    public JsonNode getSchema() {
        if (this.derivedSchema == null) {
            return this.baseSchema;
        } else {
            return this.derivedSchema;
        }
    }
}
