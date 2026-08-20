package it.davidgreco.metacatalog.functions.provisioning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vavr.control.Try;
import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.EntityService;
import java.util.concurrent.CompletableFuture;
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

  @Test
  void testTheStatusWriteDoesNotRevertWhatTheRunCommitted() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var recorder = getApplicationContext().getBean(AggregateProvisioningStatusRecorder.class);
    var transactionManager = getApplicationContext().getBean(PlatformTransactionManager.class);

    bulkLoaderService.bulkModelCreation(
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml"));
    var rootId =
        bulkLoaderService
            .bulkAggregateCreation(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("bulk/bulk2.yaml"))
            .getFirst();

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
}
