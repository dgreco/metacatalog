package it.davidgreco.metacatalog.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.EntityVersion;
import it.davidgreco.metacatalog.repository.EntityVersionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Durations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Entity versioning driven by the built-in {@link BuiltInTraits#VERSIONABLE} trait.
 *
 * <p>The database is reset per class, not per test, so every test works on type names of its own.
 */
@SpringBootTest
class EntityVersioningTests extends CommonServiceTestingSupport {

  private static final String NAME_SCHEMA =
      """
      { "type": "object", "properties": { "name": { "type": "string" } } }""";

  EntityVersioningTests(ApplicationContext applicationContext) {
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

  private JsonNode json(String text) {
    try {
      return getApplicationContext().getBean(ObjectMapper.class).readTree(text);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private List<String> names(List<EntityVersion> versions) {
    return versions.stream().map(v -> v.getValues().get("name").asText()).toList();
  }

  private List<Integer> numbers(List<EntityVersion> versions) {
    return versions.stream().map(EntityVersion::getVersion).toList();
  }

  @Test
  void everyUpdateOfAVersionableEntityRecordsANewVersion() {
    entityTypeService()
        .create("VerDoc", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), NAME_SCHEMA);

    var entity = entityService().create("VerDoc", "{\"name\":\"one\"}");
    assertEquals(1, entity.getVersion());

    entityService().update(entity.getId(), "{\"name\":\"two\"}");
    entityService().update(entity.getId(), "{\"name\":\"three\"}");

    var versions = entityService().listVersions(entity.getId());
    assertEquals(List.of(1, 2, 3), numbers(versions));
    assertEquals(List.of("one", "two", "three"), names(versions));
    assertEquals(3, entityService().read(entity.getId()).getVersion());
    assertEquals(
        json("{\"name\":\"one\"}"), entityService().readVersion(entity.getId(), 1).getValues());

    // The chain links each snapshot to its predecessor, and each is stamped with the type version
    // it was validated against.
    assertNull(versions.get(0).getPreviousVersionId());
    assertEquals(versions.get(0).getId(), versions.get(1).getPreviousVersionId());
    assertEquals(versions.get(1).getId(), versions.get(2).getPreviousVersionId());
    var pinned = entityService().read(entity.getId()).getEntityTypeVersion().getId();
    assertTrue(versions.stream().allMatch(v -> v.getEntityTypeVersion().getId().equals(pinned)));
  }

  @Test
  void aTypeWithoutVersionableKeepsNoHistory() {
    entityTypeService().create("VerPlain", List.of(), Optional.empty(), NAME_SCHEMA);

    var entity = entityService().create("VerPlain", "{\"name\":\"one\"}");
    entityService().update(entity.getId(), "{\"name\":\"two\"}");

    assertNull(entityService().read(entity.getId()).getVersion());
    assertTrue(entityService().listVersions(entity.getId()).isEmpty());
  }

  @Test
  void versionableIsHonouredThroughTraitAndTypeInheritance() {
    traitService().create("VerAudited", Optional.empty(), Optional.of(BuiltInTraits.VERSIONABLE));
    entityTypeService().create("VerViaTrait", List.of("VerAudited"), Optional.empty(), NAME_SCHEMA);
    entityTypeService().create("VerViaFather", List.of(), Optional.of("VerViaTrait"), NAME_SCHEMA);

    for (var typeName : List.of("VerViaTrait", "VerViaFather")) {
      var entity = entityService().create(typeName, "{\"name\":\"one\"}");
      entityService().update(entity.getId(), "{\"name\":\"two\"}");
      assertEquals(List.of(1, 2), numbers(entityService().listVersions(entity.getId())), typeName);
    }
  }

  @Test
  void anUpdateThatChangesNothingRecordsNoVersion() {
    entityTypeService()
        .create("VerNoop", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), NAME_SCHEMA);

    var entity = entityService().create("VerNoop", "{\"name\":\"same\"}");
    // Formatting differs, the document does not.
    entityService().update(entity.getId(), "{ \"name\" : \"same\" }");
    entityService().updateValues(entity.getId(), "{\"name\":\"same\"}");

    assertEquals(List.of(1), numbers(entityService().listVersions(entity.getId())));
    assertEquals(1, entityService().read(entity.getId()).getVersion());
  }

  @Test
  void numericValuesRoundTrippedThroughJsonbCompareEqual() {
    entityTypeService()
        .create(
            "VerNumeric",
            List.of(BuiltInTraits.VERSIONABLE),
            Optional.empty(),
            """
            { "type": "object", "properties": { "n": { "type": "number" } } }""");

    // 1.0 is read back as a floating-point node and 1 is parsed as an integer node: equal values,
    // unequal nodes, so only a by-value comparison sees that nothing changed.
    var entity = entityService().create("VerNumeric", "{\"n\":1.0}");
    entityService().update(entity.getId(), "{\"n\":1}");
    entityService().update(entity.getId(), "{\"n\":1.00}");

    assertEquals(List.of(1), numbers(entityService().listVersions(entity.getId())));
  }

  @Test
  void internalValueWritesAreVersionedToo() {
    entityTypeService()
        .create("VerInternal", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), NAME_SCHEMA);

    var entity = entityService().create("VerInternal", "{\"name\":\"one\"}");
    entityService().updateValues(entity.getId(), "{\"name\":\"two\"}");

    assertEquals(List.of("one", "two"), names(entityService().listVersions(entity.getId())));
  }

  @Test
  void aMappedEntityOfAVersionableTargetIsVersionedWhenRegenerated() {
    var mappingService = getApplicationContext().getBean(MappingService.class);
    entityTypeService()
        .create(
            "VerMapSource",
            List.of(),
            Optional.empty(),
            """
            { "type": "object", "properties": { "a": { "type": "integer" } } }""");
    entityTypeService()
        .create(
            "VerMapTarget",
            List.of(BuiltInTraits.VERSIONABLE),
            Optional.empty(),
            """
            { "type": "object", "properties": { "b": { "type": "integer" } } }""");
    mappingService.create(
        "VerMapSource",
        "VerMapTarget",
        "{\"b\": \"#source.getValue('$.a').intValue() * 2\"}",
        List.of());

    // The scheduled mapping updater derives the target in the background; waiting on its outcome
    // keeps the test independent of whether the scheduler or a direct call got there first.
    var source = entityService().create("VerMapSource", "{\"a\":1}");
    await()
        .atMost(Durations.TEN_SECONDS)
        .until(() -> entityService().list("VerMapTarget", "").size() == 1);
    var target = entityService().list("VerMapTarget", "").getFirst();
    assertEquals(List.of(1), numbers(entityService().listVersions(target.getId())));

    entityService().update(source.getId(), "{\"a\":5}");
    await()
        .atMost(Durations.TEN_SECONDS)
        .untilAsserted(
            () ->
                assertEquals(List.of(1, 2), numbers(entityService().listVersions(target.getId()))));
    var latest = entityService().readVersion(target.getId(), 2);
    assertEquals(10, latest.getValues().get("b").asInt());
  }

  @Test
  void aTypeThatBecomesVersionableBackfillsThePriorStateOnFirstUpdate() {
    entityTypeService().create("VerLate", List.of(), Optional.empty(), NAME_SCHEMA);
    var entity = entityService().create("VerLate", "{\"name\":\"before\"}");
    assertNull(entity.getVersion());

    entityTypeService()
        .createVersion(
            "VerLate", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), NAME_SCHEMA);
    entityService().update(entity.getId(), "{\"name\":\"after\"}");

    var versions = entityService().listVersions(entity.getId());
    assertEquals(List.of(1, 2), numbers(versions));
    assertEquals(List.of("before", "after"), names(versions));
  }

  @Test
  void aTypeThatStopsBeingVersionableKeepsItsHistoryButRecordsNoMore() {
    entityTypeService()
        .create("VerDropped", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), NAME_SCHEMA);
    var entity = entityService().create("VerDropped", "{\"name\":\"one\"}");
    entityService().update(entity.getId(), "{\"name\":\"two\"}");

    entityTypeService().createVersion("VerDropped", List.of(), Optional.empty(), NAME_SCHEMA);
    entityService().update(entity.getId(), "{\"name\":\"three\"}");

    assertEquals(List.of("one", "two"), names(entityService().listVersions(entity.getId())));
    assertEquals(2, entityService().read(entity.getId()).getVersion());
  }

  @Test
  void deletingAnEntityDeletesItsHistory() {
    var versionRepository = getApplicationContext().getBean(EntityVersionRepository.class);
    entityTypeService()
        .create("VerDeleted", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), NAME_SCHEMA);

    var viaDelete = entityService().create("VerDeleted", "{\"name\":\"one\"}");
    entityService().update(viaDelete.getId(), "{\"name\":\"two\"}");
    var viaInternal = entityService().create("VerDeleted", "{\"name\":\"one\"}");
    entityService().update(viaInternal.getId(), "{\"name\":\"two\"}");

    entityService().delete(viaDelete.getId());
    entityService().deleteInternal(viaInternal.getId());

    assertTrue(versionRepository.findByEntityIdOrderByVersionAsc(viaDelete.getId()).isEmpty());
    assertTrue(versionRepository.findByEntityIdOrderByVersionAsc(viaInternal.getId()).isEmpty());
  }

  @Test
  void readingTheHistoryOfAnUnknownEntityOrVersionIsNotFound() {
    entityTypeService()
        .create("VerMissing", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), NAME_SCHEMA);
    var entity = entityService().create("VerMissing", "{\"name\":\"one\"}");

    assertThrows(NotFoundException.class, () -> entityService().listVersions("no-such-entity"));
    assertThrows(NotFoundException.class, () -> entityService().readVersion(entity.getId(), 2));
  }

  @Test
  void aTypeVersionStillReferencedByEntityHistoryCannotBeDeleted() {
    var mappingService = getApplicationContext().getBean(MappingService.class);
    entityTypeService()
        .create(
            "VerRepinSource",
            List.of(),
            Optional.empty(),
            """
            { "type": "object", "properties": { "a": { "type": "integer" } } }""");
    var schema =
        """
        { "type": "object", "properties": { "b": { "type": "integer" } } }""";
    entityTypeService()
        .create("VerRepinTarget", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), schema);
    var rule = "{\"b\": \"#source.getValue('$.a').intValue()\"}";
    mappingService.create("VerRepinSource", "VerRepinTarget", rule, List.of());

    var source = entityService().create("VerRepinSource", "{\"a\":1}");
    await()
        .atMost(Durations.TEN_SECONDS)
        .until(() -> entityService().list("VerRepinTarget", "").size() == 1);
    var target = entityService().list("VerRepinTarget", "").getFirst();

    // Version the target, then replace the mapping so it is re-stamped against v2: the next
    // regeneration re-pins the mapped entity, leaving v1 referenced only by its history.
    entityTypeService()
        .createVersion(
            "VerRepinTarget", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), schema);
    mappingService.create("VerRepinSource", "VerRepinTarget", rule, List.of());
    entityService().update(source.getId(), "{\"a\":2}");
    await()
        .atMost(Durations.TEN_SECONDS)
        .until(() -> entityService().read(target.getId()).getEntityTypeVersion().getVersion() == 2);

    var refused =
        assertThrows(
            ServiceError.class, () -> entityTypeService().deleteVersion("VerRepinTarget", 1));
    assertTrue(refused.getMessage().contains("entity version"), refused.getMessage());
  }

  @Test
  void versionableIsImmutable() {
    assertThrows(ServiceError.class, () -> traitService().delete(BuiltInTraits.VERSIONABLE));
    assertThrows(
        ServiceError.class,
        () ->
            traitService()
                .createVersion(BuiltInTraits.VERSIONABLE, Optional.empty(), Optional.empty()));
  }

  @Test
  @Timeout(60)
  void concurrentUpdatesProduceContiguousVersions() throws Exception {
    entityTypeService()
        .create("VerConcurrent", List.of(BuiltInTraits.VERSIONABLE), Optional.empty(), NAME_SCHEMA);
    var entity = entityService().create("VerConcurrent", "{\"name\":\"start\"}");

    var writers = 8;
    try (var pool = Executors.newFixedThreadPool(writers)) {
      var futures = new ArrayList<Future<Void>>();
      for (var i = 0; i < writers; i++) {
        var name = "writer-" + i;
        Callable<Void> write =
            () -> {
              entityService().update(entity.getId(), "{\"name\":\"" + name + "\"}");
              return null;
            };
        futures.add(pool.submit(write));
      }
      for (var future : futures) future.get();
    }

    var versions = entityService().listVersions(entity.getId());
    var expected = new ArrayList<Integer>();
    for (var v = 1; v <= writers + 1; v++) expected.add(v);
    assertEquals(expected, numbers(versions));
    var live = entityService().read(entity.getId());
    assertEquals(writers + 1, live.getVersion());
    assertEquals(live.getValues(), versions.getLast().getValues());
  }
}
