package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.ProcedureRun;
import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.repository.ProcedureRunRepository;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TaskFactory;
import it.davidgreco.metacatalog.service.TaskManager;
import java.time.Duration;
import java.time.Instant;
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

  /**
   * The registry is a durable table cleaned by {@link ProcedureExecutor#cleanupProcedureRuns()}:
   * terminal rows older than the retention are deleted, RUNNING rows that old are marked FAILED
   * (their executing instance is gone), and everything younger is untouched.
   */
  @Test
  void retentionJobFailsStaleRunsAndDeletesOldTerminalOnes() {
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);
    var repository = getApplicationContext().getBean(ProcedureRunRepository.class);

    var beyondRetention = Instant.now().minus(Duration.ofDays(2));

    var oldDone =
        new ProcedureRun(
            "old-done", ProcedureRun.State.SUCCEEDED, null, "ProvisioningProcedure", "e1");
    oldDone.setUpdatedAt(beyondRetention);
    repository.save(oldDone);
    repository.save(
        new ProcedureRun(
            "fresh-done", ProcedureRun.State.SUCCEEDED, null, "ProvisioningProcedure", "e2"));
    var lostRun =
        new ProcedureRun(
            "lost-run", ProcedureRun.State.RUNNING, null, "ProvisioningProcedure", "e3");
    lostRun.setUpdatedAt(beyondRetention);
    repository.save(lostRun);
    repository.save(
        new ProcedureRun(
            "live-run", ProcedureRun.State.RUNNING, null, "ProvisioningProcedure", "e4"));

    procedureExecutor.cleanupProcedureRuns();

    assertTrue(
        procedureExecutor.procedureStatus("old-done").isEmpty(),
        "a terminal row past retention is deleted and polls as absent");
    assertEquals(
        ProcedureExecutor.ProcedureState.SUCCEEDED,
        procedureExecutor.procedureStatus("fresh-done").orElseThrow().state());
    var lost = procedureExecutor.procedureStatus("lost-run").orElseThrow();
    assertEquals(
        ProcedureExecutor.ProcedureState.FAILED,
        lost.state(),
        "a RUNNING row past retention lost its instance and is marked FAILED, not deleted");
    assertEquals(ProcedureExecutor.STALE_RUN_ERROR, lost.error());
    assertEquals(
        ProcedureExecutor.ProcedureState.RUNNING,
        procedureExecutor.procedureStatus("live-run").orElseThrow().state());
  }
}
