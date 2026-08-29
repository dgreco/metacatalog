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
 * Pins how the two shipped tasks are handed an access decision: {@link ScriptProvisioningTask}
 * through {@link ScriptProvisioningTask#ACCESS_ENVIRONMENT_VARIABLE}, {@link
 * StdoutProvisioningTask} by folding it into the recorded result.
 *
 * <p>That environment variable is a contract with shell scripts nothing else in the build compiles
 * against — the demo's provisioning scripts are the only real consumer — so it is exactly the kind
 * of thing that otherwise stays broken until someone runs {@code docker compose up}. The script
 * here echoes it back, so a rename or a missed {@code authorize} path fails here instead.
 */
@SpringBootTest(
    properties = {
      "application.config.provisioning.tasks.stdout.entity-types=S3FolderType",
      "application.config.provisioning.tasks.script.entity-types=AthenaTableType"
    })
class ScriptAuthorizationTests extends CommonServiceTestingSupport {

  @DynamicPropertySource
  static void scriptPath(DynamicPropertyRegistry registry) throws Exception {
    var script =
        Paths.get(
            ScriptAuthorizationTests.class
                .getClassLoader()
                .getResource("scripts/echo-values.sh")
                .toURI());
    registry.add(
        "application.config.provisioning.tasks.script.path",
        () -> script.toAbsolutePath().toString());
  }

  public ScriptAuthorizationTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void theResolvedGrantsReachBothShippedTasks() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);

    bulkLoaderService.bulkModelCreation(
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml"));
    var ids =
        bulkLoaderService.bulkAggregateCreation(
            Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk3.yaml"));

    // The two resources are derived asynchronously by the mapping engine; wait until both exist
    // (root + two ports + two mapped resources = 5 nodes) before authorizing.
    await()
        .atMost(Durations.ONE_MINUTE)
        .pollDelay(Durations.ONE_SECOND)
        .until(() -> countNodes(aggregateService.read(ids.getFirst(), true)) == 5);

    procedureExecutor.executeProcedure("AuthorizationProcedure", ids.getFirst());

    var authorized = authorizationResults(aggregateService.read(ids.getFirst(), true));
    Assertions.assertEquals(3, authorized.size());
    for (var values : authorized) {
      Assertions.assertEquals("AUTHORIZED", values.get("authorizationStatus").asText());
    }

    // The script saw the grants in its environment, as the same JSON the catalog records.
    var scriptResult = resultContaining(authorized, "(script task)");
    Assertions.assertTrue(scriptResult.contains("[script:authorize]"), scriptResult);
    Assertions.assertTrue(
        scriptResult.contains("\"id\":\"sales-analysts\""),
        "the script should have been handed the grants: " + scriptResult);
    Assertions.assertTrue(scriptResult.contains("\"READ\""), scriptResult);

    // And the stdout task named the same grant in what it recorded.
    var stdoutResult = resultContaining(authorized, "(stdout task)");
    Assertions.assertTrue(stdoutResult.contains("sales-analysts(GROUP)=[READ]"), stdoutResult);

    // Every resource carries what was granted there, which is what a later query reads.
    for (var values : authorized) {
      if (!values.has("effectiveGrants")) continue;
      Assertions.assertEquals(
          "sales-analysts",
          values.get("effectiveGrants").get(0).get("principal").get("id").asText());
    }

    // Rejecting resolves no policy, so the variable is absent rather than an empty array — the
    // distinction the script echoes back.
    procedureExecutor.executeProcedure("RejectionProcedure", ids.getFirst());
    var rejected = authorizationResults(aggregateService.read(ids.getFirst(), true));
    for (var values : rejected) {
      Assertions.assertEquals("REJECTED", values.get("authorizationStatus").asText());
    }
    Assertions.assertTrue(
        resultContaining(rejected, "(script task)").contains("access=<unset>"),
        resultContaining(rejected, "(script task)"));
    // ...and the resources record that nobody holds anything, explicitly.
    for (var values : rejected) {
      if (!values.has("effectiveGrants")) continue;
      Assertions.assertTrue(values.get("effectiveGrants").isEmpty(), values.toString());
    }
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

  /** The single result whose {@code authorizationResult} contains the marker. */
  private String resultContaining(List<JsonNode> results, String marker) {
    var matches =
        results.stream()
            .map(values -> values.get("authorizationResult").asText())
            .filter(result -> result.contains(marker))
            .toList();
    Assertions.assertEquals(1, matches.size(), "expected exactly one result containing " + marker);
    return matches.getFirst();
  }

  /** The values of every node carrying an authorization status, in tree order. */
  private List<JsonNode> authorizationResults(AggregateService.AggregatePart part) {
    var results = new ArrayList<JsonNode>();
    switch (part) {
      case AggregateService.AggregateElement(Entity entity, var ignored) -> {
        if (entity.getValues().has("authorizationStatus")) results.add(entity.getValues());
      }
      case AggregateService.Aggregate(
              Entity entity,
              var ignoredRelation,
              List<AggregateService.AggregatePart> elements) -> {
        if (entity.getValues().has("authorizationStatus")) results.add(entity.getValues());
        elements.forEach(element -> results.addAll(authorizationResults(element)));
      }
    }
    return results;
  }
}
