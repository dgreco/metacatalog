package it.witboost.dataplatformshaper.common;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonPathConfiguration;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;

public record WrappedJsonNode(JsonNode node) {
    public Object getValue(String pathExpression) {
        var dc = JsonPath.using(jsonPathConfiguration).parse(node);
        return dc.read(pathExpression);
    }
}
