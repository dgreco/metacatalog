package it.davidgreco.metacatalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.repository.EntityLifeCycleEventRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Integration tests for {@link MappingUpdaterService}. These exercise the transactional advisory
 * lock wiring (the scheduled work must run inside a transaction so {@code
 * pg_try_advisory_xact_lock} is actually held) and the poison-event handling (a permanently failing
 * event must be marked FAILED so it is not re-processed forever).
 */
@SpringBootTest
class MappingUpdaterServiceTests extends CommonServiceTestingSupport {

  public MappingUpdaterServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  private MappingUpdaterService updater() {
    return getApplicationContext().getBean(MappingUpdaterService.class);
  }

  @Test
  void processesPendingSourceCreatedEventAndCreatesMappedEntity() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    entityTypeService.create(
        "UpdSource", List.of(), Optional.empty(), "{ \"type\": \"object\", \"properties\": {} }");
    entityTypeService.create(
        "UpdTarget",
        List.of(),
        Optional.empty(),
        "{ \"type\": \"object\", \"properties\": { \"greeting\": { \"type\": \"string\" } } }");
    mappingService.create("UpdSource", "UpdTarget", "{ \"greeting\": \"'hello'\" }", List.of());

    // Creating a source-typed entity enqueues a SOURCE_CREATED / PENDING lifecycle event.
    entityService.create("UpdSource", "{}");

    var updater = updater();
    var originalMappingFlag = updater.isAutomaticEntitiesMapping();
    updater.setAutomaticEntitiesMapping(true);
    try {
      // A direct call runs the scheduled body: had the advisory lock not been acquired inside a
      // transaction (Propagation.MANDATORY), this would throw instead of processing the event.
      updater.updateMappedEntities();
    } finally {
      updater.setAutomaticEntitiesMapping(originalMappingFlag);
    }

    var mappedTargets = entityService.list("UpdTarget", "");
    assertEquals(1, mappedTargets.size(), "a mapped target entity should have been created");
    assertTrue(events().findByEventTypeAndEventStatus("SOURCE_CREATED", "PENDING").isEmpty());

    var processed = events().findByEventTypeAndEventStatus("SOURCE_CREATED", "PROCESSED");
    assertEquals(1, processed.size());
    assertTrue(
        processed.getFirst().isProcessed(),
        "a processed event must carry a real process timestamp, not the unprocessed sentinel");
  }

  @Test
  void marksPermanentlyFailingEventAsFailedInsteadOfLooping() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    entityTypeService.create(
        "PoisonSource",
        List.of(),
        Optional.empty(),
        "{ \"type\": \"object\", \"properties\": {} }");
    entityTypeService.create(
        "PoisonTarget",
        List.of(),
        Optional.empty(),
        "{ \"type\": \"object\", \"properties\": { \"greeting\": { \"type\": \"string\" } } }");
    // The mapping value is a valid string at create time but the SpEL expression evaluates to an
    // integer at runtime, so generateMappedValues fails schema validation only while processing the
    // event -> a runtime-only (poison) failure.
    mappingService.create("PoisonSource", "PoisonTarget", "{ \"greeting\": \"1+1\" }", List.of());

    entityService.create("PoisonSource", "{}");

    var updater = updater();
    var originalMappingFlag = updater.isAutomaticEntitiesMapping();
    updater.setAutomaticEntitiesMapping(true);
    try {
      updater.updateMappedEntities();
    } finally {
      updater.setAutomaticEntitiesMapping(originalMappingFlag);
    }

    assertTrue(
        events().findByEventTypeAndEventStatus("SOURCE_CREATED", "PENDING").isEmpty(),
        "the poison event must no longer be PENDING");
    assertFalse(
        events().findByEventTypeAndEventStatus("SOURCE_CREATED", "FAILED").isEmpty(),
        "the poison event must be marked FAILED");
    assertTrue(
        entityService.list("PoisonTarget", "").isEmpty(), "no invalid target entity should exist");
  }

  /**
   * Verifies that an <em>unexpected</em> {@link RuntimeException} (not a {@link ServiceError} or
   * {@link ServiceRuntimeError}) during mapped entity creation marks the event as FAILED and lets
   * the scheduler continue processing other events in the same tick.
   *
   * <p>The failure is triggered by a mapping expression that references an undefined SpEL variable
   * ({@code #undefined}). The SpEL evaluator throws a {@link
   * org.springframework.expression.spel.SpelEvaluationException} (a {@link RuntimeException}),
   * which is not caught by the {@code ServiceError | ServiceRuntimeError} catch in the scheduler.
   * Without the outer {@code catch (RuntimeException)} the exception would propagate out of the
   * {@code forEach}, roll back the surrounding transaction, and undo every {@code markFailed} from
   * earlier events in the same tick — a poison-message head-of-line blocking bug.
   */
  @Test
  void marksEventAsFailedOnUnexpectedRuntimeException() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    entityTypeService.create(
        "RceSource", List.of(), Optional.empty(), "{ \"type\": \"object\", \"properties\": {} }");
    entityTypeService.create(
        "RceTarget",
        List.of(),
        Optional.empty(),
        "{ \"type\": \"object\", \"properties\": { \"greeting\": { \"type\": \"string\" } } }");
    // The mapping references #undefined, a SpEL variable that does not exist. At create time the
    // mapping values are stored as-is (no evaluation); at event-processing time the SpEL evaluator
    // throws SpelEvaluationException, which is a RuntimeException — not a ServiceError.
    mappingService.create("RceSource", "RceTarget", "{ \"greeting\": \"#undefined\" }", List.of());

    entityService.create("RceSource", "{}");

    var updater = updater();
    var originalMappingFlag = updater.isAutomaticEntitiesMapping();
    updater.setAutomaticEntitiesMapping(true);
    try {
      updater.updateMappedEntities();
    } finally {
      updater.setAutomaticEntitiesMapping(originalMappingFlag);
    }

    assertTrue(
        events().findByEventTypeAndEventStatus("SOURCE_CREATED", "PENDING").isEmpty(),
        "the event must no longer be PENDING after the scheduler tick");
    assertFalse(
        events().findByEventTypeAndEventStatus("SOURCE_CREATED", "FAILED").isEmpty(),
        "the event must be marked FAILED despite the unexpected RuntimeException");
    assertTrue(
        entityService.list("RceTarget", "").isEmpty(),
        "no target entity should have been created from the failing mapping");
  }

  private EntityLifeCycleEventRepository events() {
    return getApplicationContext().getBean(EntityLifeCycleEventRepository.class);
  }
}
