package it.davidgreco.metacatalog.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Type;

/**
 * An immutable snapshot of the values of an {@link Entity} whose type carries the built-in {@link
 * BuiltInTraits#VERSIONABLE} trait.
 *
 * <p>Every version has one, the current one included, so the latest snapshot always equals the live
 * entity. Snapshots are chained through {@code previousVersionId} and numbered from 1 per entity.
 * Each records the {@link EntityTypeVersion} its values were validated against, which can differ
 * between versions of a mapped entity because regeneration re-pins it to the version its mapping
 * was stamped with.
 *
 * <p>History is append-only: snapshots are written by {@code EntityVersionRecorder} and removed
 * only together with their entity.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "entity_version",
    indexes = {
      @Index(
          name = "idx_entity_version_entity_version_unq",
          columnList = "entity_id, version",
          unique = true),
      @Index(name = "idx_entity_version_previous", columnList = "previous_version_id"),
      @Index(
          name = "idx_entity_version_entity_type_version_id",
          columnList = "entity_type_version_id")
    })
public class EntityVersion {

  /** The unique identifier of this snapshot, generated as a UUID. */
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  @ToString.Include
  private String id;

  /**
   * The entity this snapshot belongs to.
   *
   * <p>An association rather than a bare id so Hibernate orders the insert of a new entity before
   * the insert of its first snapshot, which the foreign key requires.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "entity_id", nullable = false)
  private Entity entity;

  /** The version number, starting at 1 and increasing by one per recorded change. */
  @Column(name = "version", nullable = false)
  @ToString.Include
  private int version;

  /** The entity's values as they were at this version. */
  @Type(JsonBinaryType.class)
  @Column(name = "values", columnDefinition = "jsonb", nullable = false)
  private JsonNode values;

  /** The type version snapshot these values were validated against. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "entity_type_version_id", nullable = false)
  private EntityTypeVersion entityTypeVersion;

  /** The id of the snapshot this one succeeds, or {@code null} for the first version. */
  @Column(name = "previous_version_id")
  private String previousVersionId;

  /** When this snapshot was recorded. */
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
