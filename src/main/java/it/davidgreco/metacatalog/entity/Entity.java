package it.davidgreco.metacatalog.entity;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Type;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
        name = "entity",
        indexes = {@Index(name = "idx_entity_entity_type_id_unq", columnList = "entity_type_id")})
@SuppressFBWarnings
public class Entity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private String id;

    @Type(JsonBinaryType.class)
    @Column(name = "values", columnDefinition = "jsonb", nullable = false)
    @ToString.Include
    private JsonNode values;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "entity_type_id", nullable = false)
    private EntityType entityType;
}
