package it.davidgreco.metacatalog.common;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonPathConfiguration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.jayway.jsonpath.JsonPath;

public record WrappedJsonNode(JsonNode node) {
  public Object getValue(String pathExpression) {
    var dc = JsonPath.using(jsonPathConfiguration).parse(node);
    return dc.read(pathExpression);
  }

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
