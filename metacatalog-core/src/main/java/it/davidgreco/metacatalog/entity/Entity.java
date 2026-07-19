package it.davidgreco.metacatalog.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Type;

/**
 * Represents a business entity instance in the metacatalog.
 *
 * <p>An entity is a concrete instance of an {@link EntityType}, containing attribute values stored
 * as JSON. Entities can be related to other entities through {@link EntityRelationship}s.
 */
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "entity",
    indexes = {@Index(name = "idx_entity_entity_type_id_unq", columnList = "entity_type_id")})
public class Entity {

  /** The unique identifier for this entity, generated as a UUID. */
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  @ToString.Include
  @EqualsAndHashCode.Include
  private String id;

  /** The attribute values of this entity, stored as a JSON object. */
  @Type(JsonBinaryType.class)
  @Column(name = "values", columnDefinition = "jsonb", nullable = false)
  @ToString.Include
  private JsonNode values;

  /** The type of this entity, which defines its schema and available traits. */
  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "entity_type_id", nullable = false)
  private EntityType entityType;

  /**
   * The frozen {@link EntityTypeVersion} snapshot this entity was created against.
   *
   * <p>When set, schema validation of {@link #values} uses this snapshot's {@code getSchema()}
   * instead of the live {@link EntityType#getSchema()}, so the entity stays pinned to the schema it
   * was validated against at creation time even after {@code createVersion} mutates the live type.
   *
   * <p>Nullable for legacy rows created before this column existed; the service layer treats null
   * as "follow the live type".
   */
  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "entity_type_version_id")
  private EntityTypeVersion entityTypeVersion;

  /**
   * Creates a new entity with the specified type and values.
   *
   * @param entityType the type of the entity
   * @param values the attribute values as a JSON node
   */
  public Entity(EntityType entityType, JsonNode values) {
    this.values = values;
    this.entityType = entityType;
  }

  /** Default constructor required by JPA. */
  public Entity() {}
}
