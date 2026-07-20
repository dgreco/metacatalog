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
    updater.setAutomaticEntitiesMapping(true);
    try {
      // A direct call runs the scheduled body: had the advisory lock not been acquired inside a
      // transaction (Propagation.MANDATORY), this would throw instead of processing the event.
      updater.updateMappedEntities();
    } finally {
      updater.setAutomaticEntitiesMapping(false);
    }

    var mappedTargets = entityService.list("UpdTarget", "");
    assertEquals(1, mappedTargets.size(), "a mapped target entity should have been created");
    assertTrue(events().findByEventTypeAndEventStatus("SOURCE_CREATED", "PENDING").isEmpty());
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
    updater.setAutomaticEntitiesMapping(true);
    try {
      updater.updateMappedEntities();
    } finally {
      updater.setAutomaticEntitiesMapping(false);
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

  private EntityLifeCycleEventRepository events() {
    return getApplicationContext().getBean(EntityLifeCycleEventRepository.class);
  }
}
