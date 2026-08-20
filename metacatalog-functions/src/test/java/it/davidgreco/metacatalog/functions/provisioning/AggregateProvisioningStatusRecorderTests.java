package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vavr.control.Try;
import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.DependencyFailedError;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TaskFactory;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.concurrent.CompletableFuture;
import org.awaitility.Durations;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The recorder writes two fields on the root and must leave everything else on it alone — including
 * whatever the run itself committed while it was in flight.
 *
 * <p>That is only interesting because of when the write can happen. {@code Schedule.schedule}
 * composes with {@code allOf(...).thenApply(...)} and the recorder attaches with {@code
 * whenComplete}: both run <em>inline on the calling thread</em> when handed futures that have
 * already completed, which for an empty or fast schedule is the thread building the plan, inside
 * {@code ProcedureExecutor}'s transaction. That transaction has already read the root, so a re-read
 * from within it is served by its persistence context and yields the plan-time instance, not the
 * row. Writing that back reverts the run.
 *
 * <p>The test stages exactly that, without racing for it: an outer transaction reads the root the
 * way the executor does, a committed write lands on the row from elsewhere, and the recorder is
 * then handed an already-completed future so its callback runs inline in that outer transaction.
 */
@SpringBootTest
class AggregateProvisioningStatusRecorderTests extends CommonServiceTestingSupport {

  public AggregateProvisioningStatusRecorderTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  /**
   * The database is reset per class, not per method, so the model is loaded once and each test
   * takes a fresh aggregate of its own from it.
   */
  private String freshAggregate() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    if (!getApplicationContext().getBean(EntityTypeService.class).exists("DataProductType")) {
      bulkLoaderService.bulkModelCreation(
          Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml"));
    }
    var resourcesBefore = resourceCount();
    var rootId =
        bulkLoaderService
            .bulkAggregateCreation(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("bulk/bulk2.yaml"))
            .getFirst();
    // The resources a provisioning run acts on are the mapped instances of the two output ports,
    // and the mapping updater derives them on a schedule. Until it has, the resource graph is empty
    // and a run succeeds having done nothing — which would pass most of the assertions below while
    // exercising none of the code they are about. Waiting on the condition, not on a duration.
    await().atMost(Durations.ONE_MINUTE).until(() -> resourceCount() >= resourcesBefore + 2);
    return rootId;
  }

  /** One {@code S3FolderType} and one {@code AthenaTableType} are derived per aggregate. */
  private long resourceCount() {
    var entityService = getApplicationContext().getBean(EntityService.class);
    return entityService.list("S3FolderType", "").size()
        + entityService.list("AthenaTableType", "").size();
  }

  private static TaskFactory succeedingFactory(EntityService entityService) {
    return entity ->
        new ProvisioningTask(entity, entityService) {
          @Override
          public String provision() {
            return "ok";
          }

          @Override
          public String unprovision() {
            return "ok";
          }
        };
  }

  private static TaskFactory failingFactory(EntityService entityService, String error) {
    return entity ->
        new ProvisioningTask(entity, entityService) {
          @Override
          public String provision() {
            throw new ServiceError(error);
          }

          @Override
          public String unprovision() {
            throw new ServiceError(error);
          }
        };
  }

  @Test
  void testTheStatusWriteDoesNotRevertWhatTheRunCommitted() {
    var entityService = getApplicationContext().getBean(EntityService.class);
    var recorder = getApplicationContext().getBean(AggregateProvisioningStatusRecorder.class);
    var transactionManager = getApplicationContext().getBean(PlatformTransactionManager.class);

    var rootId = freshAggregate();

    var outer = new TransactionTemplate(transactionManager);
    var separate = new TransactionTemplate(transactionManager);
    separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    outer.executeWithoutResult(
        transaction -> {
          // What ProcedureExecutor.executeInTransaction does before handing the entity to the
          // procedure: the root is now managed here, holding the values as they were at plan time.
          entityService.read(rootId);

          // What the run does meanwhile, from a transaction of its own — a task committing against
          // the same row. `name` stands in for it: the recorder never writes that field, so if the
          // status write is built on the plan-time snapshot the change disappears.
          separate.executeWithoutResult(
              inner -> {
                var live = entityService.read(rootId);
                var values = (ObjectNode) live.getValues();
                values.put("name", "renamed-by-the-run");
                entityService.updateValues(rootId, values.toPrettyString());
              });

          // Already complete, so whenComplete runs inline: on this thread, in this transaction.
          recorder
              .recording(
                  CompletableFuture.completedFuture(Try.<Void>success(null)),
                  rootId,
                  ProvisioningTask.Operation.PROVISION,
                  2)
              .join();
        });

    var root = entityService.read(rootId);
    Assertions.assertEquals(
        "renamed-by-the-run",
        root.getValues().get("name").asText(),
        "the status write reverted the root to its plan-time values");
    // And it still did its own job.
    Assertions.assertEquals("PROVISIONED", root.getValues().get("provisioningStatus").asText());
    Assertions.assertTrue(
        root.getValues().get("provisioningResult").asText().contains("succeeded"),
        root.getValues().get("provisioningResult").asText());
  }

