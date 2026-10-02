package it.davidgreco.metacatalog.common;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.test.json.JsonAssert;

class JsonUtilsTests {

  private final JsonUtils jsonUtils = newJsonUtils();

  private static JsonNode resource(String path) {
    try (var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonUtils newJsonUtils() {
    var jsonMapper = new ObjectMapper();
    jsonMapper.registerModule(new Jdk8Module());
    var yamlMapper = new ObjectMapper(new YAMLFactory());
    yamlMapper.registerModule(new Jdk8Module());
    return new JsonUtils(jsonMapper, yamlMapper, JsonUtils.newSchemaRegistry());
  }

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

    var baseSchema = resource("jsons/base_schema.json");

    var middleSchema = resource("jsons/middle_schema.json");

    var leafSchema = resource("jsons/leaf_schema.json");

    JsonAssert.comparator(JSONCompareMode.NON_EXTENSIBLE)
        .assertIsMatch(
            jsonUtils
                .mergeSchemas(List.of(baseSchema, middleSchema, leafSchema))
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

    var baseSchema = jsonUtils.schemaOf(resource("jsons/base_schema.json"));

    JsonAssert.comparator(JSONCompareMode.NON_EXTENSIBLE)
        .assertIsMatch(
            convertedSchema,
            jsonUtils.convertToMappingSchema(baseSchema).get().getSchemaNode().toPrettyString());
  }

  @Test
  void stringToJsonSchemaAcceptsValidV202012Schema() {
    var result =
        jsonUtils.stringToJsonSchema(
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
    assertTrue(result.isRight(), "a valid 2020-12 schema must be accepted");
  }

  @Test
  void stringToJsonSchemaRejectsForbiddenKeywords() {
    var result =
        jsonUtils.stringToJsonSchema("{\"type\":\"object\",\"$ref\":\"#/definitions/foo\"}");
    assertTrue(result.isLeft(), "schemas using $ref must be rejected");
    assertTrue(
        result.getLeft().stream().anyMatch(m -> m.contains("not allowed")),
        "the error must mention not-allowed keywords");
  }

  @Test
  void stringToJsonSchemaRejectsInvalidMetaSchema() {
    // "type" must be a string in the 2020-12 meta-schema; an integer is rejected.
    var result = jsonUtils.stringToJsonSchema("{\"type\": 42, \"properties\": {}}");
    assertTrue(
        result.isLeft(), "a schema invalid against the 2020-12 meta-schema must be rejected");
  }

  @Test
  void convertAggregateValuesIntoJsonNodesDoesNotMutateInput() throws Exception {
    var input =
        jsonUtils
            .jsonMapper()
            .readTree(
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

    jsonUtils.convertAggregateValuesIntoJsonNodes(input);

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
