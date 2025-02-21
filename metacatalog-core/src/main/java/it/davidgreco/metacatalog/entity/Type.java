package it.davidgreco.metacatalog.entity;

import com.fasterxml.jackson.databind.JsonNode;

public interface Type<T extends Type<T>> {

  T getFather();

  JsonNode getSchema();
}
