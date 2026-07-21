package it.davidgreco.metacatalog.common;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;
import static it.davidgreco.metacatalog.common.JsonUtils.jsonSchemaFactory;
import static org.junit.jupiter.api.Assertions.*;

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

    var baseSchema =
        jsonSchemaFactory
            .getSchema(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/base_schema.json"))
            .getSchemaNode();

    var middleSchema =
        jsonSchemaFactory
            .getSchema(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/middle_schema.json"))
            .getSchemaNode();

    var leafSchema =
        jsonSchemaFactory
            .getSchema(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/leaf_schema.json"))
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

    var baseSchema =
        jsonSchemaFactory.getSchema(
            Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("jsons/base_schema.json"));

    JsonAssert.comparator(JSONCompareMode.NON_EXTENSIBLE)
        .assertIsMatch(
            convertedSchema,
            JsonUtils.convertToMappingSchema(baseSchema).get().getSchemaNode().toPrettyString());
  }

  @Test
  void stringToJsonSchemaAcceptsValidV202012Schema() {
    var result =
        JsonUtils.stringToJsonSchema(
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
    assertTrue(result.isRight(), "a valid 2020-12 schema must be accepted");
  }

  @Test
  void stringToJsonSchemaRejectsForbiddenKeywords() {
    var result =
        JsonUtils.stringToJsonSchema("{\"type\":\"object\",\"$ref\":\"#/definitions/foo\"}");
    assertTrue(result.isLeft(), "schemas using $ref must be rejected");
    assertTrue(
        result.getLeft().stream().anyMatch(m -> m.contains("not allowed")),
        "the error must mention not-allowed keywords");
  }

  @Test
  void stringToJsonSchemaRejectsInvalidMetaSchema() {
    // "type" must be a string in the 2020-12 meta-schema; an integer is rejected.
    var result = JsonUtils.stringToJsonSchema("{\"type\": 42, \"properties\": {}}");
    assertTrue(
        result.isLeft(), "a schema invalid against the 2020-12 meta-schema must be rejected");
  }

  @Test
  void convertAggregateValuesIntoJsonNodesDoesNotMutateInput() throws Exception {
    var input =
        jsonFactory.readTree(
            """
            {
              "values": "{\\"a\\": 1}",
              "parts": [
                {
                  "values": "{\\"b\\": 2}"
                }
              ]
            }
            """);

    var originalValuesType = input.get("values").getNodeType();
    var originalPartsType = input.get("parts").getNodeType();

    JsonUtils.convertAggregateValuesIntoJsonNodes(input);

    assertEquals(
        originalValuesType,
        input.get("values").getNodeType(),
        "input values must remain a string after conversion (deep-copy guarantee)");
    assertEquals(
        originalPartsType,
        input.get("parts").getNodeType(),
        "input parts must remain an array after conversion (deep-copy guarantee)");
    assertTrue(
        input.get("values").isTextual(),
        "input values must still be a textual node (the original string), not a parsed object");
  }
}
