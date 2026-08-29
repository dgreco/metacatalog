package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;

import it.davidgreco.metacatalog.entity.AccessControl;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.service.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Durations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The access policy end to end: a root declares principals, permissions and grants; an
 * authorization run resolves them per resource, hands each task its own, and records them where a
 * later query can find them.
 *
 * <p>{@link AuthorizationProcedureTests} covers the lifecycle — ordering, statuses, the refusals
 * around missing tasks. This covers what the run now carries, and the two things that would make
 * the feature worthless if they broke quietly: a grant reaching a resource it was not meant to, and
 * a policy typo being applied as though it said nothing.
 */
@Slf4j
@SpringBootTest
class AuthorizationPolicyTests extends CommonServiceTestingSupport {

  private static final String AUTHORIZE = AuthorizationProcedure.class.getSimpleName();
  private static final String REJECT = RejectionProcedure.class.getSimpleName();

  public AuthorizationPolicyTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @BeforeEach
  void loadModel() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    if (!entityTypeService.exists("DataProductType")) {
      getApplicationContext()
          .getBean(BulkLoaderService.class)
          .bulkModelCreation(
              Thread.currentThread()
                  .getContextClassLoader()
                  .getResourceAsStream("bulk/bulk1.yaml"));
    }
  }

  /**
   * The factory registry belongs to a singleton bean that outlives the per-class database reset.
   */
  @AfterEach
  void unregisterTaskFactories() {
    var taskManager = getApplicationContext().getBean(TaskManager.class);
    taskManager.unregisterTaskFactory("S3FolderType");
    taskManager.unregisterTaskFactory("AthenaTableType");
  }

  /** Records, per resource, the grants the task was handed. */
  private record Seen(String entityType, List<AccessPolicy.EffectiveGrant> grants) {}

  private TaskFactory recordingFactory(
      EntityService entityService, Map<String, Seen> seen, List<String> order) {
    return (Entity entity) ->
        new ProvisioningTask(entity, entityService) {
          @Override
          public String provision() {
            return "unused";
          }

          @Override
          public String unprovision() {
            return "unused";
          }

          @Override
          public String authorize() {
            order.add("authorize:" + getEntity().getEntityType().getName());
            seen.put(
                getEntity().getEntityType().getName(),
                new Seen(getEntity().getEntityType().getName(), getAccessGrants()));
            return "granted to " + getAccessGrants().size() + " principal(s)";
          }

          @Override
          public String reject() {
            order.add("reject:" + getEntity().getEntityType().getName());
            seen.put(
                getEntity().getEntityType().getName(),
                new Seen(getEntity().getEntityType().getName(), getAccessGrants()));
            return "withdrawn";
          }
        };
  }

  @Test
  void theRootsPolicyReachesEveryResourceItSelects() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var taskManager = getApplicationContext().getBean(TaskManager.class);

    var ids =
        bulkLoaderService.bulkAggregateCreation(
            Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("bulk/bulk-policy.yaml"));
    awaitMappedResources(aggregateService, ids.getFirst());

    Map<String, Seen> seen = new ConcurrentHashMap<>();
    var order = Collections.synchronizedList(new ArrayList<String>());
    var factory = recordingFactory(entityService, seen, order);
    taskManager.registerTaskFactory("S3FolderType", factory);
    taskManager.registerTaskFactory("AthenaTableType", factory);

    procedureExecutor.executeProcedure(AUTHORIZE, ids.getFirst());

    // The blanket grant reaches both; the entityTypes-scoped one only the S3 folder. A selector
    // that quietly reached everything would pass every other assertion in this file.
    Assertions.assertEquals(
        List.of("crm-pipeline", "sales-analysts"),
        seen.get("S3FolderType").grants().stream().map(g -> g.principal().id()).toList());
    Assertions.assertEquals(
        List.of("sales-analysts"),
        seen.get("AthenaTableType").grants().stream().map(g -> g.principal().id()).toList());
    Assertions.assertEquals(
        List.of("WRITE"), seen.get("S3FolderType").grants().getFirst().permissions());
    Assertions.assertEquals(
        AccessControl.PrincipalType.SERVICE,
        seen.get("S3FolderType").grants().getFirst().principal().type());

    // And what the tasks were handed is what the catalog now says, which is the whole point of
    // recording it: this is the field a "who can read this?" query reads.
    for (var resource : resources(aggregateService, ids.getFirst())) {
      var recorded = resource.getValues().get(AccessControl.EFFECTIVE_GRANTS);
      Assertions.assertNotNull(recorded, resource.getEntityType().getName());
      Assertions.assertEquals(
          seen.get(resource.getEntityType().getName()).grants().size(), recorded.size());
      Assertions.assertEquals(
          "AUTHORIZED", resource.getValues().get("authorizationStatus").asText());
    }

    // Withdrawing leaves the resources holding nothing, recorded as an empty array rather than as
    // an absent field: "decided, nobody" and "never asked" must not read the same.
    procedureExecutor.executeProcedure(REJECT, ids.getFirst());
    for (var resource : resources(aggregateService, ids.getFirst())) {
      Assertions.assertEquals("REJECTED", resource.getValues().get("authorizationStatus").asText());
      Assertions.assertTrue(resource.getValues().get(AccessControl.EFFECTIVE_GRANTS).isEmpty());
    }
    // Reject reads no policy at all, so the tasks it ran saw nothing to apply.
    Assertions.assertEquals(List.of(), seen.get("S3FolderType").grants());
  }

  /**
   * A grant naming a principal one character off the declaration is refused before any task runs,
   * and recorded as {@code FAILED} on the root. Without this the run would succeed, report {@code
   * AUTHORIZED}, and grant nobody anything — the exact silence the model exists to prevent.
   */
  @Test
  void aGrantNamingAnUndeclaredPrincipalFailsTheRunBeforeItStarts() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var procedureExecutor = getApplicationContext().getBean(ProcedureExecutor.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var taskManager = getApplicationContext().getBean(TaskManager.class);

    var ids =
        bulkLoaderService.bulkAggregateCreation(
            Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("bulk/bulk-policy-typo.yaml"));
    awaitMappedResources(aggregateService, ids.getFirst());

    var order = Collections.synchronizedList(new ArrayList<String>());
    var factory = recordingFactory(entityService, new ConcurrentHashMap<>(), order);
    taskManager.registerTaskFactory("S3FolderType", factory);
    taskManager.registerTaskFactory("AthenaTableType", factory);

    var failure =
        Assertions.assertThrows(
            ServiceError.class,
            () -> procedureExecutor.executeProcedure(AUTHORIZE, ids.getFirst()));
    Assertions.assertTrue(failure.getMessage().contains("sales-analyst"), failure.getMessage());
    Assertions.assertEquals(
        List.of(), order, "no task may run once the policy is known to be wrong");

    var root = entityService.read(ids.getFirst());
    Assertions.assertEquals("FAILED", root.getValues().get("authorizationStatus").asText());
    Assertions.assertTrue(
        root.getValues().get("authorizationResult").asText().contains("sales-analyst"),
        root.getValues().get("authorizationResult").asText());

    // Rejecting is deliberately unaffected: it needs no policy to say "no access", which is what
    // keeps it usable as an emergency stop on an aggregate whose policy is broken.
    procedureExecutor.executeProcedure(REJECT, ids.getFirst());
    Assertions.assertEquals(
        "REJECTED",
        entityService.read(ids.getFirst()).getValues().get("authorizationStatus").asText());
  }

  /** Waits until the mapping updater has derived a resource under each of the two output ports. */
  private static void awaitMappedResources(AggregateService aggregateService, String rootId) {
    await()
        .atMost(Durations.ONE_MINUTE)
        .pollDelay(Durations.ONE_SECOND)
        .pollInterval(Durations.ONE_SECOND)
        .until(
            () ->
                aggregateService.read(rootId, true).elements().stream()
                        .filter(AggregateService.Aggregate.class::isInstance)
                        .map(AggregateService.Aggregate.class::cast)
                        .filter(port -> !port.elements().isEmpty())
                        .count()
                    == 2);
  }

  /** The mapped resources of the aggregate, one per output port, in tree order. */
  private static List<Entity> resources(AggregateService aggregateService, String rootId) {
    var aggregate = (AggregateService.Aggregate) aggregateService.read(rootId, true);
    return aggregate.elements().stream()
        .map(AggregateService.Aggregate.class::cast)
        .map(port -> ((AggregateService.AggregateElement) port.elements().getFirst()).entity())
        .toList();
  }
}
