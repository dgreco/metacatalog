package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.service.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Durations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The authorization counterpart of {@link ProvisioningProcedureTests}, over the same aggregate:
 * {@code bulk/bulk1.yaml} gives {@code DataProductType} both capability traits and its two resource
 * types both resource traits, which is the realistic case — a data product is provisioned
 * <em>and</em> authorized — and the one that would catch the two runs writing over each other's
 * status.
 */
@Slf4j
@SpringBootTest
class AuthorizationProcedureTests extends CommonServiceTestingSupport {

  private static final String AUTHORIZE = AuthorizationProcedure.class.getSimpleName();
  private static final String REJECT = RejectionProcedure.class.getSimpleName();

  public AuthorizationProcedureTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  /**
   * A task that implements only the authorization pair. The provisioning pair is abstract — a task
   * must always have both directions of it — but these tests are about the other capability, so the
   * two are stubbed once here rather than in every anonymous factory below.
   */
  private abstract static class AuthorizationOnlyTask extends ProvisioningTask {

    AuthorizationOnlyTask(Entity entity, EntityService entityService) {
      super(entity, entityService);
    }

    @Override
    public String provision() {
      return "unused";
    }

    @Override
    public String unprovision() {
      return "unused";
    }
  }

  /**
   * The database is reset once per class, not once per test, so the model is loaded only if it is
   * not there yet: the bulk endpoint is all-or-nothing and dies on the first name it already holds.
   * The <em>instances</em> are re-created per test, each one getting its own aggregate to act on so
   * no test depends on what another left behind.
   */
  @BeforeEach
  void loadModel() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    if (!entityTypeService.exists("DataProductType")) {
      getApplicationContext()
          .getBean(BulkLoaderService.class)
          .bulkModelCreation(
              Thread.currentThread()
                  .getContextClassLoader()
                  .getResourceAsStream("bulk/bulk1.yaml"));
    }
  }

  /**
   * The factory registry is a field of the singleton {@link TaskManager} bean and the Spring
   * context outlives the per-class database reset, so anything left registered here would be
   * inherited by whatever test class runs next. See the same guard in {@link
   * ProvisioningProcedureTests}.
   */
  @AfterEach
  void unregisterTaskFactories() {
    var taskManager = getApplicationContext().getBean(TaskManager.class);
    taskManager.unregisterTaskFactory("S3FolderType");
    taskManager.unregisterTaskFactory("AthenaTableType");
  }

  @Test
  void testAuthorizationProcedure() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var taskManager = getApplicationContext().getBean(TaskManager.class);

    var ids =
        bulkLoaderService.bulkAggregateCreation(
            Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml"));

    // The resources are derived asynchronously by the mapping updater; there is nothing to
    // authorize until they exist.
    awaitMappedResources(aggregateService, ids.getFirst());

    // The order the tasks actually ran in. bulk2.yaml has op2 depending on op1, and the mapping
    // carries that through to their resources: the AthenaTableType reads the FileBasedOutputPort's
    // S3FolderType. So the S3 folder must be authorized first and rejected last.
    var order = Collections.synchronizedList(new ArrayList<String>());

    TaskFactory factory =
        (Entity entity) ->
            new AuthorizationOnlyTask(entity, entityService) {
              @Override
              public String authorize() {
                order.add("authorize:" + getEntity().getEntityType().getName());
                return "Authorized entity: " + getEntity().getId() + " successfully";
              }

              @Override
              public String reject() {
                order.add("reject:" + getEntity().getEntityType().getName());
                return "Rejected entity: " + getEntity().getId() + " successfully";
              }
            };

    taskManager.registerTaskFactory("S3FolderType", factory);
    taskManager.registerTaskFactory("AthenaTableType", factory);

    procedureExecutor.executeProcedure(AUTHORIZE, ids.getFirst());

    // The Authorizable root records the whole run's outcome, and the synchronous call returns only
    // after that write: AUTHORIZED because every contained resource was.
    var rootAfterAuthorize = entityService.read(ids.getFirst());
    Assertions.assertEquals(
        "AUTHORIZED", rootAfterAuthorize.getValues().get("authorizationStatus").asText());
    Assertions.assertEquals(
        "Authorizing succeeded: all 2 resource(s) authorized",
        rootAfterAuthorize.getValues().get("authorizationResult").asText());
    // The authorization run writes its own pair of fields and leaves the provisioning pair alone —
    // the aggregate was never provisioned, so there is nothing there to read.
    Assertions.assertNull(rootAfterAuthorize.getValues().get("provisioningStatus"));

    for (var resource : resources(aggregateService, ids.getFirst())) {
      Assertions.assertEquals(
          "AUTHORIZED", resource.getValues().get("authorizationStatus").asText());
      Assertions.assertEquals(
          "Authorized entity: " + resource.getId() + " successfully",
          resource.getValues().get("authorizationResult").asText());
    }

    // Withdrawing the same aggregate's access, in the same test rather than a second one: this
    // module's tests share a database per class, so a separate test authorizing these same types
    // would depend on which ran first.
    procedureExecutor.executeProcedure(REJECT, ids.getFirst());

    var rootAfterReject = entityService.read(ids.getFirst());
    Assertions.assertEquals(
        "REJECTED", rootAfterReject.getValues().get("authorizationStatus").asText());
    Assertions.assertEquals(
        "Rejecting succeeded: all 2 resource(s) rejected",
        rootAfterReject.getValues().get("authorizationResult").asText());

    for (var resource : resources(aggregateService, ids.getFirst())) {
      Assertions.assertEquals("REJECTED", resource.getValues().get("authorizationStatus").asText());
      Assertions.assertEquals(
          "Rejected entity: " + resource.getId() + " successfully",
          resource.getValues().get("authorizationResult").asText());
    }

    // Dependencies first going in, dependents first coming back out — the same asymmetry
    // provisioning has, and for the same reason: getting the second one backwards would leave the
    // Athena table holding a grant on a folder it can no longer reach.
    Assertions.assertEquals(
        List.of(
            "authorize:S3FolderType",
            "authorize:AthenaTableType",
            "reject:AthenaTableType",
            "reject:S3FolderType"),
        List.copyOf(order));

    // A resource failing must leave the root FAILED, not partially AUTHORIZED.
    taskManager.unregisterTaskFactory("AthenaTableType");
    taskManager.registerTaskFactory(
        "AthenaTableType",
        (Entity entity) ->
            new AuthorizationOnlyTask(entity, entityService) {
              @Override
              public String authorize() {
                throw new ServiceError("The grant service is down");
              }

              @Override
              public String reject() {
                throw new ServiceError("The grant service is down");
              }
            });
    Assertions.assertThrows(
        ServiceError.class, () -> procedureExecutor.executeProcedure(AUTHORIZE, ids.getFirst()));
    var rootAfterFailure = entityService.read(ids.getFirst());
    Assertions.assertEquals(
        "FAILED", rootAfterFailure.getValues().get("authorizationStatus").asText());
    Assertions.assertTrue(
        rootAfterFailure
            .getValues()
            .get("authorizationResult")
            .asText()
            .contains("The grant service is down"));
  }

  /**
   * A task that never overrode {@code authorize()} must fail its own run naming itself, rather than
   * reporting a resource as authorized by nothing. The pair is not abstract — a type may be
   * provisionable and never authorizable — so this is the guard that keeps the omission loud.
   */
  @Test
  void testTaskWithoutAuthorizeFailsTheRun() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var taskManager = getApplicationContext().getBean(TaskManager.class);

    var ids =
        bulkLoaderService.bulkAggregateCreation(
            Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml"));

    awaitMappedResources(getApplicationContext().getBean(AggregateService.class), ids.getFirst());

    TaskFactory provisioningOnly =
        (Entity entity) ->
            new ProvisioningTask(entity, entityService) {
              @Override
              public String provision() {
                return "Provisioned";
              }

              @Override
              public String unprovision() {
                return "Unprovisioned";
              }
            };
    taskManager.registerTaskFactory("S3FolderType", provisioningOnly);
    taskManager.registerTaskFactory("AthenaTableType", provisioningOnly);

    Assertions.assertThrows(
        ServiceError.class, () -> procedureExecutor.executeProcedure(AUTHORIZE, ids.getFirst()));

    // The root's result names the cause, not just the wrapper the task failed with: this is the
    // field someone opens to find out why a run failed.
    var root = entityService.read(ids.getFirst());
    Assertions.assertEquals("FAILED", root.getValues().get("authorizationStatus").asText());
    Assertions.assertTrue(
        root.getValues().get("authorizationResult").asText().contains("does not implement"),
        root.getValues().get("authorizationResult").asText());
  }

  /**
   * The procedure acts on the root of an aggregate and only when that root is {@code Authorizable}.
   * An intermediate node — legitimately an aggregate in its own right, and here a member of one
   * that <em>is</em> authorizable — is refused naming the missing trait.
   */
  @Test
  void testRootMustBeAuthorizable() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

    var ids =
        bulkLoaderService.bulkAggregateCreation(
            Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml"));

    var outputPort =
        ((AggregateService.Aggregate)
                ((AggregateService.Aggregate) aggregateService.read(ids.getFirst(), false))
                    .elements()
                    .getFirst())
            .entity();

    var failure =
        Assertions.assertThrows(
            ServiceError.class,
            () -> procedureExecutor.executeProcedure(AUTHORIZE, outputPort.getId()));
    Assertions.assertTrue(failure.getMessage().contains("has not a trait Authorizable"));
  }

  /** Waits until the mapping updater has derived a resource under each of the two output ports. */
  private static void awaitMappedResources(AggregateService aggregateService, String rootId) {
    await()
        .atMost(Durations.ONE_MINUTE)
        .pollDelay(Durations.ONE_SECOND)
        .pollInterval(Durations.ONE_SECOND)
        .until(
            () ->
                aggregateService.read(rootId, true).elements().stream()
                        .filter(AggregateService.Aggregate.class::isInstance)
                        .map(AggregateService.Aggregate.class::cast)
                        .filter(port -> !port.elements().isEmpty())
                        .count()
                    == 2);
  }

  /** The mapped resources of the aggregate, one per output port, in tree order. */
  private static List<Entity> resources(AggregateService aggregateService, String rootId) {
    var aggregate = (AggregateService.Aggregate) aggregateService.read(rootId, true);
    return aggregate.elements().stream()
        .map(AggregateService.Aggregate.class::cast)
        .map(port -> ((AggregateService.AggregateElement) port.elements().getFirst()).entity())
        .toList();
  }
}
