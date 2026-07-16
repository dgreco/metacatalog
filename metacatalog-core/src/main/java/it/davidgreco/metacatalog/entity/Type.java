package it.davidgreco.metacatalog.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Common interface for types that support inheritance and schema definition.
 *
 * <p>This interface is implemented by both {@link EntityType} and {@link Trait}, providing a common
 * abstraction for types that can have a parent (father), mixed-in traits, and define a JSON schema.
 * The father plays the role of a Scala superclass while the traits play the role of Scala mixins,
 * which lets a type's effective schema be computed through a Scala-style linearization of its
 * ancestors.
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
   * Returns the unique name of this type.
   *
   * @return the name of the type
   */
  String getName();

  /**
   * Returns the base JSON schema declared directly by this type, without any inherited or mixed-in
   * contributions.
   *
   * @return the base JSON schema of this type
   */
  JsonNode getBaseSchema();

  /**
   * Returns the effective JSON schema for this type.
   *
   * @return the JSON schema that defines the structure for instances of this type
   */
  JsonNode getSchema();

  /**
   * Returns the traits mixed into this type, in declaration order (the last trait has the highest
   * precedence, following Scala mixin semantics).
   *
   * <p>Types without mixins (such as {@link Trait}) inherit the default empty implementation.
   *
   * @return the mixed-in traits, or an empty list if this type has none
   */
  default List<? extends Type<?>> getTraits() {
    return List.of();
  }
}
