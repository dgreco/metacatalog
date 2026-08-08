package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.EntityType;
import java.util.List;
import java.util.Optional;

/**
 * The ability to create immutable entity types, kept deliberately out of {@link EntityTypeService}
 * for the reasons set out on {@link ImmutableTraitWriter}.
 */
public interface ImmutableEntityTypeWriter {

  /**
   * Creates an entity type that can afterwards be neither deleted nor versioned. Its entities stay
   * fully mutable — the flag freezes the definition, not the data.
   *
   * @param name the type name
   * @param traits trait names to associate
   * @param fatherName optional father type name
   * @param schema the base JSON schema string
   * @return the created immutable entity type
   */
  EntityType createImmutable(
      String name, List<String> traits, Optional<String> fatherName, String schema);
}
