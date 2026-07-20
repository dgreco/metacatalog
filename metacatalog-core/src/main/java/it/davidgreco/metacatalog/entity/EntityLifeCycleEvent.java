package it.davidgreco.metacatalog.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Represents a lifecycle event for an entity in the metacatalog.
 *
 * <p>Entity lifecycle events track changes to entities such as creation, updates, and deletions.
 * These events are used by the mapping system to trigger automatic creation or update of mapped
 * entities.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "entity_lifecycle_event",
    indexes = {
      @Index(
          name = "idx_entitylifecycleevent_event_type_event_status",
          columnList = "event_type, event_status")
    })
public class EntityLifeCycleEvent {

  /** Event type: a source entity was created and its mappings still need processing. */
  public static final String ENTITY_SOURCE_CREATED = "SOURCE_CREATED";

  /** Event type: a source entity was updated and its mappings still need processing. */
  public static final String ENTITY_SOURCE_UPDATED = "SOURCE_UPDATED";

  /** Event type: an entity was created (no further mapping processing required). */
  public static final String ENTITY_CREATED = "CREATED";

  /** Event type: an entity was updated (no further mapping processing required). */
  public static final String ENTITY_UPDATED = "UPDATED";

  /** Event type: an entity was deleted (no further mapping processing required). */
  public static final String ENTITY_DELETED = "DELETED";

  /** Event status: awaiting processing by the mapping updater. */
  public static final String STATUS_PENDING = "PENDING";

  /** Event status: successfully processed by the mapping updater. */
  public static final String STATUS_PROCESSED = "PROCESSED";

  /** Event status: processing failed permanently; will not be retried. */
  public static final String STATUS_FAILED = "FAILED";

  /** Event status: the event requires no processing. */
  public static final String STATUS_NO_PROCESSING = "NO_PROCESSING";

  /**
   * The unique identifier for this event, generated from the {@code entity_lifecycle_event_seq}
   * sequence created by migration {@code V1}. The strategy is stated explicitly (rather than
   * relying on {@code GenerationType.AUTO}) so the mapping documents the exact sequence and
   * allocation size ({@code INCREMENT BY 50}) it depends on.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "entity_lifecycle_event_seq")
  @SequenceGenerator(
      name = "entity_lifecycle_event_seq",
      sequenceName = "entity_lifecycle_event_seq",
      allocationSize = 50)
  @ToString.Include
  private Long id;

  /** The ID of the entity that this event relates to. */
  @ToString.Include
  @Column(name = "entity_id", nullable = false)
  private String entityId;

  /** The name of the entity type at the time of the event. */
  @Column(name = "entity_type_name", nullable = false)
  @ToString.Include
  private String entityTypeName;

  /** The instant when the event occurred. */
  @Column(name = "event_time", nullable = false)
  @ToString.Include
  private Instant eventTime = Instant.now();

  /** The instant when the event was processed. */
  @Column(name = "process_time", nullable = false)
  @ToString.Include
  private Instant processTime = Instant.now();

  /** The type of event (e.g., SOURCE_CREATED, SOURCE_UPDATED). */
  @Column(name = "event_type", nullable = false)
  @ToString.Include
  private String eventType;

  /** The status of event processing (e.g., PENDING, PROCESSED). */
  @Column(name = "event_status", nullable = false)
  @ToString.Include
  private String eventStatus;

  /**
   * Creates a new entity lifecycle event.
   *
   * @param entityId the ID of the entity
   * @param entityTypeName the name of the entity type
   * @param eventType the type of event
   * @param eventStatus the processing status
   */
  public EntityLifeCycleEvent(
      String entityId, String entityTypeName, String eventType, String eventStatus) {
    this.entityId = entityId;
    this.entityTypeName = entityTypeName;
    this.eventType = eventType;
    this.eventStatus = eventStatus;
  }

  /** Default constructor required by JPA. */
  public EntityLifeCycleEvent() {}
}
