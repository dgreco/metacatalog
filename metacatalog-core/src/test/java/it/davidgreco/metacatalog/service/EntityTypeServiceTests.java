package it.davidgreco.metacatalog.service;

import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.networknt.schema.JsonSchemaFactory;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.test.json.JsonAssert;

@SpringBootTest
class EntityTypeServiceTests extends CommonServiceTestingSupport {

  public EntityTypeServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testCreateDeleteExists() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var jsonSchemaFactory = getApplicationContext().getBean(JsonSchemaFactory.class);

    var baseSchema =
        jsonSchemaFactory
            .getSchema(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/base_schema.json"))
            .getSchemaNode()
            .toPrettyString();

    entityTypeService.create("NewType", List.of(), Optional.empty(), baseSchema);

    Assertions.assertThrows(
        Exception.class,
        () -> entityTypeService.create("NewType", List.of(), Optional.empty(), baseSchema));

    Assertions.assertTrue(entityTypeService.exists("NewType"));

    entityTypeService.delete("NewType");

    Assertions.assertFalse(entityTypeService.exists("NewType"));

    ServiceError exception =
        assertThrows(ServiceError.class, () -> entityTypeService.delete("UnknownType"));

    Assertions.assertEquals("EntityType UnknownType not found", exception.getMessage());
  }

  @Test
  void resolveTraitsValidatesExistenceUniquenessAndPreservesOrder() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var traitService = getApplicationContext().getBean(TraitService.class);
    var schema = "{ \"type\": \"object\", \"properties\": {} }";

    traitService.create("Rt_A", Optional.empty(), Optional.empty());
    traitService.create("Rt_B", Optional.empty(), Optional.empty());

