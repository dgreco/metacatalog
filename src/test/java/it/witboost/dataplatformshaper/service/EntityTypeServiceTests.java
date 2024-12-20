package it.witboost.dataplatformshaper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.json.JsonAssert;

@SpringBootTest
class EntityTypeServiceTests extends CommonServiceTests {

    @Test
    void testInheritance() throws JsonProcessingException {
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository);

        var inheritedSchema =
                """
                          {"$id" : "derived_https://example.com/leaf.schema.json",
                          "$schema" : "https://json-schema.org/draft/2020-12/schema",
                          "allOf" : [ {
                            "$id" : "https://example.com/base.schema.json",
                            "$schema" : "https://json-schema.org/draft/2020-12/schema",
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
                              }
                            },
                            "required" : [ "street_address", "city", "state" ]
                          }, {
                            "$id" : "https://example.com/middle.schema.json",
                            "$schema" : "https://json-schema.org/draft/2020-12/schema",
                            "type" : "object",
                            "properties" : {
                              "type" : {
                                "enum" : [ "residential", "business" ]
                              }
                            },
                            "required" : [ "type" ]
                          }, {
                            "$id" : "https://example.com/leaf.schema.json",
                            "$schema" : "https://json-schema.org/draft/2020-12/schema",
                            "type" : "object",
                            "properties" : {
                              "another_property" : {
                                "type" : "string"
                              }
                            },
                            "required" : [ "another_property" ]
                          } ],
                          "properties" : {
                            "street_address" : true,
                            "city" : true,
                            "state" : true,
                            "type" : true,
                            "another_property" : true
                          },
                          "additionalProperties" : false
                        }
                        """;

        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4);

        var baseSchema = factory.getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/base_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        var middleSchema = factory.getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/middle_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        var leafSchema = factory.getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/leaf_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        entityTypeService.create("BaseType", baseSchema, Optional.empty());

        entityTypeService.create("MiddleType", middleSchema, Optional.of("BaseType"));

        var leafType = entityTypeService.create("LeafType", leafSchema, Optional.of("MiddleType"));

        JsonAssert.comparator(JSONCompareMode.STRICT)
                .assertIsMatch(leafType.getSchema().toPrettyString(), inheritedSchema);
    }
}
