package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.entity.EntityLifeCycleEvent.ENTITY_SOURCE_CREATED;
import static it.davidgreco.metacatalog.entity.EntityLifeCycleEvent.STATUS_FAILED;
import static it.davidgreco.metacatalog.entity.EntityLifeCycleEvent.STATUS_PENDING;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.repository.EntityLifeCycleEventRepository;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.TaskFactory;
import it.davidgreco.metacatalog.service.TaskManager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * On-demand stress test for the automatic mapping engine and the provisioning procedure.
 *
 * <p>It creates hundreds of data-product aggregates (the model of {@code bulk/bulk1.yaml}: a data
 * product with a file-based output port and a table-based output port depending on it), waits for
 * the scheduled {@code MappingUpdaterService} to derive every mapped resource ({@code S3FolderType}
 * / {@code AthenaTableType}) and verifies the mapped values entity by entity, then provisions every
 * aggregate and verifies each resource was provisioned exactly once, in dependency order, with the
 * outcome recorded on the entity.
 *
 * <p>Tagged {@code stress}, so a plain {@code mvn test} skips it. Run it with:
 *
 * <pre>{@code
 * mvn test -Pstress-tests -pl metacatalog-functions
 * }</pre>
 *
 * The aggregate count defaults to {@value #DEFAULT_AGGREGATES} and can be overridden with {@code
 * -Dstress.aggregates=500}.
 */
@Slf4j
@Tag("stress")
@SpringBootTest
class DataProductStressTest extends CommonServiceTestingSupport {

  private static final int DEFAULT_AGGREGATES = 300;

  public DataProductStressTest(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  private record DataProduct(String aggregateId, String name) {}

  @Test
  void stressMappingAndProvisioning() {
    var aggregates = Integer.getInteger("stress.aggregates", DEFAULT_AGGREGATES);
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var taskManager = getApplicationContext().getBean(TaskManager.class);
    var events = getApplicationContext().getBean(EntityLifeCycleEventRepository.class);

    var model =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml");
    bulkLoaderService.bulkModelCreation(model);

    // The mapping scheduler ticks throughout, so events are being drained while we are still
    // creating aggregates — creation and mapping load the system at the same time.
    log.info("Creating {} data-product aggregates", aggregates);
    var dataProducts = new ArrayList<DataProduct>(aggregates);
    for (int i = 0; i < aggregates; i++) {
      var name = "dp-%04d".formatted(i);
      var ids =
          bulkLoaderService.bulkAggregateCreation(
              new ByteArrayInputStream(aggregateYaml(name).getBytes(StandardCharsets.UTF_8)));
      dataProducts.add(new DataProduct(ids.getFirst(), name));
      if ((i + 1) % 50 == 0) {
        log.info("Created {}/{} aggregates", i + 1, aggregates);
      }
    }

    // Every output port must have been mapped to its resource, with no event left behind: one
    // FAILED SOURCE_CREATED event means a mapping was silently dropped, and more mapped
    // instances than aggregates means one was derived twice.
    await()
        .atMost(Duration.ofMinutes(10))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              assertEquals(
                  List.of(),
                  events.findByEventTypeAndEventStatus(ENTITY_SOURCE_CREATED, STATUS_FAILED),
                  "no SOURCE_CREATED event may fail");
              assertEquals(
                  aggregates,
                  entityService.list("S3FolderType", "").size(),
                  "every file-based output port must be mapped to exactly one S3 folder");
              assertEquals(
                  aggregates,
                  entityService.list("AthenaTableType", "").size(),
                  "every table-based output port must be mapped to exactly one Athena table");
              assertTrue(
                  events
                      .findByEventTypeAndEventStatus(ENTITY_SOURCE_CREATED, STATUS_PENDING)
                      .isEmpty(),
                  "no SOURCE_CREATED event may be left pending");
            });
    log.info("All {} aggregates mapped", aggregates);

    // Per-aggregate verification of the mapped values, remembering each aggregate's resource ids
    // for the ordering assertion after provisioning.
    var s3IdByAggregate = new ConcurrentHashMap<String, String>();
    var athenaIdByAggregate = new ConcurrentHashMap<String, String>();
    for (var dataProduct : dataProducts) {
      var aggregate = aggregateService.read(dataProduct.aggregateId(), true);
      var s3Folder = mappedResource(aggregate, "FileBasedOutputPortType");
      var athenaTable = mappedResource(aggregate, "TableBasedOutputPortType");

      var expectedPath = "/root/" + dataProduct.name() + "/" + dataProduct.name() + "-op1";
      assertEquals("S3FolderType", s3Folder.getEntityType().getName());
      assertEquals("my-bucket", s3Folder.getValues().get("bucket").asText());
      assertEquals(expectedPath, s3Folder.getValues().get("path").asText());

      assertEquals("AthenaTableType", athenaTable.getEntityType().getName());
      assertEquals(dataProduct.name(), athenaTable.getValues().get("database").asText());
      assertEquals(dataProduct.name() + "-op2", athenaTable.getValues().get("table").asText());
      assertEquals(expectedPath, athenaTable.getValues().get("dataPath").asText());

      s3IdByAggregate.put(dataProduct.aggregateId(), s3Folder.getId());
      athenaIdByAggregate.put(dataProduct.aggregateId(), athenaTable.getId());
    }
    log.info("Mapped values verified for all {} aggregates", aggregates);

