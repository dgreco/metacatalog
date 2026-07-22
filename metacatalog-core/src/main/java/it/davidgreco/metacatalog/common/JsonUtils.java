package it.davidgreco.metacatalog.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import io.vavr.Tuple2;
import io.vavr.control.Either;
import java.util.*;

/**
 * Spring-managed JSON utility component providing JSON Schema validation, schema merging, and
 * aggregate value conversion.
 *
 * <p>The underlying {@link ObjectMapper} instances, {@link JsonSchemaFactory}, and JSON Path {@link
 * com.jayway.jsonpath.Configuration} are registered as Spring beans in {@link
 * it.davidgreco.metacatalog.CoreConfig} and injected here, replacing the previous mutable static
 * singletons.
 */
public class JsonUtils {

  /** An empty JSON schema template with no properties defined. */
  public static final String EMPTY_SCHEMA =
      """
            {
              "type": "object",
              "properties": {
              }
            }
            """;

  private final ObjectMapper jsonMapper;
  private final ObjectMapper yamlMapper;
  private final JsonSchemaFactory jsonSchemaFactory;
  private final JsonSchema jsonSchemaSchema;

  public JsonUtils(
      ObjectMapper jsonMapper, ObjectMapper yamlMapper, JsonSchemaFactory jsonSchemaFactory) {
    this.jsonMapper = jsonMapper;
    this.yamlMapper = yamlMapper;
    this.jsonSchemaFactory = jsonSchemaFactory;
    this.jsonSchemaSchema =
        jsonSchemaFactory.getSchema(
            SchemaLocation.of(SchemaId.V202012), SchemaValidatorsConfig.builder().build());
  }

  public ObjectMapper jsonMapper() {
    return jsonMapper;
  }

  public ObjectMapper yamlMapper() {
    return yamlMapper;
  }

  public JsonSchemaFactory jsonSchemaFactory() {
    return jsonSchemaFactory;
  }

  private static final Set<String> notAllowedKeywords =
      Set.of(
          "$schema",
          "$anchor",
          "$ref",
          "$id",
          "if",
          "then",
          "else",
          "dependentRequired",
          "dependentSchemas",
          "allOf",
          "anyOf",
          "oneOf",
          "not",
          "unevaluatedProperties");

  private static final String PROPERTIES = "properties";

  private static final String REQUIRED = "required";

