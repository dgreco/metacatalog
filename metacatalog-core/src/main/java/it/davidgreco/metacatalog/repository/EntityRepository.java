package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
                 AND jsonb_path_exists(values, CAST(:jsonPath AS jsonpath))
               """,
      nativeQuery = true)
  List<Entity> findByEntityTypeIdAndJsonPath(String entityTypeId, String jsonPath);
}
