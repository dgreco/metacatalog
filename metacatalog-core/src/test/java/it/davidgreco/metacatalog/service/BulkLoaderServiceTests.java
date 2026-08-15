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
  void testBulkLoad() {
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
  void bulkAggregateCreationRejectsMissingRef() {
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

  /**
   * Replays the docker demo flow against the real files under {@code docker/bulk}: load the model,
   * load the aggregate, let the mapping engine derive the resources, delete the aggregate from the
   * catalog (as the UI does), and load the aggregate file again. The reload is what the compose
   * bulk-loader does after a restart when the demo aggregate was deleted, and must succeed.
   */
  @Test
  void testDockerDemoFilesReloadAfterAggregateDeletion() throws java.io.IOException {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var bulkDir = java.nio.file.Path.of("..", "docker", "bulk");
    // The same model also ships as the bulk1.yaml test fixture, so another test in this class may
    // have loaded it already — mirror the compose loader and only load it when it is missing.
    var traitService = getApplicationContext().getBean(TraitService.class);
    boolean modelLoaded;
    try {
      traitService.read("WithName");
      modelLoaded = true;
    } catch (NotFoundException e) {
      modelLoaded = false;
    }
    if (!modelLoaded) {
      try (var model = java.nio.file.Files.newInputStream(bulkDir.resolve("bulk-model.yaml"))) {
        bulkLoaderService.bulkModelCreation(model);
      }
    }
    var s3Before = entityService.list("S3FolderType", "").size();
    var athenaBefore = entityService.list("AthenaTableType", "").size();
    java.util.List<String> ids;
    try (var instances =
        java.nio.file.Files.newInputStream(bulkDir.resolve("bulk-instances.yaml"))) {
      ids = bulkLoaderService.bulkAggregateCreation(instances);
    }

    // The scheduled mapping engine derives the S3 folder and the Athena table asynchronously;
    // the aggregate delete below must see them to remove them with the tree. Counted as deltas:
    // other tests' aggregates may already have derived instances of the same types.
    await()
        .atMost(java.time.Duration.ofSeconds(30))
        .until(
            () ->
                entityService.list("S3FolderType", "").size() == s3Before + 1
                    && entityService.list("AthenaTableType", "").size() == athenaBefore + 1);

    aggregateService.delete(ids.getFirst());

    // Mimic the compose bulk-loader's read probe before reloading: list entities of the root type,
    // then reload the same aggregate document. The reload must succeed after the delete above.
    entityService.list("DataProductType", "");

    try (var instances =
        java.nio.file.Files.newInputStream(bulkDir.resolve("bulk-instances.yaml"))) {
      ids = bulkLoaderService.bulkAggregateCreation(instances);
    }
    assertTrue(ids.size() == 1, "the demo aggregate must load again after being deleted");
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
