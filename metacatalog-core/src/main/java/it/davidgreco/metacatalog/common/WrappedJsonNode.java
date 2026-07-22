package it.davidgreco.metacatalog.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import java.util.Map;

/**
 * A wrapper around a Jackson {@link JsonNode} that provides convenient JSON Path query operations.
 *
 * <p>This record simplifies extracting values from JSON structures using JSON Path expressions,
 * with type-safe value retrieval for common Java types.
 *
 * @param node the underlying JSON node to wrap
 */
public record WrappedJsonNode(JsonNode node) {

  private static final Configuration JSON_PATH_CONFIG =
      Configuration.builder().jsonProvider(new JacksonJsonNodeJsonProvider()).build();

  @FunctionalInterface
  private interface JsonNodeExtractor {
    Object extract(JsonNode node);
  }

  private static final Map<Class<?>, JsonNodeExtractor> EXTRACTORS =
      Map.of(
          String.class, JsonNode::asText,
          Integer.class, JsonNode::asInt,
          Long.class, JsonNode::asLong,
          Float.class, JsonNode::floatValue,
          Double.class, JsonNode::asDouble,
          Boolean.class, JsonNode::booleanValue);

  /**
   * Retrieves a value from the JSON node using a JSON Path expression.
   *
   * @param pathExpression the JSON Path expression (e.g., "$.name" or "$.items[0].id")
   * @return the value at the specified path
   */
  public Object getValue(String pathExpression) {
    var dc = JsonPath.using(JSON_PATH_CONFIG).parse(node);
    return dc.read(pathExpression);
  }

  /**
   * Retrieves a typed value from the JSON node using a JSON Path expression.
   *
   * <p>Supports String, Integer, Long, Float, Double, and Boolean types with automatic conversion
   * from the underlying JSON node types. Adding a new supported type is additive: put an entry in
   * {@link #EXTRACTORS}.
   *
   * @param <T> the expected return type
   * @param clazz the class of the expected return type
   * @param pathExpression the JSON Path expression
   * @return the value at the specified path, cast to the requested type
   */
  public <T> T getValue(Class<T> clazz, String pathExpression) {
    var dc = JsonPath.using(JSON_PATH_CONFIG).parse(node);
    var obj = dc.read(pathExpression);

    if (!(obj instanceof JsonNode jsonNode) || jsonNode.isMissingNode() || jsonNode.isNull()) {
      throw new IllegalArgumentException(
          "Path '" + pathExpression + "' did not resolve to a value");
    }

    var extractor = EXTRACTORS.get(clazz);
    if (extractor != null) {
      var expected =
          switch (clazz.getSimpleName()) {
            case "String" -> "a string";
            case "Integer", "Long", "Float", "Double" -> "a number";
            case "Boolean" -> "a boolean";
            default -> "the expected type";
          };
      requireType(jsonNode, extractor, pathExpression, expected);
      return clazz.cast(extractor.extract(jsonNode));
    }
    return clazz.cast(obj);
  }

  private static void requireType(
      JsonNode node, JsonNodeExtractor extractor, String pathExpression, String expected) {
    boolean ok =
        switch (expected) {
          case "a string" -> node.isTextual();
          case "a number" -> node.isNumber();
          case "a boolean" -> node.isBoolean();
          default -> true;
        };
    if (!ok) {
      throw new IllegalArgumentException(
          "Path '"
              + pathExpression
              + "' did not resolve to "
              + expected
              + " but to "
              + node.getNodeType());
    }
  }
}
