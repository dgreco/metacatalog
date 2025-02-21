package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TaskFactory;
import it.davidgreco.metacatalog.service.TaskManager;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Durations;
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
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);
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
            new ProvisioningTask(entity) {
              @Override
              public Void apply() {
                log.error(
                    "Provisioning task for entity: "
                        + entity
                        + " executed by thread: "
                        + Thread.currentThread().getName());
                return null;
              }

              public String getId() {
                return entity.getId();
              }
            };

    taskManager.registerTaskFactory("S3FolderType", Entity.class, factory);
    taskManager.registerTaskFactory("AthenaTableType", Entity.class, factory);

    procedureExecutor.executeProcedure(functionName, ids.getFirst());
  }
}
