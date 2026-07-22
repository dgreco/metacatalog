package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import java.util.List;
import java.util.Optional;

/**
 * Contract for the entity-type service: CRUD, schema versioning, and type-level queries on {@link
 * EntityType}.
 */
public interface EntityTypeService extends CommonTypeService<EntityType, String> {

  /**
   * Creates a new entity type with the specified traits, optional father, and base schema.
   *
   * @param name the type name
   * @param traits trait names to associate
   * @param fatherName optional father type name
   * @param schema the base JSON schema string
   * @return the created entity type
   */
  EntityType create(String name, List<String> traits, Optional<String> fatherName, String schema);

  /**
   * Creates a new version of an existing entity type, snapshotting the current live state.
   *
   * @param name the type name
   * @param traits the new trait names
   * @param fatherName the new optional father name
   * @param schema the new base JSON schema string
   * @return the mutated (now current) live entity type
   */
  EntityType createVersion(
      String name, List<String> traits, Optional<String> fatherName, String schema);

  /**
   * Reads a specific version of an entity type.
   *
   * @param name the type name
   * @param version the version number
   * @return a {@link VersionResult} wrapping either the live entity type or the snapshot
   */
  VersionResult<EntityType, EntityTypeVersion> readVersion(String name, int version);

  /**
   * Lists every version of an entity type, oldest first.
   *
   * @param name the type name
   * @return a list of version results, snapshots first, live last
   */
  List<VersionResult<EntityType, EntityTypeVersion>> listVersions(String name);

  /**
   * Deletes a specific historical version of an entity type.
   *
   * @param name the type name
   * @param version the version number
   */
  void deleteVersion(String name, int version);

  /**
   * Deletes every historical snapshot, keeping the live row.
   *
   * @param name the type name
   */
  void deleteAllVersions(String name);

  /**
   * Retrieves all entity types.
   *
   * @return all entity types
   */
  List<EntityType> list();

  List<it.davidgreco.metacatalog.entity.EntityTypeVersion> listAllVersions();

  /**
   * Counts the number of child entity types inheriting from the given type.
   *
   * @param name the parent type name
   * @return the child count
   */
  long countEntityTypeChildren(String name);
}
