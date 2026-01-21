package it.davidgreco.metacatalog.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Represents a type definition for entities in the metacatalog.
 *
 * <p>An entity type defines the schema (structure) that entities of this type must conform to. It
 * supports inheritance through a parent type (father) and can be associated with multiple traits.
 * The effective schema is either the derived schema (if present) or the base schema.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "entity_type",
    indexes = {@Index(name = "idx_entity_type_name_unq", columnList = "name", unique = true)})
public class EntityType implements Type<EntityType> {

  /** The unique identifier for this entity type, generated as a UUID. */
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  @ToString.Include
  private String id;

  /** The unique name of this entity type. */
  @Column(name = "name", nullable = false)
  @ToString.Include
  private String name;

  /** The base JSON schema that defines the structure for entities of this type. */
  @org.hibernate.annotations.Type(JsonBinaryType.class)
  @Column(name = "base_schema", columnDefinition = "jsonb", nullable = false)
  @ToString.Include
  private JsonNode baseSchema;

  /** The derived JSON schema, computed from base schema and inherited traits. */
  @org.hibernate.annotations.Type(JsonBinaryType.class)
  @Column(name = "derived_schema", columnDefinition = "jsonb", nullable = true)
  @ToString.Include
  private JsonNode derivedSchema;

  /** The parent entity type from which this type inherits. */
  @OneToOne(fetch = FetchType.EAGER)
  private EntityType father;

  /** The traits associated with this entity type. */
  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "type_traits",
      joinColumns = @JoinColumn(name = "entity_type_id"),
      inverseJoinColumns = @JoinColumn(name = "trait_id"))
  List<Trait> traits = new ArrayList<>();

  /** The entities that are instances of this type. */
  @OneToMany(mappedBy = "entityType", fetch = FetchType.LAZY)
  private List<Entity> entities = new ArrayList<>();

  /** The entity types that inherit from this type. */
  @OneToMany(mappedBy = "father", fetch = FetchType.LAZY)
  private List<EntityType> children = new ArrayList<>();

  /**
   * Returns the effective schema for this entity type.
   *
   * @return the derived schema if present, otherwise the base schema
   */
  public JsonNode getSchema() {
    if (this.derivedSchema == null) {
      return this.baseSchema;
    } else {
      return this.derivedSchema;
    }
  }
}
