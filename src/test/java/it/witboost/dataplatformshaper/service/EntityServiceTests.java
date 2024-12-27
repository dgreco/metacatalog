package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonFactory;
import static it.witboost.dataplatformshaper.common.JsonUtils.jsonSchemaFactory;
import static it.witboost.dataplatformshaper.entity.RelationType.DEPENDS_ON;
import static it.witboost.dataplatformshaper.entity.RelationType.HAS_PART;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class EntityServiceTests extends CommonServiceTests {

    @Test
    void testCreationAndValidation() throws IOException, ServiceError {
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);
        final EntityService entityService = new EntityService(
                entityTypeRepository, entityRepository, traitRelationshipRepository, entityRelationshipRepository);

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

        entityTypeService.create("TestType", List.of(), Optional.empty(), schema);

        var entity = entityService.create("TestType", values);

        SchemaValidationError exception =
                assertThrows(SchemaValidationError.class, () -> entityService.create("TestType", invalidValues));

        Assertions.assertEquals("$.price: must have an exclusive minimum value of 0", exception.errors.get(0));

        Assertions.assertEquals(1, entityService.countEntitiesByEntityType("TestType"));

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> entityTypeService.delete("TestType"));

        entityService.delete(entity.getId());

        entityTypeService.delete("TestType");

        Assertions.assertEquals(0, entityService.countEntitiesByEntityType("TestType"));
    }

    @Test
    void testLinkUnlinkLinkedEntities() throws ServiceError {
        final TraitService traitService = new TraitService(traitRepository, traitRelationshipRepository);

        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);

        final EntityService entityService = new EntityService(
                entityTypeRepository, entityRepository, traitRelationshipRepository, entityRelationshipRepository);

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
}
