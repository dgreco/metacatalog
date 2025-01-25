package it.davidgreco.metacatalog.entity;

import jakarta.persistence.*;
import java.sql.Timestamp;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

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
  @Id @GeneratedValue @ToString.Include private Long id;

  @ToString.Include
  @Column(name = "entity_id", nullable = false)
  private String entityId;

  @Column(name = "entity_type_name", nullable = false)
  @ToString.Include
  private String entityTypeName;

  @Column(name = "event_time", nullable = false)
  @ToString.Include
  private Timestamp eventTime = new Timestamp(System.currentTimeMillis());

  @Column(name = "process_time", nullable = false)
  @ToString.Include
  private Timestamp processTime = new Timestamp(System.currentTimeMillis());

  @Column(name = "event_type", nullable = false)
  @ToString.Include
  private String eventType;

  @Column(name = "event_status", nullable = false)
  @ToString.Include
  private String eventStatus;

  public EntityLifeCycleEvent(
      String entityId, String entityTypeName, String eventType, String eventStatus) {
    this.entityId = entityId;
    this.entityTypeName = entityTypeName;
    this.eventType = eventType;
    this.eventStatus = eventStatus;
  }

  public EntityLifeCycleEvent() {}
}
