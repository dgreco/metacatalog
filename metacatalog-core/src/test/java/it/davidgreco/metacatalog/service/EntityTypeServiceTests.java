package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonSchemaFactory;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.json.JsonAssert;

@SpringBootTest
class EntityTypeServiceTests extends CommonServiceTestingSupport {

  public EntityTypeServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testCreateDeleteExists() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);

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
  void testInheritance() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var traitService = getApplicationContext().getBean(TraitService.class);
    var cacheManager = getApplicationContext().getBean(CaffeineCacheManager.class);

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

    Assertions.assertThrows(
        DataIntegrityViolationException.class, () -> entityTypeService.delete("MiddleType"));

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
  void testLinearization() throws ServiceError {
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
  void testList() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);

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
  void testCreateVersionSnapshotsAndReadsHistory() throws ServiceError {
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
    Assertions.assertTrue(historyV1 instanceof EntityTypeVersion);
    var snapshot = (EntityTypeVersion) historyV1;
    Assertions.assertEquals(1, snapshot.getVersion());
    Assertions.assertFalse(snapshot.getSchema().get("properties").has("value"));

    var snapshotTraits =
        java.util.stream.StreamSupport.stream(snapshot.getTraits().spliterator(), false)
            .map(JsonNode::asText)
            .toList();
    Assertions.assertEquals(List.of("VersionTrait"), snapshotTraits);

    var versions = entityTypeService.listVersions("VersionedType");
    Assertions.assertEquals(2, versions.size());
    Assertions.assertTrue(versions.get(0) instanceof EntityTypeVersion);
    Assertions.assertTrue(versions.get(1) instanceof it.davidgreco.metacatalog.entity.EntityType);

    Assertions.assertThrows(
        ServiceError.class, () -> entityTypeService.deleteVersion("VersionedType", 2));

    entityTypeService.deleteVersion("VersionedType", 1);
    Assertions.assertEquals(1, entityTypeService.listVersions("VersionedType").size());

    Assertions.assertThrows(
        ServiceError.class, () -> entityTypeService.readVersion("VersionedType", 1));

    entityTypeService.delete("VersionedType");
    traitService.delete("VersionTrait");
  }
}
