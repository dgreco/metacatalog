package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.TaskManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Covers the case that made {@link StdoutProvisioningFunctions} a {@link
 * it.davidgreco.metacatalog.functions.DeferredTaskFactoryRegistrar}: entity type names come from
 * configuration, but the types themselves are catalog data loaded later.
 *
 * <p>The properties here mirror the shipped Docker Compose demo, where {@code
 * application-docker.yaml} names these two types and {@code docker/bulk/} creates them only after
 * the application reports healthy. Registering them at startup would now fail — {@code
 * TaskManager.registerTaskFactory} refuses a name no entity type has — so this test exists to catch
 * a regression that would break {@code docker compose up} on a fresh volume rather than in CI.
 */
@SpringBootTest(
    properties = "application.config.provisioning.entity-types=S3FolderType,AthenaTableType")
class StdoutProvisioningRegistrationTests extends CommonServiceTestingSupport {

  public StdoutProvisioningRegistrationTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testConfiguredTypesAreRegisteredOnceTheyExist() {
    var taskManager = getApplicationContext().getBean(TaskManager.class);
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);

    // The context started at all despite both configured types being absent — that is the
    // regression guard. Nothing is registered yet, precisely because nothing could be.
    Assertions.assertFalse(taskManager.isRegistered("S3FolderType"));
    Assertions.assertFalse(taskManager.isRegistered("AthenaTableType"));

    var model =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml");
    bulkLoaderService.bulkModelCreation(model);
    var instances =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml");
    var ids = bulkLoaderService.bulkAggregateCreation(instances);

    // Still nothing: the types exist now, but the registrar only runs before a procedure.
    Assertions.assertFalse(taskManager.isRegistered("S3FolderType"));

    procedureExecutor.executeProcedure("ProvisioningProcedure", ids.getFirst());

    // The run itself is what registered them, and it succeeded — a resource whose type had no
    // factory would have failed with "No factory for name: ...".
    Assertions.assertTrue(taskManager.isRegistered("S3FolderType"));
    Assertions.assertTrue(taskManager.isRegistered("AthenaTableType"));
  }
}
