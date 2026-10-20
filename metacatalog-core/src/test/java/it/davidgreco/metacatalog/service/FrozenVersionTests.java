package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityVersion;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.repository.EntityRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * A past version of a {@link BuiltInTraits#VERSIONABLE} aggregate root is frozen into a real,
 * runnable copy — root, parts and re-derived mapped instances — that functions can run on and
 * nothing else can change; versions can be deleted one at a time or all at once.
 *
 * <p>The database is reset per class, not per test, so every test builds an aggregate of its own
 * from the model {@link #model()} creates once.
 */
@SpringBootTest
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class FrozenVersionTests extends CommonServiceTestingSupport {

  private static final String NAME_SCHEMA =
      """
      { "type": "object", "properties": { "name": { "type": "string" } } }""";

  FrozenVersionTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  private EntityService entityService() {
    return getApplicationContext().getBean(EntityService.class);
  }

  private EntityTypeService entityTypeService() {
    return getApplicationContext().getBean(EntityTypeService.class);
  }

  private AggregateService aggregateService() {
    return getApplicationContext().getBean(AggregateService.class);
  }

  private MappingService mappingService() {
    return getApplicationContext().getBean(MappingService.class);
  }

  private JsonNode json(String text) {
    try {
      return new ObjectMapper().readTree(text);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * A provisionable, versionable root; a mapped part whose derived resource is provisionable and is
   * derived through a path back to the root, so it can only be re-derived inside a copy whose links
   * were replicated; and a plain part depending on the mapped one.
   */
  private void model() {
    if (entityTypeService().exists("FvRoot")) return;
    var traits = getApplicationContext().getBean(TraitService.class);
    traits.create("FvPartTrait", Optional.empty(), Optional.of(BuiltInTraits.AGGREGATE_ELEMENT));
    traits.link("FvPartTrait", DEPENDS_ON, "FvPartTrait");
    entityTypeService()
        .create(
            "FvRoot",
            List.of(BuiltInTraits.PROVISIONABLE, BuiltInTraits.VERSIONABLE),
            Optional.empty(),
            NAME_SCHEMA);
    // An intermediate node, like the demo's output ports: an aggregate read includes the mapped
    // instances of Aggregate carriers only, and provisioning finds its resources through that read.
    entityTypeService()
        .create(
            "FvMapped",
            List.of(BuiltInTraits.AGGREGATE, "FvPartTrait"),
            Optional.empty(),
            NAME_SCHEMA);
    entityTypeService().create("FvPlain", List.of("FvPartTrait"), Optional.empty(), NAME_SCHEMA);
    entityTypeService()
        .create(
            "FvResource",
            List.of(BuiltInTraits.PROVISIONABLE_RESOURCE),
            Optional.empty(),
            """
            { "type": "object", "properties": { "label": { "type": "string" } } }""");
    mappingService()
        .create(
            "FvMapped",
            "FvResource",
            "{\"label\": \"#owner.getValue('$.name').asText() + '/' +"
                + " #source.getValue('$.name').asText()\"}",
            List.of(new MappingEntityTypeRelationship.EntityPathReference("owner", "HAS_PART{$}")));
  }

  /** Creates root(name) with a mapped part and a plain part depending on it, and its resource. */
  private AggregateService.Aggregate aggregate(String name) {
    model();
    var root = new Entity(entityTypeService().read("FvRoot"), json("{\"name\":\"" + name + "\"}"));
    var mapped = new Entity(entityTypeService().read("FvMapped"), json("{\"name\":\"m\"}"));
    var plain = new Entity(entityTypeService().read("FvPlain"), json("{\"name\":\"p\"}"));
    var aggregate =
        (AggregateService.Aggregate)
            aggregateService()
                .create(
                    new AggregateService.Aggregate(
                        root,
                        List.of(
                            new AggregateService.AggregateElement(mapped),
                            new AggregateService.AggregateElement(plain, List.of(mapped)))));
    awaitResource(name + "/m");
    return aggregate;
  }

  private Entity awaitResource(String label) {
    var query = "$ ? (@.label == \"" + label + "\")";
    Awaitility.await()
        .atMost(Durations.TEN_SECONDS)
        .until(() -> entityService().list("FvResource", query).size() == 1);
    return entityService().list("FvResource", query).getFirst();
  }

  private static String rootId(AggregateService.Aggregate aggregate) {
    return aggregate.entity().getId();
  }

  private static String mappedPartId(AggregateService.Aggregate aggregate) {
    return aggregate.elements().get(0).entity().getId();
  }

  private List<EntityVersion> versions(AggregateService.Aggregate aggregate) {
    return entityService().listVersions(rootId(aggregate));
  }

  /** Records v2 by renaming the mapped part, which freezes v1. */
  private String freezeFirstVersion(AggregateService.Aggregate aggregate) {
    entityService().update(mappedPartId(aggregate), "{\"name\":\"m2\"}");
    var first = versions(aggregate).getFirst();
    assertNotNull(first.getFrozenRootId(), "v1 is frozen once v2 is recorded");
    return first.getFrozenRootId();
  }

  /** Every entity of an aggregate read, at any depth, mapped instances included. */
  private static List<Entity> members(AggregateService.AggregatePart part) {
    var members = new java.util.ArrayList<Entity>();
    members.add(part.entity());
    if (part instanceof AggregateService.Aggregate aggregate)
      for (var element : aggregate.elements()) members.addAll(members(element));
    return members;
  }

  private Entity copyMember(String copyRootId, java.util.function.Predicate<Entity> which) {
    return members(aggregateService().read(copyRootId, true)).stream()
        .filter(which)
        .findFirst()
        .orElseThrow();
  }

  private static java.util.function.Predicate<Entity> named(String name) {
    return e -> e.getValues().path("name").asText().equals(name);
  }

  private static java.util.function.Predicate<Entity> ofType(String type) {
    return e -> e.getEntityType().getName().equals(type);
  }

  @Test
  void recordingANewVersionFreezesThePreviousOneIntoARunnableCopy() {
    var live = aggregate("frozen");
    var copyRootId = freezeFirstVersion(live);

    assertNull(versions(live).getLast().getFrozenRootId(), "the current version is the live one");
    var copyRoot = entityService().read(copyRootId);
    assertEquals(rootId(live), copyRoot.getFrozenVersionOf());
    assertEquals(1, copyRoot.getFrozenVersion());
    assertEquals(json("{\"name\":\"frozen\"}"), copyRoot.getValues());

    // The copy is a whole aggregate of its own: v1's parts, their link, and a resource re-derived
    // inside the copy (its label comes from the copy's root through the copy's HAS_PART link).
    var members = members(aggregateService().read(copyRootId, true));
    for (var member : members) {
      assertEquals(rootId(live), member.getFrozenVersionOf());
      assertEquals(1, member.getFrozenVersion());
    }
    var mappedCopy = copyMember(copyRootId, named("m"));
    var plainCopy = copyMember(copyRootId, named("p"));
    var resource = copyMember(copyRootId, ofType("FvResource"));
    assertEquals("frozen/m", resource.getValues().get("label").asText());
    assertEquals(
        List.of(mappedCopy.getId()),
        entityService().linked(plainCopy.getId(), DEPENDS_ON).stream().map(Entity::getId).toList());
  }

  @Test
  void runResultsAreNotCopiedAndStatusOnlyVersionsShareACopy() {
    var live = aggregate("shared");
    entityService()
        .updateValues(
            rootId(live),
            "{\"name\":\"shared\",\"provisioningStatus\":\"PROVISIONED\",\"provisioningResult\":\"ok\"}");
    var v1Copy = freezeFirstVersionAfterStatus(live);
    entityService()
        .updateValues(
            rootId(live),
            "{\"name\":\"shared\",\"provisioningStatus\":\"UNPROVISIONED\",\"provisioningResult\":\"ok\"}");
    entityService().updateValues(rootId(live), "{\"name\":\"shared\"}");

    var versions = versions(live);
    // v1 = creation, v2 = PROVISIONED, v3 = UNPROVISIONED, v4 = plain again: v1 and v2 differ only
    // in run results, so they share a copy, and so do v3.
    assertEquals(List.of(1, 2, 3, 4), versions.stream().map(EntityVersion::getVersion).toList());
    assertEquals(v1Copy, versions.get(1).getFrozenRootId());
    assertEquals(v1Copy, versions.get(2).getFrozenRootId());
    assertFalse(
        entityService().read(v1Copy).getValues().has("provisioningStatus"),
        "a copy starts with no run results of its own");
  }

  private String freezeFirstVersionAfterStatus(AggregateService.Aggregate live) {
    var first = versions(live).getFirst();
    assertNotNull(first.getFrozenRootId());
    return first.getFrozenRootId();
  }

  @Test
  void copiesAreHiddenFromListsButReadableById() {
    var live = aggregate("hidden");
    var copyRootId = freezeFirstVersion(live);

    assertTrue(
        entityService().list("FvRoot", "").stream().noneMatch(e -> e.getId().equals(copyRootId)));
    assertTrue(entityService().listAll().stream().noneMatch(e -> e.getFrozenVersionOf() != null));
    assertTrue(
        entityService().listAllRelationships().stream()
            .noneMatch(
                r ->
                    r.getSource().getFrozenVersionOf() != null
                        || r.getTarget().getFrozenVersionOf() != null));
    assertTrue(
        mappingService().listAllEntityRelationships().stream()
            .noneMatch(r -> r.getSource().getFrozenVersionOf() != null));
    assertEquals(copyRootId, entityService().read(copyRootId).getId());
  }

  @Test
  void aCopyAcceptsRunResultsAndNothingElse() {
    var live = aggregate("locked");
    var copyRootId = freezeFirstVersion(live);
    var copyPart = copyMember(copyRootId, named("m")).getId();
    var otherCopyPart = copyMember(copyRootId, named("p")).getId();
    var liveVersions = versions(live).size();

    for (var refused :
        List.<org.junit.jupiter.api.function.Executable>of(
            () -> entityService().update(copyRootId, "{\"name\":\"changed\"}"),
            () -> entityService().updateValues(copyRootId, "{\"name\":\"changed\"}"),
            () -> entityService().link(copyPart, DEPENDS_ON, otherCopyPart),
            () -> entityService().unlink(otherCopyPart, DEPENDS_ON, copyPart),
            () -> entityService().delete(copyPart),
            () -> aggregateService().delete(copyRootId))) {
      var error = assertThrows(ServiceError.class, refused);
      assertTrue(error.getMessage().contains("version 1 of"), error.getMessage());
    }

    entityService()
        .updateValues(
            copyRootId,
            "{\"name\":\"locked\",\"provisioningStatus\":\"PROVISIONED\",\"provisioningResult\":\"ok\"}");
    assertEquals(
        "PROVISIONED",
        entityService().read(copyRootId).getValues().get("provisioningStatus").asText());
    assertEquals(liveVersions, versions(live).size(), "a run on a copy records no version");
    assertTrue(entityService().listVersions(copyRootId).isEmpty(), "nor a version of the copy");
  }

  @Test
  void theMappingUpdaterLeavesCopiesAlone() {
    var live = aggregate("updater");
    var copyRootId = freezeFirstVersion(live);
    awaitResource("updater/m2");

    entityService().update(rootId(live), "{\"name\":\"updater-renamed\"}");
    awaitResource("updater-renamed/m2");

    assertEquals(
        "updater/m",
        copyMember(copyRootId, ofType("FvResource")).getValues().get("label").asText());
  }

  @Test
  void deletingTheAggregateDeletesItsCopiesUnlessOneIsStillApplied() {
    var live = aggregate("teardown");
    var copyRootId = freezeFirstVersion(live);
    var copyResource = copyMember(copyRootId, ofType("FvResource")).getId();

    // A failed run can leave a resource provisioned under a root that is not: the resource alone
    // is enough to refuse.
    entityService()
        .updateValues(
            copyResource,
            "{\"label\":\"teardown/m\",\"provisioningStatus\":\"PROVISIONED\",\"provisioningResult\":\"ok\"}");
    var refused = assertThrows(ServiceError.class, () -> aggregateService().delete(rootId(live)));
    assertTrue(refused.getMessage().contains("version 1"), refused.getMessage());

    entityService()
        .updateValues(
            copyResource,
            "{\"label\":\"teardown/m\",\"provisioningStatus\":\"UNPROVISIONED\",\"provisioningResult\":\"ok\"}");
    aggregateService().delete(rootId(live));

    var entities = getApplicationContext().getBean(EntityRepository.class);
    assertFalse(entities.existsById(copyRootId));
    assertFalse(entities.existsById(copyResource));
  }

  @Test
  void aVersionCanBeDeletedAndItsCopyGoesWithIt() {
    var live = aggregate("prune");
    var v1Copy = freezeFirstVersion(live);
    entityService().update(mappedPartId(live), "{\"name\":\"m3\"}");
    var v2Copy = versions(live).get(1).getFrozenRootId();

    entityService().deleteVersion(rootId(live), 2);

    var versions = versions(live);
    assertEquals(List.of(1, 3), versions.stream().map(EntityVersion::getVersion).toList());
    assertEquals(versions.get(0).getId(), versions.get(1).getPreviousVersionId(), "relinked");
    var entities = getApplicationContext().getBean(EntityRepository.class);
    assertFalse(entities.existsById(v2Copy));
    assertTrue(entities.existsById(v1Copy));

    var current =
        assertThrows(ServiceError.class, () -> entityService().deleteVersion(rootId(live), 3));
    assertTrue(current.getMessage().contains("current"), current.getMessage());
    assertThrows(NotFoundException.class, () -> entityService().deleteVersion(rootId(live), 2));
  }

  @Test
  void allVersionsCanBeDeletedAtOnceKeepingTheCurrentOne() {
    var live = aggregate("wipe");
    var v1Copy = freezeFirstVersion(live);
    entityService().update(mappedPartId(live), "{\"name\":\"m3\"}");

    entityService().deleteAllVersions(rootId(live));

    var versions = versions(live);
    assertEquals(List.of(3), versions.stream().map(EntityVersion::getVersion).toList());
    assertNull(versions.getFirst().getPreviousVersionId());
    assertFalse(getApplicationContext().getBean(EntityRepository.class).existsById(v1Copy));
  }

  @Test
  void aVersionWhoseCopyIsStillAppliedCannotBeDeleted() {
    var live = aggregate("applied");
    var v1Copy = freezeFirstVersion(live);
    entityService()
        .updateValues(
            v1Copy,
            "{\"name\":\"applied\",\"provisioningStatus\":\"PROVISIONED\",\"provisioningResult\":\"ok\"}");

    var refused =
        assertThrows(ServiceError.class, () -> entityService().deleteVersion(rootId(live), 1));
    assertTrue(refused.getMessage().contains("PROVISIONED"), refused.getMessage());
    assertThrows(ServiceError.class, () -> entityService().deleteAllVersions(rootId(live)));
    assertEquals(2, versions(live).size());
  }

  @Test
  void aMappingRuleACopyUsesCannotBeDeleted() {
    var live = aggregate("rule");
    freezeFirstVersion(live);
    var rule =
        mappingService().list().stream()
            .filter(m -> m.getSource().getName().equals("FvMapped"))
            .findFirst()
            .orElseThrow();

    var refused = assertThrows(ServiceError.class, () -> mappingService().delete(rule.getId()));
    assertTrue(refused.getMessage().contains(" of aggregate "), refused.getMessage());
  }

  @Test
  void aTypeVersionACopyPinsCannotBeDeletedAndTheRefusalSaysSo() {
    var live = aggregate("pinned");
    freezeFirstVersion(live);
    int pinned = entityTypeService().read("FvPlain").getVersion();
    entityTypeService()
        .createVersion("FvPlain", List.of("FvPartTrait"), Optional.empty(), NAME_SCHEMA);

    var refused =
        assertThrows(
            ServiceError.class, () -> entityTypeService().deleteVersion("FvPlain", pinned));
    assertTrue(refused.getMessage().contains("frozen copies"), refused.getMessage());
  }

  @Test
  void anEntityOutsideAnAggregateIsNeverFrozen() {
    entityTypeService()
        .create("FvOutside", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), NAME_SCHEMA);
    var plain = entityService().create("FvOutside", "{\"name\":\"a\"}");
    entityService().update(plain.getId(), "{\"name\":\"b\"}");

    assertNull(entityService().listVersions(plain.getId()).getFirst().getFrozenRootId());
  }
}
