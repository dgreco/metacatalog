package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.EntityVersion;
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
   * <p>If the entity's type carries the built-in {@code Versionable} trait and the values change, a
   * new version is recorded.
   *
   * <p>Entities whose type is the target of a mapping relationship cannot be updated through this
   * method — they are managed exclusively by the mapping/provisioning subsystem.
   *
   * @param entityId the entity ID
   * @param values a JSON string containing the new values
   */
  void update(String entityId, String values);

  /**
   * Updates the values of an existing entity without enforcing mapping-target restrictions or
   * emitting lifecycle events. Versioning still applies, exactly as for {@link #update}.
   *
   * <p>This is intended for internal system use (e.g. the provisioning task writing back {@code
   * provisioningStatus}/{@code provisioningResult} to a mapped entity). User-facing updates must go
   * through {@link #update(String, String)}.
   *
   * @param entityId the entity ID
   * @param values a JSON string containing the new values
   */
  void updateValues(String entityId, String values);

  /**
   * Deletes an entity without enforcing the relationship and mapping-role restrictions applied by
   * {@link #delete(String)}.
   *
   * <p>This is intended for internal system use (e.g. {@link AggregateService} tearing down a whole
   * aggregate, whose members legitimately have links and may be mapping sources). The caller is
   * responsible for having already removed every relationship referencing the entity: nothing else
   * cleans them up and the database keeps foreign keys onto it.
   *
   * <p>User-facing deletes must go through {@link #delete(String)}.
   *
   * @param entityId the entity ID
   */
  void deleteInternal(String entityId);

  /**
   * Lists entities of the specified type, optionally filtered by a JSON path query.
   *
   * <p>The entities of frozen copies of past aggregate versions are left out: they are reached
   * through their version, or by id.
   *
   * @param typeName the entity type name
   * @param queryPath a JSON path query; empty string returns all entities of the type
   * @return matching entities
   */
  List<Entity> list(String typeName, String queryPath);

  /**
   * Lists every live entity in the catalog, regardless of type: every entity but those of frozen
   * copies of past aggregate versions.
   *
   * @return all live entities
   */
  List<Entity> listAll();

  /**
   * Lists all relationships between live entities, leaving out the links inside frozen copies of
   * past aggregate versions.
   *
   * @return all relationships between live entities
   */
  List<EntityRelationship> listAllRelationships();

  /**
   * Lists the version history of an entity, oldest first.
   *
   * <p>Only entities whose type carries the built-in {@code Versionable} trait have one. The last
   * element equals the live entity.
   *
   * @param entityId the entity ID
   * @return the snapshots, empty if the entity has never been versioned
   * @throws NotFoundException if the entity does not exist
   */
  List<EntityVersion> listVersions(String entityId);

  /**
   * Reads one version of an entity.
   *
   * @param entityId the entity ID
   * @param version the version number
   * @return the snapshot
   * @throws NotFoundException if the entity or the version does not exist
   */
  EntityVersion readVersion(String entityId, int version);

  /**
   * Lists every recorded version of every entity, grouped by entity and oldest first within each.
   *
   * @return all entity snapshots
   */
  List<EntityVersion> listAllVersions();

  /**
   * Deletes one past version of an entity, and the frozen copy of it unless another remaining
   * version shares that copy.
   *
   * <p>The version's successor is relinked to its predecessor, and version numbers are never
   * reused: the next version recorded still follows the current one.
   *
   * @param entityId the entity ID
   * @param version the number of the version to delete
   * @throws NotFoundException if the entity or the version does not exist
   * @throws ServiceError if the version is the current one, or its frozen copy is still provisioned
   *     or authorized
   */
  void deleteVersion(String entityId, int version);

  /**
   * Deletes every past version of an entity and their frozen copies, keeping the current version.
   *
   * @param entityId the entity ID
   * @throws NotFoundException if the entity does not exist
   * @throws ServiceError if a frozen copy of one of them is still provisioned or authorized
   */
  void deleteAllVersions(String entityId);

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