  /**
   * A run can fail before there is anything to wait for: {@code createTasksForVertices} throws for
   * a resource type with no registered factory, as do a cycle in the graph and a throwing
   * registrar. The caller gets that error, but the root is what the UI and SPARQL read, and it must
   * not still be advertising the {@code PROVISIONED} of the last run that worked.
   */
  @Test
  void testAPlanThatNeverRanStillLeavesTheRootHonest() {
    var entityService = getApplicationContext().getBean(EntityService.class);
    var taskManager = getApplicationContext().getBean(TaskManager.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);

    var rootId = freshAggregate();
    try {
      taskManager.registerTaskFactory("S3FolderType", succeedingFactory(entityService));
      taskManager.registerTaskFactory("AthenaTableType", succeedingFactory(entityService));
      procedureExecutor.executeProcedure("ProvisioningProcedure", rootId);
      Assertions.assertEquals(
          "PROVISIONED", entityService.read(rootId).getValues().get("provisioningStatus").asText());

      // Now the type has no factory, which fails while the plan is still being built.
      taskManager.unregisterTaskFactory("AthenaTableType");
      Assertions.assertThrows(
          ServiceError.class,
          () -> procedureExecutor.executeProcedure("ProvisioningProcedure", rootId));

      var root = entityService.read(rootId);
      Assertions.assertEquals(
          "FAILED",
          root.getValues().get("provisioningStatus").asText(),
          "the root kept the status of the previous successful run");
      Assertions.assertTrue(
          root.getValues().get("provisioningResult").asText().contains("AthenaTableType"),
          root.getValues().get("provisioningResult").asText());
    } finally {
      // Restored because TaskManager's registry lives on a bean shared by every test class in this
      // module, and the Spring context outlives the database reset.
      taskManager.unregisterTaskFactory("S3FolderType");
      taskManager.unregisterTaskFactory("AthenaTableType");
    }
  }

  /**
   * When an upstream resource fails, its dependents are failed with a {@link DependencyFailedError}
   * that says only that something else broke. The root records one message, and it has to be the
   * one naming the actual cause — here two of the three possible results are the restatement, and
   * the task list is ordered by a {@code HashMap}, so picking by position reported the symptom more
   * often than not and changed between runs.
   */
  @Test
  void testTheRecordedFailureNamesTheCauseNotTheDependency() {
    var entityService = getApplicationContext().getBean(EntityService.class);
    var taskManager = getApplicationContext().getBean(TaskManager.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);

    var rootId = freshAggregate();
    try {
      // The Athena table is derived from the S3 folder, so failing the folder takes the table with
      // it: one genuine failure, one dependency failure.
      taskManager.registerTaskFactory(
          "S3FolderType", failingFactory(entityService, "the bucket is on fire"));
      taskManager.registerTaskFactory("AthenaTableType", succeedingFactory(entityService));

      Assertions.assertThrows(
          ServiceError.class,
          () -> procedureExecutor.executeProcedure("ProvisioningProcedure", rootId));

      var result = entityService.read(rootId).getValues().get("provisioningResult").asText();
      Assertions.assertTrue(result.contains("the bucket is on fire"), result);
      Assertions.assertFalse(result.contains(DependencyFailedError.MESSAGE), result);
    } finally {
      taskManager.unregisterTaskFactory("S3FolderType");
      taskManager.unregisterTaskFactory("AthenaTableType");
    }
  }
}
