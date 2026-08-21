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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@Slf4j
@SpringBootTest
class ProvisioningProcedureTests extends CommonServiceTestingSupport {

  public ProvisioningProcedureTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  /**
   * The factory registry is a field of the singleton {@link TaskManager} bean, and the Spring
   * context outlives the per-class database reset — so whatever is left registered here is
   * inherited by every test class that runs afterwards. This test deliberately finishes with a
   * permanently-failing {@code AthenaTableType} factory in place, and leaving it would fail an
   * unrelated test later with "Athena is down" and nothing tying that back to here.
   */
  @AfterEach
  void unregisterTaskFactories() {
    var taskManager = getApplicationContext().getBean(TaskManager.class);
    taskManager.unregisterTaskFactory("S3FolderType");
    taskManager.unregisterTaskFactory("AthenaTableType");
  }

  @Test
  void testProvisioningProcedure() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var taskManager = getApplicationContext().getBean(TaskManager.class);

    var bulkFileStream1 =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml");

    bulkLoaderService.bulkModelCreation(bulkFileStream1);

    var bulkFileStream2 =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml");

    var ids = bulkLoaderService.bulkAggregateCreation(bulkFileStream2);

    await().pollDelay(Durations.TWO_SECONDS).until(() -> true);

    var functionName = "ProvisioningProcedure";

    // The order the tasks actually ran in. bulk2.yaml has op2 depending on op1, and the mapping
    // carries that through to their resources: the AthenaTableType reads the FileBasedOutputPort's
    // S3FolderType. So the S3 folder must be created first and destroyed last.
    var order = Collections.synchronizedList(new ArrayList<String>());

    TaskFactory factory =
        (Entity entity) ->
            new ProvisioningTask(entity, entityService) {
              @Override
              public String provision() {
                order.add("provision:" + getEntity().getEntityType().getName());
                return "Provisioned entity: " + getEntity().getId() + " successfully";
              }

              @Override
              public String unprovision() {
                order.add("unprovision:" + getEntity().getEntityType().getName());
                return "Unprovisioned entity: " + getEntity().getId() + " successfully";
              }
            };

    taskManager.registerTaskFactory("S3FolderType", factory);
    taskManager.registerTaskFactory("AthenaTableType", factory);

    procedureExecutor.executeProcedure(functionName, ids.getFirst());

    // The Provisionable root records the whole run's outcome, and the synchronous call returns
    // only after that write: PROVISIONED because every contained resource provisioned.
    var rootAfterProvision = entityService.read(ids.getFirst());
    Assertions.assertEquals(
        "PROVISIONED", rootAfterProvision.getValues().get("provisioningStatus").asText());
    Assertions.assertEquals(
        "Provisioning succeeded: all 2 resource(s) provisioned",
        rootAfterProvision.getValues().get("provisioningResult").asText());

    var aggr = aggregateService.read(ids.getFirst(), true);

    await()
        .atMost(Durations.ONE_MINUTE)
        .pollDelay(Durations.ONE_SECOND)
        .until(
            () -> !((AggregateService.Aggregate) aggr.elements().getFirst()).elements().isEmpty());

    Assertions.assertEquals(
        "PROVISIONED",
        ((AggregateService.AggregateElement)
                ((AggregateService.Aggregate) aggr.elements().getFirst()).elements().getFirst())
            .entity()
            .getValues()
            .get("provisioningStatus")
            .asText());

    var res1 =
        ((AggregateService.AggregateElement)
                ((AggregateService.Aggregate) aggr.elements().get(0)).elements().getFirst())
            .entity()
            .getValues()
            .get("provisioningResult")
            .asText();

    var expectedRes1 =
        "Provisioned entity: "
            + ((AggregateService.AggregateElement)
                    ((AggregateService.Aggregate) aggr.elements().get(0)).elements().getFirst())
                .entity()
                .getId()
            + " successfully";

    Assertions.assertEquals(expectedRes1, res1);

    Assertions.assertEquals(
        "PROVISIONED",
        ((AggregateService.AggregateElement)
                ((AggregateService.Aggregate) aggr.elements().get(1)).elements().getFirst())
            .entity()
            .getValues()
            .get("provisioningStatus")
            .asText());

    var res2 =
        ((AggregateService.AggregateElement)
                ((AggregateService.Aggregate) aggr.elements().get(1)).elements().getFirst())
            .entity()
            .getValues()
            .get("provisioningResult")
            .asText();

    var expectedRes2 =
        "Provisioned entity: "
            + ((AggregateService.AggregateElement)
                    ((AggregateService.Aggregate) aggr.elements().get(1)).elements().getFirst())
                .entity()
                .getId()
            + " successfully";

    Assertions.assertEquals(expectedRes2, res2);

    // Tearing the same aggregate back down, in the same test rather than a second one: this
    // module's tests share a database per class, so a separate test provisioning these same types
    // would depend on which ran first.
    procedureExecutor.executeProcedure("UnprovisioningProcedure", ids.getFirst());

    var rootAfterUnprovision = entityService.read(ids.getFirst());
    Assertions.assertEquals(
        "UNPROVISIONED", rootAfterUnprovision.getValues().get("provisioningStatus").asText());
    Assertions.assertEquals(
        "Unprovisioning succeeded: all 2 resource(s) unprovisioned",
        rootAfterUnprovision.getValues().get("provisioningResult").asText());

    var unprovisioned = aggregateService.read(ids.getFirst(), true);
    for (var index : List.of(0, 1)) {
      var resource =
          ((AggregateService.AggregateElement)
                  ((AggregateService.Aggregate) unprovisioned.elements().get(index))
                      .elements()
                      .getFirst())
              .entity();
      Assertions.assertEquals(
          "UNPROVISIONED", resource.getValues().get("provisioningStatus").asText());
      Assertions.assertEquals(
          "Unprovisioned entity: " + resource.getId() + " successfully",
          resource.getValues().get("provisioningResult").asText());
    }

    // Dependencies first going in, dependents first coming back out. Getting the second one
    // backwards would tear down a resource while something still depended on it.
    Assertions.assertEquals(
        List.of(
            "provision:S3FolderType",
            "provision:AthenaTableType",
            "unprovision:AthenaTableType",
            "unprovision:S3FolderType"),
        List.copyOf(order));

    // A resource failing must leave the root FAILED, not partially PROVISIONED: swap the Athena
    // factory for a failing one and provision again (in the same test, for the same shared-database
    // reason as the teardown above).
    taskManager.unregisterTaskFactory("AthenaTableType");
    taskManager.registerTaskFactory(
        "AthenaTableType",
        (Entity entity) ->
            new ProvisioningTask(entity, entityService) {
              @Override
              public String provision() {
                throw new ServiceError("Athena is down");
              }

              @Override
              public String unprovision() {
                throw new ServiceError("Athena is down");
              }
            });
    Assertions.assertThrows(
        ServiceError.class, () -> procedureExecutor.executeProcedure(functionName, ids.getFirst()));
    var rootAfterFailure = entityService.read(ids.getFirst());
    Assertions.assertEquals(
        "FAILED", rootAfterFailure.getValues().get("provisioningStatus").asText());
    Assertions.assertNotNull(rootAfterFailure.getValues().get("provisioningResult"));
  }
}
