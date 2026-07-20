package it.davidgreco.metacatalog.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link EntityLifeCycleEvent} process-time sentinel semantics. */
class EntityLifeCycleEventTest {

  @Test
  void freshEventIsNotProcessedAndCarriesTheUnprocessedSentinel() {
    var event =
        new EntityLifeCycleEvent(
            "e1",
            "SomeType",
            EntityLifeCycleEvent.ENTITY_SOURCE_CREATED,
            EntityLifeCycleEvent.STATUS_PENDING);
    assertEquals(EntityLifeCycleEvent.PROCESS_TIME_UNPROCESSED, event.getProcessTime());
    assertFalse(event.isProcessed(), "a freshly created event must not report as processed");
  }

  @Test
  void settingARealProcessTimeMarksItProcessed() {
    var event =
        new EntityLifeCycleEvent(
            "e1",
            "SomeType",
            EntityLifeCycleEvent.ENTITY_SOURCE_CREATED,
            EntityLifeCycleEvent.STATUS_PENDING);
    event.setProcessTime(Instant.now());
    assertTrue(event.isProcessed());
  }

  @Test
  void eventTimeIsStampedAtCreation() {
    var before = Instant.now();
    var event =
        new EntityLifeCycleEvent(
            "e1",
            "SomeType",
            EntityLifeCycleEvent.ENTITY_CREATED,
            EntityLifeCycleEvent.STATUS_NO_PROCESSING);
    assertFalse(event.getEventTime().isBefore(before), "event time should be around creation time");
  }
}
