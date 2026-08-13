package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TaskFactory;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.concurrent.CountDownLatch;
import org.awaitility.Durations;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class AsyncProcedureExecutionTests extends CommonServiceTestingSupport {

  public AsyncProcedureExecutionTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void asyncExecutionReturnsImmediatelyAndIsPollable() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var taskManager = getApplicationContext().getBean(TaskManager.class);

    bulkLoaderService.bulkModelCreation(
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml"));
    var ids =
        bulkLoaderService.bulkAggregateCreation(
            Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml"));
    var aggregateId = ids.getFirst();

    // The provisionable resources are mapped entities, derived asynchronously by the mapping
    // scheduler — wait until they exist or the plan would be empty and trivially SUCCEEDED.
    var aggregateService =
        getApplicationContext().getBean(it.davidgreco.metacatalog.service.AggregateService.class);
    await()
        .atMost(Durations.ONE_MINUTE)
        .until(
            () ->
                aggregateService.read(aggregateId, true).elements().stream()
                    .allMatch(
                        element ->
                            element
                                    instanceof
                                    it.davidgreco.metacatalog.service.AggregateService.Aggregate
                                        aggregate
                                && !aggregate.elements().isEmpty()));

    // Provisioning blocks on the latch so the RUNNING state is observable; unprovisioning
    // throws so the FAILED state is observable.
    var latch = new CountDownLatch(1);
    TaskFactory factory =
        (Entity entity) ->
            new ProvisioningTask(entity, entityService) {
              @Override
              public String provision() {
                try {
                  latch.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return "ok";
              }

              @Override
              public String unprovision() {
                throw new RuntimeException("unprovision boom");
              }
            };
    taskManager.registerTaskFactory("S3FolderType", factory);
    taskManager.registerTaskFactory("AthenaTableType", factory);

    // Launch: returns right after plan-building, while the tasks are still blocked.
    var scheduleId = procedureExecutor.executeProcedureAsync("ProvisioningProcedure", aggregateId);
    assertNotNull(scheduleId);
    var running = procedureExecutor.procedureStatus(scheduleId).orElseThrow();
    assertEquals(ProcedureExecutor.ProcedureState.RUNNING, running.state());

    // Release the tasks and poll to completion.
    latch.countDown();
    await()
        .atMost(Durations.ONE_MINUTE)
        .until(
            () ->
                procedureExecutor.procedureStatus(scheduleId).orElseThrow().state()
                    == ProcedureExecutor.ProcedureState.SUCCEEDED);

    // A failing run ends FAILED with the task's error preserved.
    var failedId = procedureExecutor.executeProcedureAsync("UnprovisioningProcedure", aggregateId);
    await()
        .atMost(Durations.ONE_MINUTE)
        .until(
            () ->
                procedureExecutor.procedureStatus(failedId).orElseThrow().state()
                    == ProcedureExecutor.ProcedureState.FAILED);
    // The contract is FAILED plus a non-null error. Its exact text is not pinned: the schedule
    // reports the first failed task, which can be the throwing one or a dependent that fell
    // with it, and their messages differ.
    var failed = procedureExecutor.procedureStatus(failedId).orElseThrow();
    assertNotNull(failed.error());

    // Unknown ids read as absent; plan-building failures still fail on the caller's thread.
    assertTrue(procedureExecutor.procedureStatus("no-such-schedule").isEmpty());
    assertThrows(
        ServiceError.class,
        () -> procedureExecutor.executeProcedureAsync("NoSuchProcedure", aggregateId));
  }
}