  private static boolean checkNotAllowedKeywords(JsonNode node) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> stringJsonNodeEntry : node.properties()) {
        var key = stringJsonNodeEntry.getKey();
        var value = node.get(key);
        if (notAllowedKeywords.contains(key)) {
          return true;
        } else if (value.isObject()) {
          if (checkNotAllowedKeywords(value)) return true;
        } else if (value.isArray()) {
          var array = value.elements();
          while (array.hasNext()) {
            if (checkNotAllowedKeywords(array.next())) return true;
          }
        }
      }
    }
    return false;
  }

  private static void convertFieldTypeToStringType(JsonNode node) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> entry : node.properties()) {
        var key = entry.getKey();
        var value = node.get(key);
        if (value.isObject()) {
          convertFieldTypeToStringType(value);
        } else if (value.isArray()) {
          var array = value.elements();
          while (array.hasNext()) {
            convertFieldTypeToStringType(array.next());
          }
        } else {
          if (!value.asText().equals("string") && !value.asText().equals("object")) {
            entry.setValue(JsonNodeFactory.instance.textNode("string"));
          }
        }
      }
    }
  }

  public Either<List<String>, JsonSchema> convertToMappingSchema(JsonSchema schema) {
    var node = schema.getSchemaNode().deepCopy();
    convertFieldTypeToStringType(node.get(PROPERTIES));
    return Either.right(jsonSchemaFactory.getSchema(node));
  }

  public Either<List<String>, JsonSchema> stringToJsonSchema(String json) {
    try {
      var schemaNode = jsonMapper.readTree(json);
      var res =
          jsonSchemaSchema.validate(
              schemaNode.toPrettyString(),
              InputFormat.JSON,
              executionContext ->
                  executionContext.getExecutionConfig().setFormatAssertionsEnabled(true));
      if (checkNotAllowedKeywords(schemaNode))
        return Either.left(List.of("The schema contains not allowed keywords"));
      if (res.isEmpty()) return Either.right(jsonSchemaFactory.getSchema(schemaNode));
      else {
        List<String> errors =
            new ArrayList<>(res.stream().map(ValidationMessage::toString).toList());
        return Either.left(errors);
      }
    } catch (JsonProcessingException e) {
      return Either.left(List.of(e.getMessage()));
    }
  }

  public Either<List<String>, JsonNode> mergeSchemas(List<JsonNode> schemas) {
    Map<String, Tuple2<JsonNode, Boolean>> propertiesToMerge = new HashMap<>();
    List<String> propertiesNamesToMerge = new LinkedList<>();
    for (JsonNode node : schemas) {
      if (!node.has(PROPERTIES))
        return Either.left(List.of("The schema must have a properties field"));
      var requiredProperties = new HashSet<String>();
      var maybeRequired = Optional.<JsonNode>empty();
      if (node.has(REQUIRED)) maybeRequired = Optional.of(node.get(REQUIRED));
      maybeRequired.ifPresent(
          required -> {
            if (required.isArray()) {
              required
                  .elements()
                  .forEachRemaining(
                      requiredElement -> requiredProperties.add(requiredElement.asText()));
            }
          });

      node.get(PROPERTIES)
          .properties()
          .iterator()
          .forEachRemaining(
              entry -> {
                var key = entry.getKey();
                var value = entry.getValue();
                var isRequired = requiredProperties.contains(key);
                if (!propertiesToMerge.containsKey(key)) propertiesNamesToMerge.add(key);
                propertiesToMerge.put(key, new Tuple2<>(value, isRequired));
              });
    }
    var mergedSchemaJson = jsonMapper.createObjectNode();
    var properties = jsonMapper.createObjectNode();
    var required = jsonMapper.createArrayNode();

    propertiesNamesToMerge.forEach(
        propertyName -> {
          var tuple = propertiesToMerge.get(propertyName);
          var value = tuple._1();
          properties.set(propertyName, value);
          if (Boolean.TRUE.equals(tuple._2())) required.add(propertyName);
        });
    mergedSchemaJson.put("type", "object");
    mergedSchemaJson.set(PROPERTIES, properties);
    mergedSchemaJson.set(REQUIRED, required);
    mergedSchemaJson.put("additionalProperties", false);
    var res =
        jsonSchemaSchema.validate(
            mergedSchemaJson.toPrettyString(),
            InputFormat.JSON,
            executionContext ->
                executionContext.getExecutionConfig().setFormatAssertionsEnabled(true));
    if (res.isEmpty()) return Either.right(mergedSchemaJson);
    else {
      List<String> errors = new ArrayList<>(res.stream().map(ValidationMessage::toString).toList());
      return Either.left(errors);
    }
  }

  private static final String VALUES = "values";
  private static final String PARTS = "parts";

  public JsonNode convertAggregateValuesIntoJsonNodes(JsonNode jsonNode)
      throws JsonProcessingException {
    var copy = jsonNode.deepCopy();
    convertAggregateValuesIntoJsonNodesInPlace(copy);
    return copy;
  }

  private void convertAggregateValuesIntoJsonNodesInPlace(JsonNode jsonNode)
      throws JsonProcessingException {
    if (jsonNode.has(VALUES)) {
      var values = jsonNode.get(VALUES).asText();
      var valuesJson = yamlMapper.readTree(values);
      ((ObjectNode) jsonNode).set(VALUES, valuesJson);
    }
    if (jsonNode.has(PARTS)) {
      var parts = (ArrayNode) jsonNode.get(PARTS);
      if (parts.isEmpty()) {
        ((ObjectNode) jsonNode).remove(PARTS);
      } else {
        for (JsonNode part : parts) {
          convertAggregateValuesIntoJsonNodesInPlace(part);
        }
      }
    }
  }
}