    // Count every provision() invocation per entity id, and record the global order the tasks
    // ran in — executions are sequential, so within one aggregate the recorded order is the
    // schedule's order.
    var provisionCounts = new ConcurrentHashMap<String, Integer>();
    var provisionOrder = Collections.synchronizedList(new ArrayList<String>());
    TaskFactory factory =
        (Entity entity) ->
            new ProvisioningTask(entity, entityService) {
              @Override
              public String provision() {
                provisionCounts.merge(getEntity().getId(), 1, Integer::sum);
                provisionOrder.add(getEntity().getId());
                return "Provisioned entity: " + getEntity().getId() + " successfully";
              }

              @Override
              public String unprovision() {
                throw new UnsupportedOperationException("this test only provisions");
              }
            };
    taskManager.registerTaskFactory("S3FolderType", factory);
    taskManager.registerTaskFactory("AthenaTableType", factory);

    log.info("Provisioning {} aggregates", aggregates);
    for (int i = 0; i < dataProducts.size(); i++) {
      // Synchronous: returns only when the whole aggregate is provisioned, throws on any failure.
      procedureExecutor.executeProcedure(
          "ProvisioningProcedure", dataProducts.get(i).aggregateId());
      if ((i + 1) % 50 == 0) {
        log.info("Provisioned {}/{} aggregates", i + 1, aggregates);
      }
    }

    // Exactly one provision() per resource — no resource skipped, none provisioned twice.
    assertEquals(2 * aggregates, provisionOrder.size());
    for (var dataProduct : dataProducts) {
      var s3Id = s3IdByAggregate.get(dataProduct.aggregateId());
      var athenaId = athenaIdByAggregate.get(dataProduct.aggregateId());
      assertEquals(1, provisionCounts.get(s3Id), "S3 folder of " + dataProduct.name());
      assertEquals(1, provisionCounts.get(athenaId), "Athena table of " + dataProduct.name());
      // The Athena table reads the S3 folder, so the folder must have been created first.
      assertTrue(
          provisionOrder.indexOf(s3Id) < provisionOrder.indexOf(athenaId),
          "S3 folder of " + dataProduct.name() + " must be provisioned before its Athena table");
    }

    // The outcome must also be recorded on every resource entity.
    await()
        .atMost(Duration.ofMinutes(2))
        .untilAsserted(
            () -> {
              for (var typeName : List.of("S3FolderType", "AthenaTableType")) {
                for (var resource : entityService.list(typeName, "")) {
                  assertProvisioned(resource);
                }
              }
            });
    log.info("All {} resources provisioned exactly once, in dependency order", 2 * aggregates);
  }

  private static void assertProvisioned(Entity resource) {
    var status = resource.getValues().get("provisioningStatus");
    assertNotNull(status, "resource " + resource.getId() + " has no provisioningStatus");
    assertEquals("PROVISIONED", status.asText(), "resource " + resource.getId());
    assertEquals(
        "Provisioned entity: " + resource.getId() + " successfully",
        resource.getValues().get("provisioningResult").asText());
  }

  /** The single mapped resource of the aggregate's output port with the given entity type. */
  private static Entity mappedResource(
      AggregateService.Aggregate aggregate, String outputPortTypeName) {
    var outputPort =
        aggregate.elements().stream()
            .map(AggregateService.Aggregate.class::cast)
            .filter(part -> outputPortTypeName.equals(part.entity().getEntityType().getName()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "aggregate "
                            + aggregate.entity().getId()
                            + " has no "
                            + outputPortTypeName
                            + " part"));
    var mapped =
        outputPort.elements().stream()
            .filter(AggregateService.AggregateElement.class::isInstance)
            .map(AggregateService.AggregateElement.class::cast)
            .map(AggregateService.AggregateElement::entity)
            .toList();
    assertEquals(
        1,
        mapped.size(),
        "output port "
            + outputPort.entity().getId()
            + " must have exactly one mapped resource, found "
            + mapped.size());
    return mapped.getFirst();
  }

  private static String aggregateYaml(String dataProductName) {
    return """
        entityType: "DataProductType"
        values:
          name: "%1$s"

        parts:

        - ref: "op1"
          entityType: "FileBasedOutputPortType"
          values:
            name: "%1$s-op1"

        - ref: "op2"
          entityType: "TableBasedOutputPortType"
          values:
            name: "%1$s-op2"
          dependsOn:
            - "op1"
        """
        .formatted(dataProductName);
  }
}
