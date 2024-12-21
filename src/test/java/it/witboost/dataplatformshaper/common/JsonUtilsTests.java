package it.witboost.dataplatformshaper.common;

import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.test.json.JsonAssert;

import java.util.List;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonSchemaFactory;

public class JsonUtilsTests {

    @Test
    void testMergeSchemas() {

        var mergedSchema =
                """
                  {
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
                    },
                    "type" : {
                      "enum" : [ "residential", "business" ]
                    },
                    "another_property" : {
                      "type" : "string"
                    }
                  },
                  "required" : [ "street_address", "city", "state", "type", "another_property" ]
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

        JsonAssert.comparator(JSONCompareMode.STRICT)
                .assertIsMatch(
                        JsonUtils.mergeSchemas(List.of(baseSchema, middleSchema, leafSchema))
                                .get()
                                .toPrettyString(),
                        mergedSchema);
    }
}
