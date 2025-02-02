package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;

import it.davidgreco.metacatalog.functions.CommonServiceTests;
import it.davidgreco.metacatalog.functions.common.ProcedureExecutor;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.ServiceError;
import org.awaitility.Durations;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ProvisioningProcedureTests extends CommonServiceTests {

  @Test
  void testProvisioningProcedure() throws ServiceError {
    var bulkLoaderService = applicationContext.getBean(BulkLoaderService.class);
    var procedureExecutor = applicationContext.getBean(ProcedureExecutor.class);

    var bulkFileStream1 =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml");

    bulkLoaderService.bulkModelCreation(bulkFileStream1);

    var bulkFileStream2 =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml");

    var ids = bulkLoaderService.bulkAggregateCreation(bulkFileStream2);

    await().pollDelay(Durations.TWO_SECONDS).until(() -> true);

    var functionName = "ProvisioningProcedure";

    procedureExecutor.executeProcedure(functionName, ids.get(0));
  }
}
