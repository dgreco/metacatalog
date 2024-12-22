package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonSchemaFactory;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.json.JsonAssert;

@SpringBootTest
class EntityTypeServiceTests extends CommonServiceTests {

    @Test
    void testCreateDeleteExists() throws JsonProcessingException, SchemaValidationError, ServiceError {
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository);

        var baseSchema = jsonSchemaFactory
                .getSchema(Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/base_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        entityTypeService.create("NewType", baseSchema, Optional.empty());

        Assertions.assertTrue(entityTypeService.exists("NewType"));

        entityTypeService.delete("NewType");

        Assertions.assertFalse(entityTypeService.exists("NewType"));

        ServiceError exception = assertThrows(ServiceError.class, () -> entityTypeService.delete("UnknownType"));

        Assertions.assertEquals("EntityType UnknownType not found", exception.getMessage());
    }

    @Test
    void testInheritance() throws JsonProcessingException, ServiceError {
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository);

        var inheritedSchema =
                """
                  {
                  "type" : "object",
                  "properties" : {
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

        entityTypeService.create("BaseType", baseSchema, Optional.empty());

        entityTypeService.create("MiddleType", middleSchema, Optional.of("BaseType"));

        var leafType = entityTypeService.create("LeafType", leafSchema, Optional.of("MiddleType"));

        entityTypeService.create("LeafType1", leafSchema, Optional.of("MiddleType"));

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
