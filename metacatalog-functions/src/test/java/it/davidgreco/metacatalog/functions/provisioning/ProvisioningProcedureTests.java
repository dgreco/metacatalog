package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.service.*;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Durations;
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

  @Test
  void testProvisioningProcedure() throws ServiceError {
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

    TaskFactory<Entity> factory =
        (Entity entity) ->
            new ProvisioningTask(entity, entityService) {
              @Override
              public String provision() {
                return "Provisioned entity: " + getEntity().getId() + " successfully";
              }
            };

    taskManager.registerTaskFactory("S3FolderType", Entity.class, factory);
    taskManager.registerTaskFactory("AthenaTableType", Entity.class, factory);

    procedureExecutor.executeProcedure(functionName, ids.getFirst());

    await().pollDelay(Durations.TWO_SECONDS).until(() -> true);

    var aggr = aggregateService.read(ids.getFirst(), true);
    Assertions.assertEquals(
        "PROVISIONED",
        ((AggregateService.AggregateElement)
                ((AggregateService.Aggregate) aggr.elements().get(0)).elements().getFirst())
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
  }
}
