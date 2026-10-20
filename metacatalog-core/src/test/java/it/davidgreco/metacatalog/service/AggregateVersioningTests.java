package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityVersion;
import it.davidgreco.metacatalog.repository.EntityVersionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A {@link BuiltInTraits#VERSIONABLE} aggregate root versions its whole aggregate — root, {@code
 * HAS_PART} tree and {@code DEPENDS_ON} links — once per transaction, and only a root may be
 * versionable within an aggregate.
 *
 * <p>The database is reset per class, not per test, so every test builds an aggregate of its own
 * from the types {@link #model()} creates once.
 */
@SpringBootTest
class AggregateVersioningTests extends CommonServiceTestingSupport {

  private static final String NAME_SCHEMA =
      """
      { "type": "object", "properties": { "name": { "type": "string" } } }""";

  AggregateVersioningTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  private EntityService entityService() {
    return getApplicationContext().getBean(EntityService.class);
  }

  private EntityTypeService entityTypeService() {
    return getApplicationContext().getBean(EntityTypeService.class);
  }

  private TraitService traitService() {
    return getApplicationContext().getBean(TraitService.class);
  }

  private AggregateService aggregateService() {
    return getApplicationContext().getBean(AggregateService.class);
  }

  private JsonNode json(String text) {
    try {
      return new ObjectMapper().readTree(text);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * A versionable root type composing a leaf type whose instances may depend on each other. Created
   * on first use: the context and the database outlive a single test.
   */
  private void model() {
    if (entityTypeService().exists("AvRoot")) return;
    traitService()
        .create("AvPartTrait", Optional.empty(), Optional.of(BuiltInTraits.AGGREGATE_ELEMENT));
    traitService().link("AvPartTrait", DEPENDS_ON, "AvPartTrait");
    entityTypeService()
        .create(
            "AvRoot",
            List.of(BuiltInTraits.AGGREGATE, BuiltInTraits.VERSIONABLE),
            Optional.empty(),
            NAME_SCHEMA);
    entityTypeService().create("AvLeaf", List.of("AvPartTrait"), Optional.empty(), NAME_SCHEMA);
  }

  /** Creates root(name) with two leaves, the second depending on the first. */
  private AggregateService.Aggregate aggregate(String name) {
    model();
    var root = new Entity(entityTypeService().read("AvRoot"), json("{\"name\":\"" + name + "\"}"));
    var first = new Entity(entityTypeService().read("AvLeaf"), json("{\"name\":\"first\"}"));
    var second = new Entity(entityTypeService().read("AvLeaf"), json("{\"name\":\"second\"}"));
    return (AggregateService.Aggregate)
        aggregateService()
            .create(
                new AggregateService.Aggregate(
                    root,
                    List.of(
                        new AggregateService.AggregateElement(first),
                        new AggregateService.AggregateElement(second, List.of(first)))));
  }

  private List<EntityVersion> versions(AggregateService.Aggregate aggregate) {
    return entityService().listVersions(aggregate.entity().getId());
  }

  private static List<String> partNames(EntityVersion version) {
    return StreamSupport.stream(version.getAggregate().get("parts").spliterator(), false)
        .map(part -> part.get("values").get("name").asText())
        .sorted()
        .toList();
  }

  private static String partId(AggregateService.Aggregate aggregate, int index) {
    return aggregate.elements().get(index).entity().getId();
  }

  @Test
  void creatingAVersionableAggregateRecordsOneVersionOfTheWholeTree() {
    var aggregate = aggregate("whole");

    var versions = versions(aggregate);
    assertEquals(1, versions.size(), "one transaction, one version");
    var tree = versions.getFirst().getAggregate();
    assertEquals(aggregate.entity().getId(), tree.get("id").asText());
    assertEquals("AvRoot", tree.get("entityType").asText());
    assertEquals(json("{\"name\":\"whole\"}"), tree.get("values"));
    assertEquals(List.of("first", "second"), partNames(versions.getFirst()));
    var second =
        StreamSupport.stream(tree.get("parts").spliterator(), false)
            .filter(p -> p.get("id").asText().equals(partId(aggregate, 1)))
            .findFirst()
            .orElseThrow();
    assertEquals(partId(aggregate, 0), second.get("dependencies").get(0).asText());
    assertEquals(1, entityService().read(aggregate.entity().getId()).getVersion());
  }

  @Test
  void editingAPartRecordsANewVersionOfTheAggregate() {
    var aggregate = aggregate("edited");

    entityService().update(partId(aggregate, 0), "{\"name\":\"first-renamed\"}");
    entityService().update(partId(aggregate, 0), "{\"name\":\"first-renamed\"}");

    var versions = versions(aggregate);
    assertEquals(2, versions.size(), "the unchanged re-write records nothing");
    assertEquals(List.of("first-renamed", "second"), partNames(versions.getLast()));
    assertEquals(json("{\"name\":\"edited\"}"), versions.getLast().getValues());
    assertEquals(List.of("first", "second"), partNames(versions.getFirst()));
  }

  /**
   * A part below an intermediate node is reached by walking up through a node that is not a root,
   * and the root's version records the nested tree.
   */
  @Test
  void editingANestedPartRecordsAVersionOfTheRootWithTheNestedTree() {
    model();
    if (!entityTypeService().exists("AvMiddle"))
      entityTypeService()
          .create(
              "AvMiddle",
              List.of(BuiltInTraits.AGGREGATE, "AvPartTrait"),
              Optional.empty(),
              NAME_SCHEMA);
    var root = new Entity(entityTypeService().read("AvRoot"), json("{\"name\":\"nested\"}"));
    var middle = new Entity(entityTypeService().read("AvMiddle"), json("{\"name\":\"middle\"}"));
    var leaf = new Entity(entityTypeService().read("AvLeaf"), json("{\"name\":\"leaf\"}"));
    var aggregate =
        (AggregateService.Aggregate)
            aggregateService()
                .create(
                    new AggregateService.Aggregate(
                        root,
                        List.of(
                            new AggregateService.Aggregate(
                                middle, List.of(new AggregateService.AggregateElement(leaf))))));

    entityService().update(leaf.getId(), "{\"name\":\"leaf-renamed\"}");

    var versions = versions(aggregate);
    assertEquals(2, versions.size());
    var nestedLeaf = versions.getLast().getAggregate().get("parts").get(0).get("parts").get(0);
    assertEquals(leaf.getId(), nestedLeaf.get("id").asText());
    assertEquals("leaf-renamed", nestedLeaf.get("values").get("name").asText());
  }

  /**
   * A versionable root whose only part is a mapping source: the part derives one AvDerived entity
   * asynchronously, through the scheduled mapping updater.
   */
  private AggregateService.Aggregate aggregateWithDerivedResource(String name) {
    model();
    if (!entityTypeService().exists("AvMappedLeaf")) {
      entityTypeService()
          .create("AvMappedLeaf", List.of("AvPartTrait"), Optional.empty(), NAME_SCHEMA);
      entityTypeService()
          .create(
              "AvDerived",
              List.of(),
              Optional.empty(),
              """
              { "type": "object",
                "properties": { "label": { "type": "string" }, "status": { "type": "string" } } }""");
      getApplicationContext()
          .getBean(MappingService.class)
          .create(
              "AvMappedLeaf",
              "AvDerived",
              "{\"label\": \"#source.getValue('$.name').asText() + '-derived'\"}",
              List.of());
    }
    var root = new Entity(entityTypeService().read("AvRoot"), json("{\"name\":\"" + name + "\"}"));
    var part =
        new Entity(entityTypeService().read("AvMappedLeaf"), json("{\"name\":\"" + name + "\"}"));
    return (AggregateService.Aggregate)
        aggregateService()
            .create(
                new AggregateService.Aggregate(
                    root, List.of(new AggregateService.AggregateElement(part))));
  }

  /** The label of the entity the version recorded as derived from the root's only part, if any. */
  private static String derivedLabel(EntityVersion version) {
    var part = version.getAggregate().get("parts").get(0);
    return part.has("mapped")
        ? part.get("mapped").get(0).get("values").get("label").asText()
        : null;
  }

  /**
   * Waits for the mapping updater to have derived an entity with the given label, and returns it.
   * Its transaction has committed by then, so any version it were to record would already exist.
   */
  private Entity awaitDerived(String label) {
    var query = "$ ? (@.label == \"" + label + "\")";
    org.awaitility.Awaitility.await()
        .atMost(org.awaitility.Durations.TEN_SECONDS)
        .until(() -> entityService().list("AvDerived", query).size() == 1);
    return entityService().list("AvDerived", query).getFirst();
  }

  /**
   * The mapping updater's writes are not versions of their own: deriving an entity from a part, or
   * re-deriving it after the part changed, records nothing. The derived entity is still part of the
   * aggregate, and the next version taken for any other reason records it as it then is.
   */
  @Test
  void theMappingUpdaterRecordsNoVersionButItsResultIsInTheNextOne() {
    var aggregate = aggregateWithDerivedResource("updater");
    awaitDerived("updater-derived");
    assertEquals(1, versions(aggregate).size(), "deriving the entity records no version");
    assertNull(derivedLabel(versions(aggregate).getFirst()), "v1 precedes the derivation");

    entityService().update(partId(aggregate, 0), "{\"name\":\"updater-2\"}");
    awaitDerived("updater-2-derived");
    var afterRename = versions(aggregate);
    assertEquals(2, afterRename.size(), "the rename is a version, its re-derivation is not");
    assertEquals("updater-derived", derivedLabel(afterRename.getLast()));

    entityService().update(aggregate.entity().getId(), "{\"name\":\"updater-root\"}");
    assertEquals("updater-2-derived", derivedLabel(versions(aggregate).getLast()));
  }

  @Test
  void aStatusWriteOnADerivedResourceIsAVersion() {
    var aggregate = aggregateWithDerivedResource("status");
    var derived = awaitDerived("status-derived");

    entityService()
        .updateValues(derived.getId(), "{\"label\":\"status-derived\",\"status\":\"DONE\"}");

    var latest = versions(aggregate).getLast();
    assertEquals(2, latest.getVersion());
    assertEquals(
        "DONE",
        latest
            .getAggregate()
            .get("parts")
            .get(0)
            .get("mapped")
            .get(0)
            .get("values")
            .get("status")
            .asText());
  }

  @Test
  void linkingAndUnlinkingInsideTheAggregateRecordVersions() {
    var aggregate = aggregate("relinked");

    entityService().unlink(partId(aggregate, 1), DEPENDS_ON, partId(aggregate, 0));
    entityService().link(partId(aggregate, 0), DEPENDS_ON, partId(aggregate, 1));

    var versions = versions(aggregate);
    assertEquals(List.of(1, 2, 3), versions.stream().map(EntityVersion::getVersion).toList());
    for (var part : versions.get(1).getAggregate().get("parts"))
      assertTrue(
          !part.has("dependencies") || part.get("dependencies").isEmpty(),
          "after the unlink nothing depends on anything: " + part);
  }

  @Test
  void aStatusWriteOnTheRootRecordsTheWholeTreeAgain() {
    var aggregate = aggregate("status");

    entityService().updateValues(aggregate.entity().getId(), "{\"name\":\"status-written\"}");

    var versions = versions(aggregate);
    assertEquals(2, versions.size());
    assertEquals(List.of("first", "second"), partNames(versions.getLast()));
  }

  @Test
  void severalChangesInOneTransactionRecordOneVersion() {
    var aggregate = aggregate("batched");
    var transactions =
        new TransactionTemplate(getApplicationContext().getBean(PlatformTransactionManager.class));

    transactions.executeWithoutResult(
        _ -> {
          entityService().update(partId(aggregate, 0), "{\"name\":\"a\"}");
          entityService().update(partId(aggregate, 1), "{\"name\":\"b\"}");
          entityService().update(aggregate.entity().getId(), "{\"name\":\"batched-2\"}");
        });

    var versions = versions(aggregate);
    assertEquals(2, versions.size());
    assertEquals(List.of("a", "b"), partNames(versions.getLast()));
    assertEquals(json("{\"name\":\"batched-2\"}"), versions.getLast().getValues());
  }

  @Test
  void deletingTheAggregateDeletesItsHistory() {
    var aggregate = aggregate("deleted");
    entityService().update(partId(aggregate, 0), "{\"name\":\"x\"}");

    aggregateService().delete(aggregate.entity().getId());

    var history =
        getApplicationContext()
            .getBean(EntityVersionRepository.class)
            .findByEntityIdOrderByVersionAsc(aggregate.entity().getId());
    assertTrue(history.isEmpty());
  }

  @Test
  void aPlainVersionableEntityCarriesNoAggregate() {
    entityTypeService()
        .create("AvPlain", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), NAME_SCHEMA);
    var plain = entityService().create("AvPlain", "{\"name\":\"plain\"}");

    assertNull(entityService().listVersions(plain.getId()).getFirst().getAggregate());
  }

  @Test
  void onlyAnAggregateRootMayBeVersionable() {
    model();
    var versionable = BuiltInTraits.VERSIONABLE;

    // A leaf, and an intermediate node: both carry AggregateElement.
    assertRefused(
        () ->
            entityTypeService()
                .create(
                    "AvBadLeaf",
                    List.of(BuiltInTraits.AGGREGATE_ELEMENT, versionable),
                    Optional.empty(),
                    NAME_SCHEMA));
    assertRefused(
        () ->
            entityTypeService()
                .create(
                    "AvBadMiddle",
                    List.of(BuiltInTraits.AGGREGATE, BuiltInTraits.AGGREGATE_ELEMENT, versionable),
                    Optional.empty(),
                    NAME_SCHEMA));
    // Inherited from a father type, and through a trait's father.
    assertRefused(
        () ->
            entityTypeService()
                .create("AvBadChild", List.of("AvPartTrait"), Optional.of("AvRoot"), NAME_SCHEMA));
    traitService().create("AvVersionedTrait", Optional.empty(), Optional.of(versionable));
    assertRefused(
        () ->
            entityTypeService()
                .create(
                    "AvBadViaTrait",
                    List.of("AvPartTrait", "AvVersionedTrait"),
                    Optional.empty(),
                    NAME_SCHEMA));

    // Versioning a part type to add the trait.
    assertRefused(
        () ->
            entityTypeService()
                .createVersion(
                    "AvLeaf", List.of("AvPartTrait", versionable), Optional.empty(), NAME_SCHEMA));

    // Versioning a father type whose subtype is a part.
    entityTypeService().create("AvFather", List.of(), Optional.empty(), NAME_SCHEMA);
    entityTypeService()
        .create("AvSubPart", List.of("AvPartTrait"), Optional.of("AvFather"), NAME_SCHEMA);
    assertRefused(
        () ->
            entityTypeService()
                .createVersion("AvFather", List.of(versionable), Optional.empty(), NAME_SCHEMA));

    // Re-parenting a trait a part type carries under Versionable.
    traitService().create("AvMixin", Optional.empty(), Optional.empty());
    entityTypeService()
        .create("AvMixedPart", List.of("AvPartTrait", "AvMixin"), Optional.empty(), NAME_SCHEMA);
    assertRefused(
        () -> traitService().createVersion("AvMixin", Optional.empty(), Optional.of(versionable)));

    // The root itself is fine (AvRoot exists), and so is a type outside the aggregate model.
    entityTypeService().create("AvOutside", List.of(versionable), Optional.empty(), NAME_SCHEMA);
  }

  private static void assertRefused(org.junit.jupiter.api.function.Executable change) {
    var refused = assertThrows(ServiceError.class, change);
    assertTrue(
        refused.getMessage().contains("only the root can be versionable"), refused.getMessage());
  }

  /**
   * A part edit whose transaction loaded the root before a concurrent root edit committed must not
   * write that stale root back when it records the aggregate: recording bumps the root's version,
   * which saves the root, and a stale copy would silently undo the other writer's change.
   */
  @Test
  @Timeout(60)
  void recordingAPartEditDoesNotUndoAConcurrentRootEdit() throws Exception {
    var aggregate = aggregate("racing");
    var rootId = aggregate.entity().getId();
    var transactions =
        new TransactionTemplate(getApplicationContext().getBean(PlatformTransactionManager.class));
    var partEditLoadedTheRoot = new java.util.concurrent.CountDownLatch(1);
    var rootEditCommitted = new java.util.concurrent.CountDownLatch(1);

    try (var pool = Executors.newSingleThreadExecutor()) {
      var partEdit =
          pool.submit(
              () ->
                  transactions.executeWithoutResult(
                      _ -> {
                        // Walking up to the root loads it into this transaction.
                        entityService().update(partId(aggregate, 0), "{\"name\":\"part-edit\"}");
                        partEditLoadedTheRoot.countDown();
                        try {
                          rootEditCommitted.await();
                        } catch (InterruptedException e) {
                          throw new IllegalStateException(e);
                        }
                      }));
      partEditLoadedTheRoot.await();
      entityService().update(rootId, "{\"name\":\"root-edit\"}");
      rootEditCommitted.countDown();
      partEdit.get();
    }

    assertEquals(json("{\"name\":\"root-edit\"}"), entityService().read(rootId).getValues());
    var last = versions(aggregate).getLast();
    assertEquals(json("{\"name\":\"root-edit\"}"), last.getValues());
    assertEquals(List.of("part-edit", "second"), partNames(last));
  }

  @Test
  @Timeout(60)
  void concurrentEditsToDifferentPartsProduceContiguousVersions() throws Exception {
    var aggregate = aggregate("concurrent");

    var rounds = 6;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<Future<Void>>();
      for (var i = 0; i < rounds; i++) {
        var round = i;
        for (var part = 0; part < 2; part++) {
          var partIdToEdit = partId(aggregate, part);
          var label = "p" + part + "-r" + round;
          Callable<Void> write =
              () -> {
                entityService().update(partIdToEdit, "{\"name\":\"" + label + "\"}");
                return null;
              };
          futures.add(pool.submit(write));
        }
      }
      for (var future : futures) future.get();
    }

    var versions = versions(aggregate);
    var numbers = versions.stream().map(EntityVersion::getVersion).toList();
    var expected = new ArrayList<Integer>();
    for (var v = 1; v <= versions.size(); v++) expected.add(v);
    assertEquals(expected, numbers, "versions must be contiguous");
    assertEquals(1 + 2 * rounds, versions.size());
    var live =
        List.of(
                entityService().read(partId(aggregate, 0)).getValues().get("name").asText(),
                entityService().read(partId(aggregate, 1)).getValues().get("name").asText())
            .stream()
            .sorted()
            .toList();
    assertEquals(live, partNames(versions.getLast()), "the last version reflects both edits");
  }
}
