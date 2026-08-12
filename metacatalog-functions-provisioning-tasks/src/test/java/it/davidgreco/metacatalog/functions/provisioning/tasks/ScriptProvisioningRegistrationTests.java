package it.davidgreco.metacatalog.functions.provisioning.tasks;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.awaitility.Durations;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Covers the per-type task association: the same aggregate is provisioned with its {@code
 * S3FolderType} resource handled by the stdout task and its {@code AthenaTableType} resource
 * handled by the script task, and each resource's {@code provisioningResult} shows which task ran.
 *
 * <p>The script is {@code src/test/resources/scripts/echo-values.sh}, which prints the operation
 * and the JSON it received on standard input — so the assertions also cover the contract of {@link
 * ScriptProvisioningTask}: operation as {@code $1}, values on stdin, output recorded in the result.
 */
@SpringBootTest(
    properties = {
      "application.config.provisioning.tasks.stdout.entity-types=S3FolderType",
      "application.config.provisioning.tasks.script.entity-types=AthenaTableType"
    })
class ScriptProvisioningRegistrationTests extends CommonServiceTestingSupport {

  @DynamicPropertySource
  static void scriptPath(DynamicPropertyRegistry registry) throws Exception {
    var script =
        Paths.get(
            ScriptProvisioningRegistrationTests.class
                .getClassLoader()
                .getResource("scripts/echo-values.sh")
                .toURI());
    registry.add(
        "application.config.provisioning.tasks.script.path",
        () -> script.toAbsolutePath().toString());
  }

  public ScriptProvisioningRegistrationTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testEachTypeIsProvisionedByItsConfiguredTask() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);

    var model =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml");
    bulkLoaderService.bulkModelCreation(model);
    var instances =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml");
    var ids = bulkLoaderService.bulkAggregateCreation(instances);

    // The two resources are derived asynchronously by the mapping engine; wait until both exist
    // (root + two ports + two mapped resources = 5 nodes) before provisioning.
    await()
        .atMost(Durations.ONE_MINUTE)
        .pollDelay(Durations.ONE_SECOND)
        .until(() -> countNodes(aggregateService.read(ids.getFirst(), true)) == 5);

    procedureExecutor.executeProcedure("ProvisioningProcedure", ids.getFirst());

    var provisioned = provisioningResults(aggregateService.read(ids.getFirst(), true));
    Assertions.assertEquals(2, provisioned.size());
    for (var values : provisioned) {
      Assertions.assertEquals("PROVISIONED", values.get("provisioningStatus").asText());
    }
    var stdoutResult = resultContaining(provisioned, "(stdout task)");
    Assertions.assertTrue(stdoutResult.contains("S3FolderType"));
    var scriptResult = resultContaining(provisioned, "(script task)");
    Assertions.assertTrue(scriptResult.contains("AthenaTableType"));
    // The script echoed the operation and the JSON it was handed on stdin.
    Assertions.assertTrue(scriptResult.contains("[script:provision]"));
    Assertions.assertTrue(scriptResult.contains("{"));

    procedureExecutor.executeProcedure("UnprovisioningProcedure", ids.getFirst());

    var unprovisioned = provisioningResults(aggregateService.read(ids.getFirst(), true));
    Assertions.assertEquals(2, unprovisioned.size());
    for (var values : unprovisioned) {
      Assertions.assertEquals("UNPROVISIONED", values.get("provisioningStatus").asText());
    }
    Assertions.assertTrue(
        resultContaining(unprovisioned, "(script task)").contains("[script:unprovision]"));
  }

  /** The single result whose {@code provisioningResult} contains the marker. */
  private String resultContaining(List<JsonNode> provisioned, String marker) {
    var matches =
        provisioned.stream()
            .map(values -> values.get("provisioningResult").asText())
            .filter(result -> result.contains(marker))
            .toList();
    Assertions.assertEquals(1, matches.size(), "expected exactly one result containing " + marker);
    return matches.getFirst();
  }

  private int countNodes(AggregateService.AggregatePart part) {
    return switch (part) {
      case AggregateService.AggregateElement ignored -> 1;
      case AggregateService.Aggregate(
              Entity ignored,
              var ignoredRelation,
              List<AggregateService.AggregatePart> elements) ->
          1 + elements.stream().mapToInt(this::countNodes).sum();
    };
  }

  /** The values of every node carrying a provisioning status, in tree order. */
  private List<JsonNode> provisioningResults(AggregateService.AggregatePart part) {
    var results = new ArrayList<JsonNode>();
    switch (part) {
      case AggregateService.AggregateElement(Entity entity, var ignored) -> {
        if (entity.getValues().has("provisioningStatus")) {
          results.add(entity.getValues());
        }
      }
      case AggregateService.Aggregate(
              Entity entity,
              var ignoredRelation,
              List<AggregateService.AggregatePart> elements) -> {
        if (entity.getValues().has("provisioningStatus")) {
          results.add(entity.getValues());
        }
        elements.forEach(element -> results.addAll(provisioningResults(element)));
      }
    }
    return results;
  }
}
