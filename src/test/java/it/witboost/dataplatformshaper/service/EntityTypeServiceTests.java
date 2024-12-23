package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonSchemaFactory;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.json.JsonAssert;

@SpringBootTest
class EntityTypeServiceTests extends CommonServiceTests {

    @Autowired
    private TraitService traitService;

    @Test
    void testCreateDeleteExists() throws JsonProcessingException, SchemaValidationError, ServiceError {
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);

        var baseSchema = jsonSchemaFactory
                .getSchema(Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/base_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        entityTypeService.create("NewType", List.of(), Optional.empty(), baseSchema);

        Assertions.assertTrue(entityTypeService.exists("NewType"));

        entityTypeService.delete("NewType");

        Assertions.assertFalse(entityTypeService.exists("NewType"));

        ServiceError exception = assertThrows(ServiceError.class, () -> entityTypeService.delete("UnknownType"));

        Assertions.assertEquals("EntityType UnknownType not found", exception.getMessage());
    }

    @Test
    void testInheritance() throws JsonProcessingException, ServiceError {
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);

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

        var baseSchema = jsonSchemaFactory
                .getSchema(Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/base_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        var middleSchema = jsonSchemaFactory
                .getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/middle_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        var leafSchema = jsonSchemaFactory
                .getSchema(Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/leaf_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        var traitSchema1 = jsonSchemaFactory
                .getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/trait_schema1.json"))
                .getSchemaNode()
                .toPrettyString();

        var trait1 = traitService.create("Trait1", traitSchema1, Optional.empty());

        entityTypeService.create("BaseType", List.of("Trait1"), Optional.empty(), baseSchema);

        entityTypeService.create("MiddleType", List.of(), Optional.of("BaseType"), middleSchema);

        var leafType = entityTypeService.create("LeafType", List.of(), Optional.of("MiddleType"), leafSchema);

        entityTypeService.create("LeafType1", List.of(), Optional.of("MiddleType"), leafSchema);

        Assertions.assertEquals(2, entityTypeService.countEntityTypeChildren("MiddleType"));

        JsonAssert.comparator(JSONCompareMode.NON_EXTENSIBLE)
                .assertIsMatch(leafType.getSchema().toPrettyString(), inheritedSchema);

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> entityTypeService.delete("MiddleType"));

        entityTypeService.delete("LeafType");

        Assertions.assertEquals(1, entityTypeService.countEntityTypeChildren("MiddleType"));

        entityTypeService.delete("LeafType1");

        Assertions.assertEquals(0, entityTypeService.countEntityTypeChildren("MiddleType"));

        entityTypeService.delete("MiddleType");

        entityTypeService.delete("BaseType");
    }
}
