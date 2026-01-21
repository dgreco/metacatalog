package it.davidgreco.metacatalog.common;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonPathConfiguration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jayway.jsonpath.JsonPath;

/**
 * A wrapper around a Jackson {@link JsonNode} that provides convenient JSON Path query operations.
 *
 * <p>This record simplifies extracting values from JSON structures using JSON Path expressions,
 * with type-safe value retrieval for common Java types.
 *
 * @param node the underlying JSON node to wrap
 */
public record WrappedJsonNode(JsonNode node) {

  /**
   * Retrieves a value from the JSON node using a JSON Path expression.
   *
   * @param pathExpression the JSON Path expression (e.g., "$.name" or "$.items[0].id")
   * @return the value at the specified path
   */
  public Object getValue(String pathExpression) {
    var dc = JsonPath.using(jsonPathConfiguration).parse(node);
    return dc.read(pathExpression);
  }

  /**
   * Retrieves a typed value from the JSON node using a JSON Path expression.
   *
   * <p>Supports String, Integer, Long, Float, Double, and Boolean types with automatic conversion
   * from the underlying JSON node types.
   *
   * @param <T> the expected return type
   * @param clazz the class of the expected return type
   * @param pathExpression the JSON Path expression
   * @return the value at the specified path, cast to the requested type
   */
  public <T> T getValue(Class<T> clazz, String pathExpression) {
    var dc = JsonPath.using(jsonPathConfiguration).parse(node);
    var obj = dc.read(pathExpression);

    if (clazz == String.class) return clazz.cast(((TextNode) obj).asText());
    else if (clazz == Integer.class) return clazz.cast(((NumericNode) obj).asInt());
    else if (clazz == Long.class) return clazz.cast(((NumericNode) obj).asLong());
    else if (clazz == Float.class) return clazz.cast(((NumericNode) obj).asDouble());
    else if (clazz == Double.class) return clazz.cast(((NumericNode) obj).asDouble());
    else if (clazz == Boolean.class) return clazz.cast(((BooleanNode) obj).booleanValue());
    else return clazz.cast(obj);
  }
}
