package it.davidgreco.metacatalog.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

  /**
   * Verifies that a bulk aggregate YAML referencing an undeclared name in its {@code dependsOn}
   * list is rejected with a {@link ServiceError} containing a descriptive message.
   *
   * <p>The bulk loader resolves each entry in {@code dependsOn} against the names declared earlier
   * in the same YAML document. A missing reference must surface as a clear {@link ServiceError}
   * rather than a {@link NullPointerException} from a null lookup, so that callers get actionable
   * feedback about which name was undeclared.
   */
  @Test
  void bulkAggregateCreationRejectsMissingRef() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);

    // The aggregate YAML references an entity type; it must exist before we can test the ref check.
    entityTypeService.create(
        "MissingRefType",
        java.util.List.of(),
        java.util.Optional.empty(),
        "{ \"type\": \"object\", \"properties\": { \"name\": { \"type\": \"string\" } } }");

    var yaml =
        """
        entityType: "MissingRefType"
        values:
          name: "dp1"
        dependsOn: ["never-declared"]
        """;
    var ex =
        assertThrows(
            ServiceError.class,
            () ->
                bulkLoaderService.bulkAggregateCreation(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    assertTrue(
        ex.getMessage().contains("never declared"),
        "error must mention the undeclared ref; got: " + ex.getMessage());
  }

  /** Malformed top-level section (non-array) must throw ServiceError, not silently skip. */
  @Test
  void bulkModelCreationRejectsNonArraySection() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var yaml =
        """
        Traits: "not-an-array"
        """;
    var ex =
        assertThrows(
            ServiceError.class,
            () ->
                bulkLoaderService.bulkModelCreation(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    assertTrue(
        ex.getMessage().contains("must be a YAML array"),
        "error must mention the section shape; got: " + ex.getMessage());
  }
}
