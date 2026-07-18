package it.davidgreco.metacatalog.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * An immutable snapshot of a {@link Trait} captured each time a new version of the trait is
 * created.
 *
 * <p>The live {@code trait} table always holds exactly one row per name (the current version). When
 * {@link it.davidgreco.metacatalog.service.TraitService#createVersion} is called, the current live
 * state is copied into this history table, the live row is mutated in place with the new schema /
 * father, and its {@code version} is bumped.
 *
 * <p>Snapshots are self-contained: {@code baseSchema} and {@code derivedSchema} are frozen at the
 * moment the snapshot was taken, so reading an old version always returns the schema that was
 * effective at that point.
 *
 * <p>The version chain is carried by the {@code previousVersionId} self-reference: each snapshot
 * points to the snapshot that preceded it. The UI synthesises "successor-of" graph edges from this
 * chain.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "trait_version",
    indexes = {
      @Index(
          name = "idx_trait_version_group_version_unq",
          columnList = "version_group_id, version",
          unique = true),
      @Index(name = "idx_trait_version_previous", columnList = "previous_version_id")
    })
public class TraitVersion {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  @ToString.Include
  private String id;

  @Column(name = "version_group_id", nullable = false)
  @ToString.Include
  private String versionGroupId;

  @Column(name = "version", nullable = false)
  @ToString.Include
  private int version;

  @Column(name = "name", nullable = false)
  private String name;

  @org.hibernate.annotations.Type(JsonBinaryType.class)
  @Column(name = "base_schema", columnDefinition = "jsonb", nullable = false)
  private JsonNode baseSchema;

  @org.hibernate.annotations.Type(JsonBinaryType.class)
  @Column(name = "derived_schema", columnDefinition = "jsonb", nullable = true)
  private JsonNode derivedSchema;

  @Column(name = "father_name")
  private String fatherName;

  @Column(name = "previous_version_id")
  private String previousVersionId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /**
   * Returns the effective schema frozen in this snapshot: the derived schema if present, else the
   * base schema.
   */
  public JsonNode getSchema() {
    return this.derivedSchema != null ? this.derivedSchema : this.baseSchema;
  }
}
