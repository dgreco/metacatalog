package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.MappingEntityRelationship;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import java.util.List;

/**
 * Contract for the mapping service: CRUD operations on {@link MappingEntityTypeRelationship} and
 * source/target queries.
 *
 * <p>Mapped-entity lifecycle management (create/update/delete cascades) lives in {@link
 * MappedEntityService}; entity-path traversal lives in {@link EntityPathResolver}.
 */
public interface MappingService extends CommonService<MappingEntityTypeRelationship, String> {

  /**
   * Creates a mapping between two entity types, parsing the entity path references from a JSON
   * string. If the pair already has a mapping, it is replaced in place (same id).
   *
   * @param sourceEntityTypeName the source entity type name
   * @param targetEntityTypeName the target entity type name
   * @param mappingValues a JSON string representing the mapping values
   * @param entityPathReferencesJson a JSON string representing the entity path references, or
   *     {@code null}/{@code ""} for an empty list
   * @return the created or replaced mapping
   */
  MappingEntityTypeRelationship create(
      String sourceEntityTypeName,
      String targetEntityTypeName,
      String mappingValues,
      String entityPathReferencesJson);

  /**
   * Creates a mapping between two entity types. A (source, target) pair holds at most one mapping:
   * if one already exists, it is replaced in place — same id, new values and path references,
   * re-validated against the target schema and re-stamped with the current type versions — so
   * mapped entities already derived through it stay attached instead of a second instance being
   * derived alongside them.
   *
   * @param sourceEntityTypeName the source entity type name
   * @param targetEntityTypeName the target entity type name
   * @param mappingValues a JSON string representing the mapping values
   * @param entityPathReferences a list of entity path references
   * @return the created or replaced mapping
   */
  MappingEntityTypeRelationship create(
      String sourceEntityTypeName,
      String targetEntityTypeName,
      String mappingValues,
      List<MappingEntityTypeRelationship.EntityPathReference> entityPathReferences);

  /**
   * Retrieves all mapping entity type relationships.
   *
   * @return all mappings
   */
  List<MappingEntityTypeRelationship> list();

  /**
   * Lists all mapping entity-to-entity relationships.
   *
   * @return all mapping entity relationships
   */
  List<MappingEntityRelationship> listAllEntityRelationships();

  /**
   * Finds all entities that map <em>to</em> the given target entity.
   *
   * @param target the target entity
   * @return a list of mapping sources, each carrying the source entity and its path references
   */
  List<MappingSource> findMappingSources(Entity target);

  /**
   * Checks if the given entity type is a source in any mapping relationship.
   *
   * @param entityType the entity type to check
   * @return {@code true} if the entity type is a source in at least one mapping
   */
  boolean isSourceEntityType(EntityType entityType);

  /**
   * Checks if the given entity type is a target in any mapping relationship.
   *
   * @param entityType the entity type to check
   * @return {@code true} if the entity type is a target in at least one mapping
   */
  boolean isTargetEntityType(EntityType entityType);

  /**
   * A source entity and the path references that describe how to traverse from it to a mapped
   * target.
   *
   * @param source the entity that maps to the target
   * @param pathReferences path references from the mapping type relationship
   */
  record MappingSource(
      Entity source, List<MappingEntityTypeRelationship.EntityPathReference> pathReferences) {}
}
