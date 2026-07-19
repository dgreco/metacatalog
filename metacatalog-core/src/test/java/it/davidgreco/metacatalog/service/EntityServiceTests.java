package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;
import static it.davidgreco.metacatalog.common.JsonUtils.jsonSchemaFactory;
import static it.davidgreco.metacatalog.entity.RelationType.*;
import static org.junit.Assert.assertThrows;

import it.davidgreco.metacatalog.repository.EntityRepository;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.json.JsonAssert;

@SpringBootTest
class EntityServiceTests extends CommonServiceTestingSupport {

  public EntityServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testCreationAndValidationAndUpdate() throws IOException, ServiceError {
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var schema =
        jsonSchemaFactory
            .getSchema(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/simple_schema.json"))
            .getSchemaNode()
            .toPrettyString();

    var values =
        jsonFactory
            .readTree(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/simple_doc.json"))
            .toPrettyString();

    var invalidValues =
        jsonFactory
            .readTree(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/invalid_simple_doc.json"))
            .toPrettyString();

    var testType = entityTypeService.create("TestType", List.of(), Optional.empty(), schema);

    var entity = entityService.create("TestType", values);

    SchemaValidationError exception =
        assertThrows(
            SchemaValidationError.class, () -> entityService.create("TestType", invalidValues));

    Assertions.assertEquals(
        "$.price: must have an exclusive minimum value of 0", exception.getErrors().getFirst());

    Assertions.assertEquals(1, entityRepository.countByEntityType(testType));

    Assertions.assertThrows(ServiceError.class, () -> entityTypeService.delete("TestType"));

    var updatedValues =
        """
                {
                  "name": "Lampshade",
                  "price": 2
                }
                """;

    entityService.update(entity.getId(), updatedValues);

    var newValues = entityService.read(entity.getId()).getValues().toPrettyString();

    JsonAssert.comparator(JSONCompareMode.NON_EXTENSIBLE).assertIsMatch(newValues, updatedValues);

    entityService.delete(entity.getId());

    entityTypeService.delete("TestType");

    Assertions.assertEquals(0, entityRepository.countByEntityType(testType));
  }

  @Test
  void testLinkUnlinkLinkedEntities() throws ServiceError {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var emptySchema =
        """
                {
                  "type": "object",
                  "properties": {}
                }
                """;

    var emptyValues =
        """
                {}
                """;

    traitService.create("SourceTrait", Optional.empty(), Optional.empty());

    traitService.create("TargetTrait", Optional.empty(), Optional.empty());

    traitService.create("InheritedSourceTrait", Optional.empty(), Optional.of("SourceTrait"));

    traitService.create("InheritedTargetTrait", Optional.empty(), Optional.of("TargetTrait"));

    entityTypeService.create(
        "SourceEntityType", List.of("InheritedSourceTrait"), Optional.empty(), emptySchema);

    entityTypeService.create(
        "TargetEntityType", List.of("InheritedTargetTrait"), Optional.empty(), emptySchema);

    traitService.link("SourceTrait", DEPENDS_ON, "TargetTrait");

    var sourceEntity = entityService.create("SourceEntityType", emptyValues);

    var targetEntity = entityService.create("TargetEntityType", emptyValues);

    Assertions.assertThrows(
        ServiceError.class,
        () -> entityService.link(sourceEntity.getId(), HAS_PART, targetEntity.getId()));

    entityService.link(sourceEntity.getId(), DEPENDS_ON, targetEntity.getId());

    // The inverse relation should be created automatically
    Assertions.assertThrows(
        ServiceError.class,
        () -> entityService.link(targetEntity.getId(), IS_REQUIRED_BY, sourceEntity.getId()));

    // No loops
    Assertions.assertThrows(
        ServiceError.class,
        () -> entityService.link(targetEntity.getId(), DEPENDS_ON, sourceEntity.getId()));

    // No multiple link between source and target
    Assertions.assertThrows(
        ServiceError.class,
        () -> entityService.link(sourceEntity.getId(), DEPENDS_ON, targetEntity.getId()));
  }

  @Test
  void testList() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    entityTypeService.create(
        "TestType",
        List.of(),
        Optional.empty(),
        """
                    {
                      "type": "object",
                      "properties": {
                        "a": {
                          "type": "string"
                        },
                        "b": {
                          "type": "integer"
                        }
                      },
                      "required": ["a", "b"]
                    }
                    """);

    var e1 =
        entityService.create(
            "TestType",
            """
                {
                  "a": "a",
                  "b": 1
                }
                """);

    var e2 =
        entityService.create(
            "TestType",
            """
                {
                  "a": "b",
                  "b": 2
                }
                """);

    var entities1 =
        entityService.list(
            "TestType",
            """
                           $ ? (@.a == "a" && @.b == 1)
                         """);
    Assertions.assertEquals(1, entities1.size());
    Assertions.assertEquals(e1.getId(), entities1.getFirst().getId());

    var entities2 =
        entityService.list(
            "TestType",
            """
                       $ ? (@.a == "b" && @.b > 1)
                     """);
    Assertions.assertEquals(1, entities2.size());
    Assertions.assertEquals(e2.getId(), entities2.getFirst().getId());
  }

  @Test
  void testEntityIsPinnedToCreationVersion() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var schemaV1 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" } },
          "required": ["name"],
          "additionalProperties": false
        }
        """;
    var schemaV2 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" }, "value": { "type": "number" } },
          "required": ["name", "value"],
          "additionalProperties": false
        }
        """;

    entityTypeService.create("PinnedType", List.of(), Optional.empty(), schemaV1);

    var valuesV1 =
        """
        { "name": "alpha" }
        """;
    var entity = entityService.create("PinnedType", valuesV1);
    Assertions.assertNotNull(entity.getEntityTypeVersion());
    Assertions.assertEquals(1, entity.getEntityTypeVersion().getVersion());
    Assertions.assertFalse(
        entity.getEntityTypeVersion().getSchema().get("properties").has("value"));

    entityTypeService.createVersion("PinnedType", List.of(), Optional.empty(), schemaV2);

    var live = entityTypeService.read("PinnedType");
    Assertions.assertEquals(2, live.getVersion());

    var reloaded = entityService.read(entity.getId());
    Assertions.assertEquals(1, reloaded.getEntityTypeVersion().getVersion());
    Assertions.assertFalse(
        reloaded.getEntityTypeVersion().getSchema().get("properties").has("value"));

    var valuesWithoutValue =
        """
        { "name": "beta" }
        """;
    entityService.update(entity.getId(), valuesWithoutValue);

    var valuesV2 =
        """
        { "name": "delta", "value": 42 }
        """;
    SchemaValidationError extraPropertyRejected =
        assertThrows(
            SchemaValidationError.class, () -> entityService.update(entity.getId(), valuesV2));
    Assertions.assertFalse(extraPropertyRejected.getErrors().isEmpty());

    SchemaValidationError newEntityMissingValueRejected =
        assertThrows(
            SchemaValidationError.class,
            () -> entityService.create("PinnedType", valuesWithoutValue));
    Assertions.assertFalse(newEntityMissingValueRejected.getErrors().isEmpty());

    var entityV2 = entityService.create("PinnedType", valuesV2);
    Assertions.assertEquals(2, entityV2.getEntityTypeVersion().getVersion());

    SchemaValidationError v2UpdateRejected =
        assertThrows(
            SchemaValidationError.class,
            () ->
                entityService.update(
                    entityV2.getId(),
                    """
                    { "name": "epsilon" }
                    """));
    Assertions.assertFalse(v2UpdateRejected.getErrors().isEmpty());

    entityService.delete(entity.getId());
    entityService.delete(entityV2.getId());
    entityTypeService.delete("PinnedType");
  }

  @Test
  void testDeleteVersionRefusedWhenEntityPinned() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var schemaV1 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" } },
          "required": ["name"]
        }
        """;
    var schemaV2 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" }, "value": { "type": "number" } },
          "required": ["name", "value"]
        }
        """;

    entityTypeService.create("PinnedDeleteType", List.of(), Optional.empty(), schemaV1);
    var entity =
        entityService.create(
            "PinnedDeleteType",
            """
            { "name": "alpha" }
            """);
    Assertions.assertEquals(1, entity.getEntityTypeVersion().getVersion());

    entityTypeService.createVersion("PinnedDeleteType", List.of(), Optional.empty(), schemaV2);

    Assertions.assertThrows(
        ServiceError.class, () -> entityTypeService.deleteVersion("PinnedDeleteType", 1));

    entityService.delete(entity.getId());
    entityTypeService.delete("PinnedDeleteType");
  }
}
