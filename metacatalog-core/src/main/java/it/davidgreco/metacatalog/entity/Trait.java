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
 * They support inheritance through a parent trait (father) and any number of mixed-in traits, and
 * contribute to the derived schema of entity types they are associated with.
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
   * The traits mixed into this trait, in declaration order — the last one has the highest
   * precedence, as for an entity type's traits.
   *
   * <p>A father alone cannot express a trait that <em>is</em> two unrelated things: {@code
   * Provisionable} and {@code Authorizable} are siblings under {@code Aggregate}, so with single
   * inheritance nothing could carry both, and every type that wanted both had to mix each in
   * itself. The father keeps the role a Scala superclass has and these keep the role of its {@code
   * with} clauses; {@link TypeLinearization} already folds both, so an ancestor reached twice (a
   * diamond) contributes once.
   *
   * <p>Ordered by a column rather than left to insertion order, because the order decides which
   * declaration of a property wins and must survive a reload.
   *
   * <p>Fetched lazily, like the father; the finders whose callers read the names outside a
   * transaction fetch one hop.
   */
  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "trait_traits",
      joinColumns = @JoinColumn(name = "trait_id"),
      inverseJoinColumns = @JoinColumn(name = "mixin_id"))
  @OrderColumn(name = "position")
  private List<Trait> traits = new ArrayList<>();

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

  /**
   * Whether this trait is frozen: an immutable trait can be neither deleted nor versioned.
   *
   * <p>Set at creation and never cleared afterwards — there is deliberately no API that turns it
   * off, so "immutable" is a guarantee rather than a lock someone can pick. Correcting a trait
   * marked by mistake means going to the database.
   *
   * <p>The flag freezes the trait itself, not everything touching it: a new relationship may still
   * be linked to an immutable trait, and a mutable trait may still inherit from one, because
   * neither writes to this row.
   */
  @Column(name = "immutable", nullable = false)
  @ToString.Include
  private boolean immutable = false;

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
