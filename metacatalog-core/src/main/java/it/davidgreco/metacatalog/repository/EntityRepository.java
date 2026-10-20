package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for {@link Entity} persistence operations.
 *
 * <p>Provides CRUD operations and custom queries for entities, including queries that leverage
 * PostgreSQL's JSONB path query capabilities.
 */
@Repository
public interface EntityRepository extends JpaRepository<Entity, String> {

  /**
   * Counts the number of entities of a given type.
   *
   * @param entityType the entity type to count instances of
   * @return the number of entities of the specified type
   */
  long countByEntityType(EntityType entityType);

  /**
   * Counts the number of entities pinned to a given entity type version snapshot.
   *
   * @param entityTypeVersion the snapshot to count instances of
   * @return the number of entities pinned to the specified snapshot
   */
  long countByEntityTypeVersion(EntityTypeVersion entityTypeVersion);

  /**
   * Counts the entities of frozen copies of past aggregate versions pinned to a given type version.
   *
   * @param entityTypeVersion the snapshot to count instances of
   * @return the number of frozen entities pinned to it
   */
  long countByEntityTypeVersionAndFrozenVersionOfIsNotNull(EntityTypeVersion entityTypeVersion);

  /**
   * Finds all entities of a given type, eagerly fetching the pinned {@link EntityTypeVersion} so
   * the DTO / graph layers that read it do not trigger a per-entity lazy load (N+1).
   *
   * @param entityType the entity type to search for
   * @return list of entities of the specified type
   */
  @EntityGraph(
      attributePaths = {"entityTypeVersion"},
      type = EntityGraph.EntityGraphType.LOAD)
  List<Entity> findByEntityType(EntityType entityType);

  /** {@inheritDoc} Fetches the pinned {@link EntityTypeVersion} in the same query. */
  @Override
  @EntityGraph(
      attributePaths = {"entityTypeVersion"},
      type = EntityGraph.EntityGraphType.LOAD)
  List<Entity> findAll();

  /** {@inheritDoc} Fetches the pinned {@link EntityTypeVersion} in the same query. */
  @Override
  @EntityGraph(
      attributePaths = {"entityTypeVersion"},
      type = EntityGraph.EntityGraphType.LOAD)
  Optional<Entity> findById(String id);

  /**
   * Takes a {@code SELECT ... FOR UPDATE} lock on an entity's row, so concurrent writers to the
   * same entity are serialised for the rest of the transaction.
   *
   * <p>Used by {@code EntityVersionRecorder} before numbering a new snapshot. If the entity is
   * already managed, the returned instance is that same object and its state is not refreshed: the
   * caller reads anything it needs to be current from the database after taking the lock.
   *
   * @param id the entity id
   * @return the locked entity
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT e FROM Entity e WHERE e.id = :id")
  Optional<Entity> findByIdForUpdate(@Param("id") String id);

  /**
   * Finds entities by type and JSON path expression.
   *
   * <p>Uses PostgreSQL's native JSONB path query functionality to filter entities based on their
   * JSON values.
   *
   * @param entityTypeId the ID of the entity type
   * @param jsonPath the JSON path expression to apply
   * @return list of matching entities
   */
  @Query(
      value =
          """
               SELECT * FROM entity
               WHERE entity_type_id = :entityTypeId
                 AND frozen_version_of IS NULL
                 AND jsonb_path_exists(values, CAST(:jsonPath AS jsonpath))
               """,
      nativeQuery = true)
  List<Entity> findByEntityTypeIdAndJsonPath(String entityTypeId, String jsonPath);

  /**
   * Finds the live entities of a given type: every one but the entities of frozen copies of past
   * aggregate versions, which are reached only through their version.
   *
   * @param entityType the entity type to list instances of
   * @return the live entities of the type
   */
  @EntityGraph(
      attributePaths = {"entityTypeVersion"},
      type = EntityGraph.EntityGraphType.LOAD)
  List<Entity> findByEntityTypeAndFrozenVersionOfIsNull(EntityType entityType);

  /**
   * Finds every live entity, leaving out the entities of frozen copies of past aggregate versions.
   *
   * @return the live entities
   */
  @EntityGraph(
      attributePaths = {"entityTypeVersion"},
      type = EntityGraph.EntityGraphType.LOAD)
  List<Entity> findByFrozenVersionOfIsNull();

  /**
   * Finds the entities of every frozen copy of a root's past versions.
   *
   * @param rootId the id of the live aggregate root
   * @return the entities of all its frozen copies
   */
  List<Entity> findByFrozenVersionOf(String rootId);

  /**
   * Finds the entities of one frozen copy: the copy made for the given version of a root.
   *
   * @param rootId the id of the live aggregate root
   * @param version the version the copy was made for
   * @return the copy's entities
   */
  List<Entity> findByFrozenVersionOfAndFrozenVersion(String rootId, Integer version);

  /**
   * Moves a frozen copy to another version number: when the version a shared copy was made for is
   * deleted, the copy is then named after the earliest version still using it.
   *
   * @param rootId the id of the live aggregate root
   * @param from the version the copy is named after
   * @param to the version to name it after
   */
  @Modifying
  @Query(
      "UPDATE Entity e SET e.frozenVersion = :to"
          + " WHERE e.frozenVersionOf = :rootId AND e.frozenVersion = :from")
  void renumberFrozenCopy(
      @Param("rootId") String rootId, @Param("from") Integer from, @Param("to") Integer to);
}
