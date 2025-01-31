package it.davidgreco.metacatalog.service;

import static org.awaitility.Awaitility.await;

import org.awaitility.Durations;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BulkLoaderServiceTests extends CommonServiceTests {

  @Test
  void testBulkLoad() throws ServiceError {
    var bulkLoaderService = applicationContext.getBean(BulkLoaderService.class);
    var aggregateService = applicationContext.getBean(AggregateService.class);

    var bulkFileStream1 =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml");

    bulkLoaderService.bulkModelCreation(bulkFileStream1);

    var bulkFileStream2 =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml");

    var ids = bulkLoaderService.bulkAggregateCreation(bulkFileStream2);

    await().pollDelay(Durations.TWO_SECONDS).until(() -> true);

    System.out.println(aggregateService.read(ids.getFirst(), true));
  }
}
