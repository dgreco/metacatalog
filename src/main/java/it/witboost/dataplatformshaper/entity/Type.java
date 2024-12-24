package it.witboost.dataplatformshaper.entity;

import com.fasterxml.jackson.databind.JsonNode;

public interface Type<T extends Type> {

    T getFather();

    JsonNode getBaseSchema();
}
