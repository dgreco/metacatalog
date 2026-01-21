package it.davidgreco.metacatalog.entity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Common interface for types that support inheritance and schema definition.
 *
 * <p>This interface is implemented by both {@link EntityType} and {@link Trait}, providing a common
 * abstraction for types that can have a parent (father) and define a JSON schema.
 *
 * @param <T> the self-referential type parameter for the implementing class
 */
public interface Type<T extends Type<T>> {

  /**
   * Returns the parent type from which this type inherits.
   *
   * @return the parent type, or null if this is a root type
   */
  T getFather();

  /**
   * Returns the effective JSON schema for this type.
   *
   * @return the JSON schema that defines the structure for instances of this type
   */
  JsonNode getSchema();
}
