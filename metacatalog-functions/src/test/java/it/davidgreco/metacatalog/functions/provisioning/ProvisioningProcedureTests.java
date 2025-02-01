package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;

import it.davidgreco.metacatalog.functions.CommonServiceTests;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import java.util.function.Consumer;
import org.awaitility.Durations;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ProvisioningProcedureTests extends CommonServiceTests {

  @Test
  void testProvisioningProcedure() throws ServiceError, ClassNotFoundException {
    var entityService = applicationContext.getBean(EntityService.class);
    var bulkLoaderService = applicationContext.getBean(BulkLoaderService.class);

    var bulkFileStream1 =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml");

    bulkLoaderService.bulkModelCreation(bulkFileStream1);

    var bulkFileStream2 =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml");

    var ids = bulkLoaderService.bulkAggregateCreation(bulkFileStream2);

    await().pollDelay(Durations.TWO_SECONDS).until(() -> true);

    var functionName = "ProvisioningProcedure";

    var clazz =
        Thread.currentThread()
            .getContextClassLoader()
            .loadClass("it.davidgreco.metacatalog.functions.provisioning." + functionName);

    var provisioningProcedure = (Consumer) applicationContext.getBean(clazz);

    provisioningProcedure.accept(entityService.read(ids.getFirst()));
  }
}
