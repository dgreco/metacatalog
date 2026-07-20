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
 * Represents a reusable characteristic that can be applied to entity types.
 *
 * <p>Traits define additional schema properties that can be shared across multiple entity types.
 * They support inheritance through a parent trait (father) and contribute to the derived schema of
 * entity types they are associated with.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "trait",
    indexes = {@Index(name = "idx_trait_name_unq", columnList = "name", unique = true)})
public class Trait implements Type<Trait> {

  /** The unique identifier for this trait, generated as a UUID. */
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  @ToString.Include
  private String id;

  /** The unique name of this trait. */
  @Column(name = "name", nullable = false)
  @ToString.Include
  private String name;

  /** The base JSON schema that defines the properties contributed by this trait. */
  @org.hibernate.annotations.Type(JsonBinaryType.class)
  @Column(name = "base_schema", columnDefinition = "jsonb", nullable = false)
  @ToString.Include
  private JsonNode baseSchema;

  /** The derived JSON schema, computed from base schema and inherited traits. */
  @org.hibernate.annotations.Type(JsonBinaryType.class)
  @Column(name = "derived_schema", columnDefinition = "jsonb", nullable = true)
  @ToString.Include
  private JsonNode derivedSchema;

  /**
   * The parent trait from which this trait inherits. Modelled as {@link ManyToOne}: many child
   * traits may share the same father.
   *
   * <p>Fetched lazily: eager fetch recursively pulled the whole ancestor chain. The finders whose
   * callers read the immediate father outside a transaction ({@code findAll} / {@code findByName})
   * fetch one hop via an {@code @EntityGraph}; the full ancestor walk runs inside a transaction.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  private Trait father;

  /**
   * The monotonically increasing version number of this live trait. Starts at 1 on creation and is
   * bumped every time a new version is created via {@code createVersion}.
   */
  @Column(name = "version", nullable = false)
  @ToString.Include
  private int version = 1;

  /**
   * Groups this live row with every snapshot of the same logical trait in {@link TraitVersion}.
   * Stable across versions.
   */
  @Column(name = "version_group_id", nullable = false)
  private String versionGroupId;

  /** The entity types that have this trait. */
  @ManyToMany(mappedBy = "traits", fetch = FetchType.LAZY)
  private List<EntityType> types = new ArrayList<>();

  /**
   * Returns the effective schema for this trait.
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
