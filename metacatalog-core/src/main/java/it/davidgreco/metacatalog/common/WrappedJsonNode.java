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

        if (clazz == String.class) return (T) ((TextNode) obj).asText();
        else if (clazz == Integer.class) return (T) Integer.valueOf(((IntNode) obj).intValue());
        else if (clazz == Long.class) return (T) Long.valueOf(((LongNode) obj).intValue());
        else if (clazz == Float.class) return (T) Float.valueOf(((FloatNode) obj).intValue());
        else if (clazz == Double.class) return (T) Double.valueOf(((DoubleNode) obj).intValue());
        else if (clazz == Boolean.class) return (T) Boolean.valueOf(((BooleanNode) obj).booleanValue());
        else return (T) obj;
    }
}
