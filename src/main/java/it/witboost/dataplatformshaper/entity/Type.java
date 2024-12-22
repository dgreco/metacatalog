package it.witboost.dataplatformshaper.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

public interface Type<T extends Type> {
    Optional<T> getFather();

    JsonNode getBaseSchema();
}
