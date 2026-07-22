package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.List;

/**
 * Contract for the entity service: CRUD, relationship linking, and list/query operations on {@link
 * Entity} instances.
 */
public interface EntityService extends CommonService<Entity, String> {

  /**
   * Creates a new entity of the given type, validating {@code values} against the type's effective
   * schema.
   *
   * @param typeName the name of the entity type
   * @param values a JSON string containing the entity values
   * @return the created entity
   */
  Entity create(String typeName, String values);

  /**
   * Updates the values of an existing entity.
   *
   * @param entityId the entity ID
   * @param values a JSON string containing the new values
   */
  void update(String entityId, String values);

  /**
   * Lists entities of the specified type, optionally filtered by a JSON path query.
   *
   * @param typeName the entity type name
   * @param queryPath a JSON path query; empty string returns all entities of the type
   * @return matching entities
   */
  List<Entity> list(String typeName, String queryPath);

  List<Entity> listAll();

  /**
   * Lists all entity-to-entity relationships.
   *
   * @return all entity relationships
   */
  List<EntityRelationship> listAllRelationships();

  /**
   * Links two entities with the given relation type.
   *
   * @param sourceId the source entity ID
   * @param relType the relation type
   * @param targetId the target entity ID
   */
  void link(String sourceId, RelationType relType, String targetId);

  /**
   * Removes a link between two entities.
   *
   * @param sourceId the source entity ID
   * @param relType the relation type
   * @param targetId the target entity ID
   */
  void unlink(String sourceId, RelationType relType, String targetId);

  /**
   * Retrieves entities linked to the given source entity with the specified relation type.
   *
   * @param sourceId the source entity ID
   * @param relType the relation type
   * @return linked target entities
   */
  List<Entity> linked(String sourceId, RelationType relType);
}
