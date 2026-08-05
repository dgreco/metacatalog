package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.EntityLifeCycleEvent.ENTITY_SOURCE_CREATED;
import static it.davidgreco.metacatalog.entity.EntityLifeCycleEvent.STATUS_FAILED;
import static it.davidgreco.metacatalog.entity.EntityLifeCycleEvent.STATUS_PENDING;
import static it.davidgreco.metacatalog.entity.EntityLifeCycleEvent.STATUS_PROCESSED;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.entity.EntityLifeCycleEvent;
import it.davidgreco.metacatalog.repository.EntityLifeCycleEventRepository;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.awaitility.Durations;
import org.awaitility.core.ThrowingRunnable;
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

  /**
   * The lifecycle events of the given type and status raised for {@code entityTypeName}.
   *
   * <p>Every assertion here has to be scoped to the types the test itself created. The database is
   * reset once per class rather than per test, and the scheduler runs in the background throughout,
   * so a repository-wide query would also see the other tests' events.
   */
  private List<EntityLifeCycleEvent> eventsFor(
      String entityTypeName, String eventType, String eventStatus) {
    return events().findByEventTypeAndEventStatus(eventType, eventStatus).stream()
        .filter(event -> entityTypeName.equals(event.getEntityTypeName()))
        .toList();
  }

  /**
   * Runs one tick with automatic mapping enabled and waits for {@code expectations} to hold.
   *
   * <p>The direct call is what proves the transactional wiring: were the advisory lock not acquired
   * inside a transaction ({@link
   * org.springframework.transaction.annotation.Propagation#MANDATORY}), it would throw here rather
   * than process anything.
   *
   * <p>Waiting afterwards is what makes the test independent of the scheduler, which is enabled in
   * this module's test configuration and ticks every second. The lock is taken with {@code
   * pg_try_advisory_xact_lock}, so whichever of the two callers arrives second gives up and returns
   * without doing anything — asserting straight after the direct call would then read the state
   * before any tick had finished. The wait stays inside the enabling block so the scheduler is
   * still allowed to run while we wait for it.
   */
  private void tickAndAwait(ThrowingRunnable expectations) {
    var updater = updater();
    var originalMappingFlag = updater.isAutomaticEntitiesMapping();
    updater.setAutomaticEntitiesMapping(true);
    try {
      updater.updateMappedEntities();
      await().atMost(Durations.TEN_SECONDS).untilAsserted(expectations);
    } finally {
      updater.setAutomaticEntitiesMapping(originalMappingFlag);
    }
  }

  @Test
  void processesPendingSourceCreatedEventAndCreatesMappedEntity() {
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

    tickAndAwait(
        () -> {
          assertEquals(
              1,
              entityService.list("UpdTarget", "").size(),
              "a mapped target entity should have been created");
          assertTrue(
              eventsFor("UpdSource", ENTITY_SOURCE_CREATED, STATUS_PENDING).isEmpty(),
              "the event must no longer be PENDING");

          var processed = eventsFor("UpdSource", ENTITY_SOURCE_CREATED, STATUS_PROCESSED);
          assertEquals(1, processed.size());
          assertTrue(
              processed.getFirst().isProcessed(),
              "a processed event must carry a real process timestamp, not the unprocessed sentinel");
        });
  }

  @Test
  void marksPermanentlyFailingEventAsFailedInsteadOfLooping() {
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

    tickAndAwait(
        () -> {
          assertTrue(
              eventsFor("PoisonSource", ENTITY_SOURCE_CREATED, STATUS_PENDING).isEmpty(),
              "the poison event must no longer be PENDING");
          assertFalse(
              eventsFor("PoisonSource", ENTITY_SOURCE_CREATED, STATUS_FAILED).isEmpty(),
              "the poison event must be marked FAILED");
          assertTrue(
              entityService.list("PoisonTarget", "").isEmpty(),
              "no invalid target entity should exist");
        });
  }

  /**
   * Verifies that an <em>unexpected</em> {@link RuntimeException} (not a {@link ServiceError} or
   * {@link ServiceError}) during mapped entity creation marks the event as FAILED and lets the
   * scheduler continue processing other events in the same tick.
   *
   * <p>The failure is triggered by a mapping expression that references an undefined SpEL variable
   * ({@code #undefined}). The SpEL evaluator throws a {@link
   * org.springframework.expression.spel.SpelEvaluationException} (a {@link RuntimeException}),
   * which is not caught by the {@code ServiceError | ServiceError} catch in the scheduler. Without
   * the outer {@code catch (RuntimeException)} the exception would propagate out of the {@code
   * forEach}, roll back the surrounding transaction, and undo every {@code markFailed} from earlier
   * events in the same tick — a poison-message head-of-line blocking bug.
   */
  @Test
  void marksEventAsFailedOnUnexpectedRuntimeException() {
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

    tickAndAwait(
        () -> {
          assertTrue(
              eventsFor("RceSource", ENTITY_SOURCE_CREATED, STATUS_PENDING).isEmpty(),
              "the event must no longer be PENDING after the scheduler tick");
          assertFalse(
              eventsFor("RceSource", ENTITY_SOURCE_CREATED, STATUS_FAILED).isEmpty(),
              "the event must be marked FAILED despite the unexpected RuntimeException");
          assertTrue(
              entityService.list("RceTarget", "").isEmpty(),
              "no target entity should have been created from the failing mapping");
        });
  }

  /**
   * Pins the behaviour the other tests have to tolerate: the lock is taken with {@code
   * pg_try_advisory_xact_lock}, so a tick that finds it held elsewhere returns immediately without
   * touching a single event, rather than waiting its turn.
   *
   * <p>This is what made those tests flaky when they asserted straight after their own direct call
   * — a concurrent scheduler tick holding the lock turned that call into a no-op, and the event was
   * still PENDING when the assertions ran.
   */
  @Test
  void skipsTheTickEntirelyWhenAnotherInstanceHoldsTheLock() throws SQLException {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    entityTypeService.create(
        "LockSource", List.of(), Optional.empty(), "{ \"type\": \"object\", \"properties\": {} }");
    entityTypeService.create(
        "LockTarget",
        List.of(),
        Optional.empty(),
        "{ \"type\": \"object\", \"properties\": { \"greeting\": { \"type\": \"string\" } } }");
    mappingService.create("LockSource", "LockTarget", "{ \"greeting\": \"'hello'\" }", List.of());

    entityService.create("LockSource", "{}");

    var updater = updater();
    var originalMappingFlag = updater.isAutomaticEntitiesMapping();
    updater.setAutomaticEntitiesMapping(true);
    try {
      // A second connection standing in for another application instance. The blocking variant is
      // safe here: the service only ever uses the try_ variant, so it cannot queue behind us.
      try (var connection =
          DriverManager.getConnection(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
        connection.setAutoCommit(false);
        try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(1)")) {
          lock.execute();
        }

        updater.updateMappedEntities();

        assertFalse(
            eventsFor("LockSource", ENTITY_SOURCE_CREATED, STATUS_PENDING).isEmpty(),
            "the event must still be PENDING: the tick could not take the lock");
        assertTrue(
            entityService.list("LockTarget", "").isEmpty(),
            "no mapped entity should have been created by a tick that never ran");

        connection.rollback();
      }

      // Once the lock is free the work still gets done, so nothing is lost — only deferred. This
      // has to stay inside the enabling block: a later tick is what picks the event up.
      await()
          .atMost(Durations.TEN_SECONDS)
          .untilAsserted(
              () ->
                  assertEquals(
                      1,
                      entityService.list("LockTarget", "").size(),
                      "the deferred event must be picked up by a later tick"));
    } finally {
      updater.setAutomaticEntitiesMapping(originalMappingFlag);
    }
  }

  private EntityLifeCycleEventRepository events() {
    return getApplicationContext().getBean(EntityLifeCycleEventRepository.class);
  }
}
