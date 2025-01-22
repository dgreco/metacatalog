package it.davidgreco.metacatalog.common;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonSchemaFactory;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.test.json.JsonAssert;

class JsonUtilsTests {

    @Test
    void testMergeSchemas() {

        var mergedSchema =
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
                  "additionalProperties" : false
                }""";

        var baseSchema = jsonSchemaFactory
                .getSchema(Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/base_schema.json"))
                .getSchemaNode();

        var middleSchema = jsonSchemaFactory
                .getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/middle_schema.json"))
                .getSchemaNode();

        var leafSchema = jsonSchemaFactory
                .getSchema(Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/leaf_schema.json"))
                .getSchemaNode();

        JsonAssert.comparator(JSONCompareMode.NON_EXTENSIBLE)
                .assertIsMatch(
                        JsonUtils.mergeSchemas(List.of(baseSchema, middleSchema, leafSchema))
                                .get()
                                .toPrettyString(),
                        mergedSchema);
    }

    @Test
    void testConvertMappingSchema() {

        var convertedSchema =
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
                "struct" : {
                  "type" : "object",
                  "properties" : {
                    "aNumber" : {
                      "type" : "string"
                    },
                    "anInteger" : {
                      "type" : "string"
                    }
                  }
                }
              },
              "required" : [ "street_address", "city", "state" ]
            }
            """;

        var baseSchema = jsonSchemaFactory.getSchema(
                Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/base_schema.json"));

        JsonAssert.comparator(JSONCompareMode.NON_EXTENSIBLE)
                .assertIsMatch(
                        convertedSchema,
                        JsonUtils.convertToMappingSchema(baseSchema)
                                .get()
                                .getSchemaNode()
                                .toPrettyString());
    }
}
