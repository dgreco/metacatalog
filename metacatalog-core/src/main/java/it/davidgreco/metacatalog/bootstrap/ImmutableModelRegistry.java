package it.davidgreco.metacatalog.bootstrap;

import it.davidgreco.metacatalog.entity.RelationType;
import java.util.List;

/**
 * What an {@link ImmutableModelContributor} declares into. Every declaration states "this must
 * exist, and it must be immutable"; the installer creates it if it is missing and leaves it alone
 * if it is already there.
 *
 * <p>An empty JSON Schema is used wherever a schema is omitted, which is the common case for a
 * trait that exists only to be mixed in as a marker.
 */
public interface ImmutableModelRegistry {

  /** The schema given to a trait or entity type declared without one. */
  String EMPTY_SCHEMA = "{\"type\": \"object\", \"properties\": {}}";

  /**
   * Declares an immutable trait.
   *
   * @param name the trait name
   * @param schema the JSON Schema string, or {@code null} for {@link #EMPTY_SCHEMA}
   * @param fatherName the parent trait name, or {@code null} for a root trait. It must already
   *     exist — declare it earlier in the same {@code contribute} call, or in a module ordered
   *     before this one.
   */
  void trait(String name, String schema, String fatherName);

  /**
   * Declares a root immutable trait with an empty schema — the marker-trait case.
   *
   * @param name the trait name
   */
  default void trait(String name) {
    trait(name, null, null);
  }

  /**
   * Declares a root immutable trait.
   *
   * @param name the trait name
   * @param schema the JSON Schema string, or {@code null} for {@link #EMPTY_SCHEMA}
   */
  default void trait(String name, String schema) {
    trait(name, schema, null);
  }

  /**
   * Declares an immutable entity type.
   *
   * @param name the entity type name
   * @param schema the base JSON Schema string, or {@code null} for {@link #EMPTY_SCHEMA}
   * @param traits the names of the traits mixed in, in precedence order; may be {@code null}
   * @param fatherName the parent entity type name, or {@code null} for a root type
   */
  void entityType(String name, String schema, List<String> traits, String fatherName);

  /**
   * Declares a root immutable entity type.
   *
   * @param name the entity type name
   * @param schema the base JSON Schema string, or {@code null} for {@link #EMPTY_SCHEMA}
   * @param traits the names of the traits mixed in, in precedence order; may be {@code null}
   */
  default void entityType(String name, String schema, List<String> traits) {
    entityType(name, schema, traits, null);
  }

  /**
   * Declares an immutable relationship between two traits. The inverse row is created alongside it
   * and frozen too, so neither direction can be unlinked.
   *
   * @param sourceTrait the source trait name
   * @param relType the relation type
   * @param targetTrait the target trait name
   */
  void link(String sourceTrait, RelationType relType, String targetTrait);
}
