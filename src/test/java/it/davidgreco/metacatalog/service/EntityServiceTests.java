package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;
import static it.davidgreco.metacatalog.common.JsonUtils.jsonSchemaFactory;
import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.json.JsonAssert;

@SpringBootTest
class EntityServiceTests extends CommonServiceTests {

    @Test
    void testCreationAndValidationAndUpdate() throws IOException, ServiceError {
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository, emf);
        var entityService = new EntityService(
                entityTypeRepository, entityRepository, entityRelationshipRepository, traitRelationshipRepository);

        var schema = jsonSchemaFactory
                .getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/simple_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        var values = jsonFactory
                .readTree(Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/simple_doc.json"))
                .toPrettyString();

        var invalidValues = jsonFactory
                .readTree(Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream("jsons/invalid_simple_doc.json"))
                .toPrettyString();

        var testType = entityTypeService.create("TestType", List.of(), Optional.empty(), schema);

        var entity = entityService.create("TestType", values);

        SchemaValidationError exception =
                assertThrows(SchemaValidationError.class, () -> entityService.create("TestType", invalidValues));

        Assertions.assertEquals("$.price: must have an exclusive minimum value of 0", exception.errors.getFirst());

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
        final TraitService traitService = new TraitService(traitRepository, traitRelationshipRepository, emf);
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository, emf);
        var entityService = new EntityService(
                entityTypeRepository, entityRepository, entityRelationshipRepository, traitRelationshipRepository);

        var emptySchema =
                """
                {
                  "type": "object",
                  "properties": {}
                }
                """;

        var emptyValues = """
                {}
                """;

        traitService.create("SourceTrait", emptySchema, Optional.empty());

        traitService.create("TargetTrait", emptySchema, Optional.empty());

        traitService.create("InheritedSourceTrait", emptySchema, Optional.of("SourceTrait"));

        traitService.create("InheritedTargetTrait", emptySchema, Optional.of("TargetTrait"));

        entityTypeService.create("SourceEntityType", List.of("InheritedSourceTrait"), Optional.empty(), emptySchema);

        entityTypeService.create("TargetEntityType", List.of("InheritedTargetTrait"), Optional.empty(), emptySchema);

        traitService.link("SourceTrait", DEPENDS_ON, "TargetTrait");

        var sourceEntity = entityService.create("SourceEntityType", emptyValues);

        var targetEntity = entityService.create("TargetEntityType", emptyValues);

        Assertions.assertThrows(
                ServiceError.class, () -> entityService.link(sourceEntity.getId(), HAS_PART, targetEntity.getId()));

        entityService.link(sourceEntity.getId(), DEPENDS_ON, targetEntity.getId());

        // No loops
        Assertions.assertThrows(
                ServiceError.class, () -> entityService.link(targetEntity.getId(), DEPENDS_ON, sourceEntity.getId()));

        // No multiple link between source and target
        Assertions.assertThrows(
                ServiceError.class, () -> entityService.link(sourceEntity.getId(), DEPENDS_ON, targetEntity.getId()));
    }

    @Test
    public void testList() throws ServiceError {
        final TraitService traitService = new TraitService(traitRepository, traitRelationshipRepository, emf);
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository, emf);
        var entityService = new EntityService(
                entityTypeRepository, entityRepository, entityRelationshipRepository, traitRelationshipRepository);

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

        var e1 = entityService.create(
                "TestType",
                """
                {
                  "a": "a",
                  "b": 1
                }
                """);

        var e2 = entityService.create(
                "TestType",
                """
                {
                  "a": "b",
                  "b": 2
                }
                """);

        var entities1 = entityService.list(
                """
                           $ ? (@.a == "a" && @.b == 1)
                         """);
        Assertions.assertEquals(1, entities1.size());
        Assertions.assertEquals(e1.getId(), entities1.getFirst().getId());

        var entities2 = entityService.list(
                """
                           $ ? (@.a == "b" && @.b > 1)
                         """);
        Assertions.assertEquals(1, entities2.size());
        Assertions.assertEquals(e2.getId(), entities2.getFirst().getId());
    }
}
