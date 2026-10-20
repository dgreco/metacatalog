package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.repository.EntityRepository;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.MappingService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TaskManager;
import it.davidgreco.metacatalog.service.TraitService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.awaitility.Durations;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * A past version of a Versionable aggregate is a frozen copy that functions still run on. A run on
 * the copy acts on the copy's own resources and records its results on the copy alone: the live
 * aggregate does not move, and no version of either is recorded.
 */
@SpringBootTest
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class FrozenVersionProvisioningTests extends CommonServiceTestingSupport {

  private static final String NAME_SCHEMA =
      """
      { "type": "object", "properties": { "name": { "type": "string" } } }""";

  FrozenVersionProvisioningTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  private <T> T bean(Class<T> type) {
    return getApplicationContext().getBean(type);
  }

  /**
   * A provisionable, authorizable, versionable root with one port, an intermediate node like the
   * demo's output ports, from which the resource the runs act on is derived.
   */
  private void model() {
    var types = bean(EntityTypeService.class);
    if (types.exists("FpRoot")) return;
    bean(TraitService.class)
        .create("FpPart", Optional.empty(), Optional.of(BuiltInTraits.AGGREGATE_ELEMENT));
    types.create(
        "FpRoot",
        List.of(BuiltInTraits.PROVISIONABLE, BuiltInTraits.AUTHORIZABLE, BuiltInTraits.VERSIONABLE),
        Optional.empty(),
        NAME_SCHEMA);
    types.create(
        "FpPort", List.of(BuiltInTraits.AGGREGATE, "FpPart"), Optional.empty(), NAME_SCHEMA);
    types.create(
        "FpResource",
        List.of(BuiltInTraits.PROVISIONABLE_RESOURCE, BuiltInTraits.AUTHORIZABLE_RESOURCE),
        Optional.empty(),
        """
        { "type": "object", "properties": { "label": { "type": "string" } } }""");
    bean(MappingService.class)
        .create(
            "FpPort",
            "FpResource",
            "{\"label\": \"#source.getValue('$.name').asText() + '-res'\"}",
            List.of());
  }

  private static Entity entity(EntityTypeService types, String type, String values) {
    try {
      return new Entity(types.read(type), new ObjectMapper().readTree(values));
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private Entity resourceLabelled(String label) {
    var query = "$ ? (@.label == \"" + label + "\")";
    await()
        .atMost(Durations.TEN_SECONDS)
        .until(() -> bean(EntityService.class).list("FpResource", query).size() == 1);
    return bean(EntityService.class).list("FpResource", query).getFirst();
  }

  private static Entity copyResource(AggregateService aggregateService, String copyRootId) {
    var copy = aggregateService.read(copyRootId, true);
    var port = (AggregateService.Aggregate) copy.elements().getFirst();
    return port.elements().getFirst().entity();
  }

  @Test
  void provisioningAPastVersionActsOnItsFrozenCopyAlone() {
    model();
    var types = bean(EntityTypeService.class);
    var entityService = bean(EntityService.class);
    var aggregateService = bean(AggregateService.class);
    var taskManager = bean(TaskManager.class);
    var procedureExecutor = bean(ProcedureExecutor.class);

    var live =
        (AggregateService.Aggregate)
            aggregateService.create(
                new AggregateService.Aggregate(
                    entity(types, "FpRoot", "{\"name\":\"fp\"}"),
                    List.of(
                        new AggregateService.Aggregate(
                            entity(types, "FpPort", "{\"name\":\"p\"}"), List.of()))));
    var rootId = live.entity().getId();
    var liveResource = resourceLabelled("p-res");
    entityService.update(live.elements().getFirst().entity().getId(), "{\"name\":\"p2\"}");
    resourceLabelled("p2-res");
    var copyRootId = entityService.listVersions(rootId).getFirst().getFrozenRootId();
    Assertions.assertNotNull(copyRootId, "v1 is frozen once v2 supersedes it");
    var resource = copyResource(aggregateService, copyRootId);
    Assertions.assertEquals("p-res", resource.getValues().get("label").asText());
    var liveVersions = entityService.listVersions(rootId).size();

    var ran = Collections.synchronizedList(new ArrayList<String>());
    taskManager.registerTaskFactory(
        "FpResource",
        entity ->
            new ProvisioningTask(entity, entityService) {
              @Override
              public String provision() {
                ran.add(getEntity().getId());
                return "ok";
              }

              @Override
              public String unprovision() {
                ran.add(getEntity().getId());
                return "ok";
              }
            });
    try {
      procedureExecutor.executeProcedure("ProvisioningProcedure", copyRootId);

      Assertions.assertEquals(List.of(resource.getId()), ran, "the copy's resource, only");
      Assertions.assertEquals("PROVISIONED", status(entityService, copyRootId));
      Assertions.assertEquals("PROVISIONED", status(entityService, resource.getId()));
      Assertions.assertNull(status(entityService, rootId), "the live root does not move");
      Assertions.assertNull(status(entityService, liveResource.getId()));
      Assertions.assertEquals(liveVersions, entityService.listVersions(rootId).size());
      Assertions.assertTrue(entityService.listVersions(copyRootId).isEmpty());

      // While the copy holds what the run built, the aggregate cannot go: deleting it would lose
      // the only record that the run happened.
      Assertions.assertThrows(ServiceError.class, () -> aggregateService.delete(rootId));
      procedureExecutor.executeProcedure("UnprovisioningProcedure", copyRootId);
      Assertions.assertEquals("UNPROVISIONED", status(entityService, copyRootId));
      aggregateService.delete(rootId);
      Assertions.assertFalse(bean(EntityRepository.class).existsById(copyRootId));
    } finally {
      // TaskManager's registry outlives the per-class database reset.
      taskManager.unregisterTaskFactory("FpResource");
    }
  }

  /**
   * Authorization reads the policy from the copy's root — the one that version had — and writes the
   * resolved grants onto the copy's resource next to its status. Both are run results, so the copy
   * accepts them; the live aggregate again does not move.
   */
  @Test
  void authorizingAPastVersionActsOnItsFrozenCopyAlone() {
    model();
    var types = bean(EntityTypeService.class);
    var entityService = bean(EntityService.class);
    var aggregateService = bean(AggregateService.class);
    var taskManager = bean(TaskManager.class);
    var procedureExecutor = bean(ProcedureExecutor.class);

    var live =
        (AggregateService.Aggregate)
            aggregateService.create(
                new AggregateService.Aggregate(
                    entity(
                        types,
                        "FpRoot",
                        """
                        { "name": "fa",
                          "principals": [ { "id": "analysts", "type": "GROUP" } ],
                          "permissions": [ { "name": "READ" } ],
                          "grants": [ { "principals": ["analysts"], "permissions": ["READ"] } ] }"""),
                    List.of(
                        new AggregateService.Aggregate(
                            entity(types, "FpPort", "{\"name\":\"q\"}"), List.of()))));
    var rootId = live.entity().getId();
    var liveResource = resourceLabelled("q-res");
    entityService.update(live.elements().getFirst().entity().getId(), "{\"name\":\"q2\"}");
    resourceLabelled("q2-res");
    var copyRootId = entityService.listVersions(rootId).getFirst().getFrozenRootId();
    var resource = copyResource(aggregateService, copyRootId);
    var liveVersions = entityService.listVersions(rootId).size();

    taskManager.registerTaskFactory(
        "FpResource",
        entity ->
            new ProvisioningTask(entity, entityService) {
              @Override
              public String provision() {
                return "ok";
              }

              @Override
              public String unprovision() {
                return "ok";
              }

              @Override
              public String authorize() {
                return "ok";
              }

              @Override
              public String reject() {
                return "ok";
              }
            });
    try {
      procedureExecutor.executeProcedure(
          ProvisioningTask.Operation.AUTHORIZE.procedureName(), copyRootId);

      Assertions.assertEquals("AUTHORIZED", status(entityService, copyRootId, AUTHORIZATION));
      Assertions.assertEquals("AUTHORIZED", status(entityService, resource.getId(), AUTHORIZATION));
      var grants = entityService.read(resource.getId()).getValues().get("effectiveGrants");
      Assertions.assertTrue(
          grants != null && grants.toString().contains("analysts"),
          "the copy's resource holds the grants its version's policy gave it: " + grants);
      Assertions.assertNull(status(entityService, rootId, AUTHORIZATION));
      Assertions.assertNull(status(entityService, liveResource.getId(), AUTHORIZATION));
      Assertions.assertNull(
          entityService.read(liveResource.getId()).getValues().get("effectiveGrants"));
      Assertions.assertEquals(liveVersions, entityService.listVersions(rootId).size());
      Assertions.assertTrue(entityService.listVersions(copyRootId).isEmpty());

      Assertions.assertThrows(ServiceError.class, () -> aggregateService.delete(rootId));
      procedureExecutor.executeProcedure(
          ProvisioningTask.Operation.REJECT.procedureName(), copyRootId);
      Assertions.assertEquals("REJECTED", status(entityService, copyRootId, AUTHORIZATION));
      aggregateService.delete(rootId);
      Assertions.assertFalse(bean(EntityRepository.class).existsById(copyRootId));
    } finally {
      taskManager.unregisterTaskFactory("FpResource");
    }
  }

  /**
   * Every status a resource's task writes records a version of a versionable root, and every
   * version is followed by a freeze in transactions of its own. Tasks run in parallel, so a large
   * aggregate has many freezes at once; they must neither starve the connection pool nor fail the
   * run.
   */
  @Test
  void aParallelRunOnALargeVersionableAggregateFreezesWithoutStarving() {
    model();
    var types = bean(EntityTypeService.class);
    var entityService = bean(EntityService.class);
    var aggregateService = bean(AggregateService.class);
    var taskManager = bean(TaskManager.class);
    var procedureExecutor = bean(ProcedureExecutor.class);

    var ports = new ArrayList<AggregateService.AggregatePart>();
    for (var i = 0; i < 12; i++)
      ports.add(
          new AggregateService.Aggregate(
              entity(types, "FpPort", "{\"name\":\"big" + i + "\"}"), List.of()));
    var live =
        (AggregateService.Aggregate)
            aggregateService.create(
                new AggregateService.Aggregate(
                    entity(types, "FpRoot", "{\"name\":\"big\"}"), ports));
    var rootId = live.entity().getId();
    for (var i = 0; i < 12; i++) resourceLabelled("big" + i + "-res");
    entityService.update(rootId, "{\"name\":\"big-renamed\"}");

    taskManager.registerTaskFactory(
        "FpResource",
        entity ->
            new ProvisioningTask(entity, entityService) {
              @Override
              public String provision() {
                try {
                  Thread.sleep(200);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return "ok";
              }

              @Override
              public String unprovision() {
                return "ok";
              }
            });
    try {
      var started = System.nanoTime();
      procedureExecutor.executeProcedure("ProvisioningProcedure", rootId);
      var seconds = (System.nanoTime() - started) / 1e9;

      Assertions.assertEquals("PROVISIONED", status(entityService, rootId));
      Assertions.assertTrue(seconds < 20, "the run took " + seconds + "s");
      var versions = entityService.listVersions(rootId);
      // v1 the creation, v2 the rename: two copies. Every version after them only adds run
      // results, so they all share v2's copy.
      var copies =
          versions.subList(0, versions.size() - 1).stream()
              .map(it.davidgreco.metacatalog.entity.EntityVersion::getFrozenRootId)
              .distinct()
              .toList();
      Assertions.assertEquals(2, copies.size(), copies.toString());
      Assertions.assertFalse(copies.contains(null), "every past version has a copy");
    } finally {
      taskManager.unregisterTaskFactory("FpResource");
    }
  }

  private static final String PROVISIONING = "provisioningStatus";

  private static final String AUTHORIZATION = "authorizationStatus";

  private static String status(EntityService entityService, String entityId) {
    return status(entityService, entityId, PROVISIONING);
  }

  private static String status(EntityService entityService, String entityId, String field) {
    var status = entityService.read(entityId).getValues().get(field);
    return status == null ? null : status.asText();
  }
}
