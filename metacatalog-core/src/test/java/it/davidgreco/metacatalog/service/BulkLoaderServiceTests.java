package it.davidgreco.metacatalog.service;

import static org.awaitility.Awaitility.await;

import org.awaitility.Durations;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class BulkLoaderServiceTests extends CommonServiceTestingSupport {

  public BulkLoaderServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testBulkLoad() throws ServiceError {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

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
