package it.davidgreco.metacatalog.entity;

import jakarta.persistence.*;
import java.sql.Timestamp;
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

  /** The unique identifier for this event. */
  @Id @GeneratedValue @ToString.Include private Long id;

  /** The ID of the entity that this event relates to. */
  @ToString.Include
  @Column(name = "entity_id", nullable = false)
  private String entityId;

  /** The name of the entity type at the time of the event. */
  @Column(name = "entity_type_name", nullable = false)
  @ToString.Include
  private String entityTypeName;

  /** The timestamp when the event occurred. */
  @Column(name = "event_time", nullable = false)
  @ToString.Include
  private Timestamp eventTime = new Timestamp(System.currentTimeMillis());

  /** The timestamp when the event was processed. */
  @Column(name = "process_time", nullable = false)
  @ToString.Include
  private Timestamp processTime = new Timestamp(System.currentTimeMillis());

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
