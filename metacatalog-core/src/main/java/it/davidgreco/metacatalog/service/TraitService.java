package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitVersion;
import java.util.List;
import java.util.Optional;

/**
 * Contract for the trait service: CRUD, schema versioning, relationship linking, and trait-level
 * queries on {@link Trait}.
 */
public interface TraitService extends CommonTypeService<Trait, String> {

  /**
   * Creates a new trait with an optional schema and optional father.
   *
   * @param name the trait name
   * @param schema an optional JSON schema string
   * @param fatherName an optional father trait name
   * @return the created trait
   */
  Trait create(String name, Optional<String> schema, Optional<String> fatherName);

  /**
   * Creates a new version of an existing trait, snapshotting the current live state.
   *
   * @param name the trait name
   * @param schema the new optional JSON schema string
   * @param fatherName the new optional father name
   * @return the mutated (now current) live trait
   */
  Trait createVersion(String name, Optional<String> schema, Optional<String> fatherName);

  /**
   * Reads a specific version of a trait.
   *
   * @param name the trait name
   * @param version the version number
   * @return a {@link VersionResult} wrapping either the live trait or the snapshot
   */
  VersionResult<Trait, TraitVersion> readVersion(String name, int version);

  /**
   * Lists every version of a trait, oldest first.
   *
   * @param name the trait name
   * @return a list of version results, snapshots first, live last
   */
  List<VersionResult<Trait, TraitVersion>> listVersions(String name);

  /**
   * Deletes a specific historical version of a trait.
   *
   * @param name the trait name
   * @param version the version number
   */
  void deleteVersion(String name, int version);

  /**
   * Deletes every historical snapshot, keeping the live row.
   *
   * @param name the trait name
   */
  void deleteAllVersions(String name);

  /**
   * Retrieves all traits.
   *
   * @return all traits
   */
  List<Trait> list();

  List<it.davidgreco.metacatalog.entity.TraitVersion> listAllVersions();

  /**
   * Links two traits with the given relation type.
   *
   * @param sourceTraitName the source trait name
   * @param relType the relation type
   * @param targetTraitName the target trait name
   */
  void link(String sourceTraitName, RelationType relType, String targetTraitName);

  /**
   * Removes a link between two traits.
   *
   * @param sourceTraitName the source trait name
   * @param relType the relation type
   * @param targetTraitName the target trait name
   */
  void unlink(String sourceTraitName, RelationType relType, String targetTraitName);

  /**
   * Retrieves traits linked to the given source trait with the specified relation type.
   *
   * @param traitName1 the source trait name
   * @param relType the relation type
   * @return linked target traits
   */
  List<Trait> linked(String traitName1, RelationType relType);

  /**
   * Lists all trait relationships.
   *
   * @return all trait relationships
   */
  List<it.davidgreco.metacatalog.entity.TraitRelationship> listAllRelationships();

  /**
   * Counts the number of child traits inheriting from the given trait.
   *
   * @param name the parent trait name
   * @return the child count
   */
  long countTraitChildren(String name);
}