    // A non-existent trait is still reported (the batch fetch must not silently drop it).
    var missing =
        Assertions.assertThrows(
            ServiceError.class,
            () ->
                entityTypeService.create(
                    "RtMissing", List.of("Rt_A", "Rt_DoesNotExist"), Optional.empty(), schema));
    Assertions.assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());

    // Duplicate trait names are rejected before any lookup.
    var duplicate =
        Assertions.assertThrows(
            ServiceError.class,
            () ->
                entityTypeService.create(
                    "RtDup", List.of("Rt_A", "Rt_A"), Optional.empty(), schema));
    Assertions.assertTrue(
        duplicate.getMessage().contains("already defined"), duplicate.getMessage());

    // The resolved traits come back in the requested declaration order (significant for
    // linearization), even though the batch query returns them in an unspecified order.
    var type =
        entityTypeService.create("RtOrdered", List.of("Rt_B", "Rt_A"), Optional.empty(), schema);
    Assertions.assertEquals(
        List.of("Rt_B", "Rt_A"),
        type.getTraits().stream().map(it.davidgreco.metacatalog.entity.Trait::getName).toList());
  }

  @Test
  void testInheritance() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var traitService = getApplicationContext().getBean(TraitService.class);
    var cacheManager = getApplicationContext().getBean(CaffeineCacheManager.class);
    var jsonSchemaFactory = getApplicationContext().getBean(JsonSchemaFactory.class);

    var inheritedSchema =
        """
                  {
                  "type" : "object",
                  "properties" : {
                    "version" : {
                       "type" : "number",
                       "exclusiveMinimum" : 0
                    },
                    "street_address" : {
                      "type" : "string"
                    },
                    "city" : {
                      "type" : "string"
                    },
                    "state" : {
                      "type" : "string"
                    },
                    "struct": {
                      "type": "object",
                      "properties": {
                         "aNumber": {
                            "type": "number"
                         },
                         "anInteger": {
                            "type": "integer"
                         }
                       }
                    },
                    "type" : {
                      "enum" : [ "residential", "business" ]
                    },
                    "another_property" : {
                      "type" : "string"
                    }
                  },
                  "required" : [ "street_address", "city", "state", "type", "another_property" ],
                  additionalProperties : false
                }""";

    var baseSchema =
        jsonSchemaFactory
            .getSchema(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/base_schema.json"))
            .getSchemaNode()
            .toPrettyString();

    var middleSchema =
        jsonSchemaFactory
            .getSchema(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/middle_schema.json"))
            .getSchemaNode()
            .toPrettyString();

    var leafSchema =
        jsonSchemaFactory
            .getSchema(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/leaf_schema.json"))
            .getSchemaNode()
            .toPrettyString();

    var traitSchema1 =
        jsonSchemaFactory
            .getSchema(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/trait_schema1.json"))
            .getSchemaNode()
            .toPrettyString();

    traitService.create("Trait1", Optional.of(traitSchema1), Optional.empty());

    var baseType =
        entityTypeService.create("BaseType", List.of("Trait1"), Optional.empty(), baseSchema);

    var middleType =
        entityTypeService.create("MiddleType", List.of(), Optional.of("BaseType"), middleSchema);

    var leafType =
        entityTypeService.create("LeafType", List.of(), Optional.of("MiddleType"), leafSchema);

    var leafType1 =
        entityTypeService.create("LeafType1", List.of(), Optional.of("MiddleType"), leafSchema);

    Cache<String, Object> cache =
        (Cache<String, Object>) cacheManager.getCache("EntityTypes").getNativeCache();

    Assertions.assertTrue(cache.asMap().keySet().contains(baseType.getName()));

    Assertions.assertTrue(cache.asMap().keySet().contains(middleType.getName()));

    Assertions.assertTrue(cache.asMap().keySet().contains(leafType.getName()));

    Assertions.assertTrue(cache.asMap().keySet().contains(leafType1.getName()));

    Assertions.assertEquals(2, entityTypeService.countEntityTypeChildren("MiddleType"));

    JsonAssert.comparator(JSONCompareMode.NON_EXTENSIBLE)
        .assertIsMatch(leafType.getSchema().toPrettyString(), inheritedSchema);

    Assertions.assertThrows(ServiceError.class, () -> entityTypeService.delete("MiddleType"));

    entityTypeService.delete("LeafType");

    Assertions.assertEquals(1, entityTypeService.countEntityTypeChildren("MiddleType"));

    entityTypeService.delete("LeafType1");

    Assertions.assertEquals(0, entityTypeService.countEntityTypeChildren("MiddleType"));

    entityTypeService.delete("MiddleType");

    entityTypeService.delete("BaseType");

    Assertions.assertFalse(cache.asMap().keySet().contains(baseType.getName()));

    Assertions.assertFalse(cache.asMap().keySet().contains(middleType.getName()));

    Assertions.assertFalse(cache.asMap().keySet().contains(leafType.getName()));

    Assertions.assertFalse(cache.asMap().keySet().contains(leafType1.getName()));
  }

  /**
   * Verifies that the derived schema is built following the Scala class linearization: traits
   * (Scala mixins) override the father (Scala superclass), and a trait declared later in the list
   * overrides one declared earlier. Both are cases where the previous ad-hoc merge order produced
   * the wrong precedence.
   */
  @Test
  void testLinearization() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var traitService = getApplicationContext().getBean(TraitService.class);

    // A property named "shared" is declared by several ancestors with a different "type" each, so
    // the winner can be identified by inspecting the merged schema.
    var traitSharedNumber =
        """
        {
          "type": "object",
          "properties": {
            "shared": { "type": "number" },
            "traitProp": { "type": "string" }
          }
        }
        """;
    var fatherSharedInteger =
        """
        {
          "type": "object",
          "properties": {
            "shared": { "type": "integer" },
            "fatherProp": { "type": "string" }
          }
        }
        """;
    var childOwn =
        """
        {
          "type": "object",
          "properties": {
            "childProp": { "type": "string" }
          }
        }
        """;

    // Case 1: trait overrides father.
    // Child extends Father with Trait -> linearization [Child, Trait, Father], so the trait's
    // "shared" (number) wins over the father's "shared" (integer).
    traitService.create("LinTrait", Optional.of(traitSharedNumber), Optional.empty());
    entityTypeService.create("LinFather", List.of(), Optional.empty(), fatherSharedInteger);
    var child =
        entityTypeService.create(
            "LinChild", List.of("LinTrait"), Optional.of("LinFather"), childOwn);

    JsonNode childProps = child.getSchema().get("properties");
    Assertions.assertEquals("number", childProps.get("shared").get("type").asText());
    Assertions.assertTrue(childProps.has("fatherProp"));
    Assertions.assertTrue(childProps.has("traitProp"));
    Assertions.assertTrue(childProps.has("childProp"));

    // Case 2: a later mixin overrides an earlier one.
    // MixType with traits [LinT1, LinT2] -> linearization [MixType, LinT2, LinT1], so LinT2's
    // "shared" (boolean) wins over LinT1's "shared" (string).
    var t1SharedString =
        """
        {
          "type": "object",
          "properties": { "shared": { "type": "string" } }
        }
        """;
    var t2SharedBoolean =
        """
        {
          "type": "object",
          "properties": { "shared": { "type": "boolean" } }
        }
        """;
    var mixOwn =
        """
        {
          "type": "object",
          "properties": {}
        }
        """;
    traitService.create("LinT1", Optional.of(t1SharedString), Optional.empty());
    traitService.create("LinT2", Optional.of(t2SharedBoolean), Optional.empty());
    var mixType =
        entityTypeService.create("LinMix", List.of("LinT1", "LinT2"), Optional.empty(), mixOwn);

    Assertions.assertEquals(
        "boolean", mixType.getSchema().get("properties").get("shared").get("type").asText());

    // Clean up: entity types first (they reference father and traits), then the traits.
    entityTypeService.delete("LinChild");
    entityTypeService.delete("LinFather");
    entityTypeService.delete("LinMix");
    traitService.delete("LinTrait");
    traitService.delete("LinT1");
    traitService.delete("LinT2");
  }

  @Test
  void testList() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var jsonSchemaFactory = getApplicationContext().getBean(JsonSchemaFactory.class);

    var baseSchema =
        jsonSchemaFactory
            .getSchema(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/base_schema.json"))
            .getSchemaNode()
            .toPrettyString();

    entityTypeService.create("ListTestType1", List.of(), Optional.empty(), baseSchema);
    entityTypeService.create("ListTestType2", List.of(), Optional.empty(), baseSchema);
    entityTypeService.create("ListTestType3", List.of(), Optional.empty(), baseSchema);

    var allEntityTypes = entityTypeService.list();

    Assertions.assertNotNull(allEntityTypes);
    Assertions.assertTrue(allEntityTypes.size() >= 3);

    var entityTypeNames =
        allEntityTypes.stream().map(it.davidgreco.metacatalog.entity.EntityType::getName).toList();

    Assertions.assertTrue(entityTypeNames.contains("ListTestType1"));
    Assertions.assertTrue(entityTypeNames.contains("ListTestType2"));
    Assertions.assertTrue(entityTypeNames.contains("ListTestType3"));

    entityTypeService.delete("ListTestType1");
    entityTypeService.delete("ListTestType2");
    entityTypeService.delete("ListTestType3");
  }

  @Test
  void testCreateVersionSnapshotsAndReadsHistory() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var traitService = getApplicationContext().getBean(TraitService.class);

    var schemaV1 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" } }
        }
        """;
    var schemaV2 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" }, "value": { "type": "number" } }
        }
        """;

    traitService.create("VersionTrait", Optional.empty(), Optional.empty());

    var liveV1 =
        entityTypeService.create(
            "VersionedType", List.of("VersionTrait"), Optional.empty(), schemaV1);
    Assertions.assertEquals(1, liveV1.getVersion());
    Assertions.assertNotNull(liveV1.getVersionGroupId());

    var liveV2 =
        entityTypeService.createVersion("VersionedType", List.of(), Optional.empty(), schemaV2);
    Assertions.assertEquals(2, liveV2.getVersion());
    Assertions.assertEquals(liveV1.getVersionGroupId(), liveV2.getVersionGroupId());
    Assertions.assertTrue(liveV2.getSchema().get("properties").has("value"));
    Assertions.assertTrue(liveV2.getTraits().isEmpty());

    var readLive = entityTypeService.read("VersionedType");
    Assertions.assertEquals(2, readLive.getVersion());

    var historyV1 = entityTypeService.readVersion("VersionedType", 1);
    Assertions.assertTrue(historyV1 instanceof VersionResult.Snapshot);
    var snapshot = ((VersionResult.Snapshot<?, ?>) historyV1).value();
    Assertions.assertEquals(1, ((EntityTypeVersion) snapshot).getVersion());
    Assertions.assertFalse(
        ((EntityTypeVersion) snapshot).getSchema().get("properties").has("value"));

    var snapshotTraits =
        java.util.stream.StreamSupport.stream(
                ((EntityTypeVersion) snapshot).getTraits().spliterator(), false)
            .map(JsonNode::asText)
            .toList();
    Assertions.assertEquals(List.of("VersionTrait"), snapshotTraits);

    var versions = entityTypeService.listVersions("VersionedType");
    Assertions.assertEquals(2, versions.size());
    Assertions.assertTrue(versions.get(0) instanceof VersionResult.Snapshot);
    Assertions.assertTrue(versions.get(1) instanceof VersionResult.Live);

    Assertions.assertThrows(
        ServiceError.class, () -> entityTypeService.deleteVersion("VersionedType", 2));

    entityTypeService.deleteVersion("VersionedType", 1);
    Assertions.assertEquals(1, entityTypeService.listVersions("VersionedType").size());

    Assertions.assertThrows(
        ServiceError.class, () -> entityTypeService.readVersion("VersionedType", 1));

    entityTypeService.delete("VersionedType");
    traitService.delete("VersionTrait");
  }

  /**
   * Right after {@code create} there is no historical snapshot yet, so {@code listVersions} returns
   * exactly one element: the live type itself. The current-version snapshot still exists in the
   * {@code entity_type_version} table (entities pin to it) but is filtered out by {@code
   * listVersions}.
   */
  @Test
  void testListVersionsRightAfterCreateReturnsOnlyLive() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityTypeVersionRepository =
        getApplicationContext()
            .getBean(it.davidgreco.metacatalog.repository.EntityTypeVersionRepository.class);

    entityTypeService.create(
        "NoHistoryType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "name": { "type": "string" } } }
        """);

    var versions = entityTypeService.listVersions("NoHistoryType");
    Assertions.assertEquals(1, versions.size());
    Assertions.assertTrue(versions.getFirst() instanceof VersionResult.Live);

    var live = entityTypeService.read("NoHistoryType");
    var currentSnapshot =
        entityTypeVersionRepository.findByVersionGroupIdAndVersion(
            live.getVersionGroupId(), live.getVersion());
    Assertions.assertTrue(currentSnapshot.isPresent(), "current-version snapshot must exist");

    entityTypeService.delete("NoHistoryType");
  }

  /**
   * {@code deleteAllVersions} must refuse with a friendly message when an entity is still pinned to
   * a HISTORICAL snapshot, instead of surfacing the raw FK violation. The current-version snapshot
   * is preserved anyway, but historical ones cannot be removed while referenced.
   */
  @Test
  void testDeleteAllVersionsRefusesWhenEntityPinnedToHistoricalSnapshot() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var entityTypeVersionRepository =
        getApplicationContext()
            .getBean(it.davidgreco.metacatalog.repository.EntityTypeVersionRepository.class);

    var schemaV1 =
        """
        { "type": "object", "properties": { "name": { "type": "string" } }, "required": ["name"] }
        """;
    var schemaV2 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" }, "value": { "type": "number" } },
          "required": ["name", "value"]
        }
        """;

    entityTypeService.create("HistPinType", List.of(), Optional.empty(), schemaV1);
    var entity =
        entityService.create(
            "HistPinType",
            """
            { "name": "alpha" }
            """);
    Assertions.assertEquals(1, entity.getEntityTypeVersion().getVersion());

    entityTypeService.createVersion("HistPinType", List.of(), Optional.empty(), schemaV2);
    var live = entityTypeService.read("HistPinType");
    Assertions.assertEquals(2, live.getVersion());

    var ex =
        Assertions.assertThrows(
            ServiceError.class, () -> entityTypeService.deleteAllVersions("HistPinType"));
    Assertions.assertTrue(
        ex.getMessage().contains("referenced by 1 entit"),
        "expected friendly referenced-by message, got: " + ex.getMessage());

    var history =
        entityTypeVersionRepository
            .findByVersionGroupIdOrderByVersionAsc(live.getVersionGroupId())
            .stream()
            .filter(s -> s.getVersion() != live.getVersion())
            .toList();
    Assertions.assertEquals(1, history.size(), "historical snapshot must still exist");

    entityService.delete(entity.getId());
    entityTypeService.delete("HistPinType");
  }

  /**
   * {@code delete} (whole type) must refuse with a friendly message when an entity is still pinned
   * to any snapshot, instead of surfacing the raw FK violation.
   */
  @Test
  void testDeleteTypeRefusesWhenEntityPinned() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    entityTypeService.create(
        "PinnedRefType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "name": { "type": "string" } }, "required": ["name"] }
        """);
    var entity =
        entityService.create(
            "PinnedRefType",
            """
            { "name": "alpha" }
            """);
    Assertions.assertNotNull(entity.getEntityTypeVersion());

    var ex =
        Assertions.assertThrows(
            ServiceError.class, () -> entityTypeService.delete("PinnedRefType"));
    Assertions.assertTrue(
        ex.getMessage().contains("referenced by 1 entit"),
        "expected friendly referenced-by message, got: " + ex.getMessage());

    Assertions.assertTrue(entityTypeService.exists("PinnedRefType"), "type must still exist");

    entityService.delete(entity.getId());
    entityTypeService.delete("PinnedRefType");
  }

  /**
   * When a type was created before version pinning was introduced (so its current live version has
   * no {@link EntityTypeVersion} snapshot), {@code createVersion} must backfill a snapshot for the
   * current live version before mutating it, so the version chain stays complete and entities
   * created against that previous live version can be pinned to it retroactively.
   */
  @Test
  void testCreateVersionBackfillsMissingCurrentSnapshot()
      throws com.fasterxml.jackson.core.JsonProcessingException {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityTypeRepository =
        getApplicationContext()
            .getBean(it.davidgreco.metacatalog.repository.EntityTypeRepository.class);
    var entityTypeVersionRepository =
        getApplicationContext()
            .getBean(it.davidgreco.metacatalog.repository.EntityTypeVersionRepository.class);
    var jsonMapper = getApplicationContext().getBean(ObjectMapper.class);

    // Simulate a pre-V4 type: insert a live row directly via the repository, bypassing the service
    // (which now creates a v1 snapshot on create).
    var live = new it.davidgreco.metacatalog.entity.EntityType();
    live.setName("BackfillType");
    live.setBaseSchema(
        jsonMapper.readTree(
            """
                { "type": "object", "properties": { "name": { "type": "string" } } }
                """));
    live.setVersion(1);
    live.setVersionGroupId(java.util.UUID.randomUUID().toString());
    entityTypeRepository.save(live);

    var preCheck =
        entityTypeVersionRepository.findByVersionGroupIdAndVersion(
            live.getVersionGroupId(), live.getVersion());
    Assertions.assertTrue(preCheck.isEmpty(), "fixture: no snapshot should exist yet");

    entityTypeService.createVersion(
        "BackfillType",
        List.of(),
        Optional.empty(),
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" }, "value": { "type": "number" } }
        }
        """);

    var nowLive = entityTypeService.read("BackfillType");
    Assertions.assertEquals(2, nowLive.getVersion());

    var v1Snapshot =
        entityTypeVersionRepository.findByVersionGroupIdAndVersion(nowLive.getVersionGroupId(), 1);
    Assertions.assertTrue(v1Snapshot.isPresent(), "backfill snapshot for v1 must exist");
    Assertions.assertNull(
        v1Snapshot.get().getPreviousVersionId(), "backfilled v1 snapshot has no predecessor");

    var v2Snapshot =
        entityTypeVersionRepository.findByVersionGroupIdAndVersion(nowLive.getVersionGroupId(), 2);
    Assertions.assertTrue(v2Snapshot.isPresent(), "current-version snapshot for v2 must exist");
    Assertions.assertEquals(v1Snapshot.get().getId(), v2Snapshot.get().getPreviousVersionId());

    entityTypeService.delete("BackfillType");
  }
}
