package it.davidgreco.metacatalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Pins the exact text of JSON Schema validation errors, which reaches API clients: every message
 * starts with where the failure is, as a JSONPath for values ({@code $.items[1].name}) and as a
 * JSON Pointer for a schema checked against the meta-schema ({@code /type}). Between major
 * versions, json-schema-validator changed the default form of that location and stopped including
 * it in {@code getMessage()} at all; these are the strings clients have been getting, and the path
 * every call site takes to produce them.
 */
@SpringBootTest
class ValidationMessageFormatTests extends CommonServiceTestingSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String SCHEMA =
      """
      {"type": "object",
       "properties": {
         "a": {"type": "object", "properties": {"b": {"type": "integer"}}},
         "items": {"type": "array", "items": {
           "type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}},
         "my-field": {"type": "string"},
         "my.field": {"type": "string"},
         "my field": {"type": "string"},
         "città": {"type": "string"},
         "count": {"type": "integer"},
         "big": {"type": "integer", "maximum": 10},
         "note": {"type": "string"},
         "when": {"type": "string", "format": "date-time"}},
       "required": ["count"],
       "additionalProperties": false}""";

  ValidationMessageFormatTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  private JsonUtils jsonUtils() {
    return getApplicationContext().getBean(JsonUtils.class);
  }

  private static JsonNode json(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  @Test
  void schemaErrorsAgainstTheMetaSchemaUseJsonPointers() {
    var result = jsonUtils().stringToJsonSchema("{\"type\": 42, \"properties\": {}}");

    assertEquals(
        List.of(
            "/type: does not have a value in the enumeration [\"array\", \"boolean\", \"integer\","
                + " \"null\", \"number\", \"object\", \"string\"]",
            "/type: integer found, array expected"),
        result.getLeft());
  }

  static Stream<Arguments> values() {
    return Stream.of(
        Arguments.of("{}", List.of("$: required property 'count' not found")),
        Arguments.of(
            "{\"count\": 1, \"a\": {\"b\": \"x\"}}",
            List.of("$.a.b: string found, integer expected")),
        Arguments.of(
            "{\"count\": 1, \"items\": [{\"name\": \"ok\"}, {\"name\": 3}, {}]}",
            List.of(
                "$.items[1].name: integer found, string expected",
                "$.items[2]: required property 'name' not found")),
        Arguments.of(
            "{\"count\": 1, \"my-field\": 1}",
            List.of("$.my-field: integer found, string expected")),
        Arguments.of(
            "{\"count\": 1, \"my.field\": 1}",
            List.of("$.my.field: integer found, string expected")),
        Arguments.of(
            "{\"count\": 1, \"my field\": 1}",
            List.of("$.my field: integer found, string expected")),
        Arguments.of(
            "{\"count\": 1, \"città\": 1}", List.of("$.città: integer found, string expected")),
        Arguments.of("{\"count\": 1.0}", List.of()),
        Arguments.of("{\"count\": 1.5}", List.of("$.count: number found, integer expected")),
        Arguments.of("{\"count\": 12345678901234567890123}", List.of()),
        Arguments.of(
            "{\"count\": 1, \"big\": 99999999999999999999}",
            List.of("$.big: must have a maximum value of 10")),
        Arguments.of(
            "{\"count\": 1, \"note\": null}", List.of("$.note: null found, string expected")),
        Arguments.of(
            "{\"count\": 1, \"extra\": true}",
            List.of(
                "$: property 'extra' is not defined in the schema and the schema does not allow"
                    + " additional properties")),
        // Entity values are not checked for formats; mapped values are (see below).
        Arguments.of("{\"count\": 1, \"when\": \"x\"}", List.of()));
  }

  @ParameterizedTest
  @MethodSource("values")
  void valueErrorsUseJsonPaths(String values, List<String> expected) {
    var schema = jsonUtils().stringToJsonSchema(SCHEMA).get();

    if (expected.isEmpty()) {
      SchemaValidationError.validateOrThrow(schema, json(values));
    } else {
      var error =
          assertThrows(
              SchemaValidationError.class,
              () -> SchemaValidationError.validateOrThrow(schema, json(values)));
      assertEquals(expected, error.getErrors());
    }
  }

  @Test
  void mappedValuesAreCheckedForFormats() {
    var schema =
        jsonUtils()
            .stringToJsonSchema(
                "{\"type\": \"object\","
                    + " \"properties\": {\"when\": {\"type\": \"string\", \"format\": \"date-time\"}}}")
            .get();

    var error =
        assertThrows(
            SchemaValidationError.class,
            () ->
                MappingValueEvaluator.generateMappedValues(
                    json("{}"), Map.of(), json("{\"when\": \"'not-a-date'\"}"), schema));
    assertEquals(
        List.of("$.when: does not match the date-time pattern must be a valid RFC 3339 date-time"),
        error.getErrors());
  }

  @Test
  void accessPolicyErrorsUseJsonPaths() {
    var type = new EntityType();
    type.setName("DataProductType");
    var root =
        new Entity(
            type,
            json(
                "{\"principals\": [{\"id\": \"\", \"type\": \"USER\"}],"
                    + " \"grants\": [{\"principals\": [], \"permissions\": [\"READ\"]}]}"));
    root.setId("root-1");

    var error = assertThrows(ServiceError.class, () -> AccessPolicy.of(root));
    assertEquals(
        "Invalid access policy on aggregate root root-1: "
            + "$.principals[0].id: must be at least 1 characters long; "
            + "$.grants[0].principals: must have at least 1 items but found 0",
        error.getMessage());
  }
}
