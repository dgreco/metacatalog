package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.EntityVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for {@link EntityVersion} history snapshots. */
@Repository
public interface EntityVersionRepository extends JpaRepository<EntityVersion, String> {

  /**
   * Returns every snapshot of an entity, oldest first.
   *
   * @param entityId the entity id
   * @return the snapshots, empty if the entity has never been versioned
   */
  List<EntityVersion> findByEntityIdOrderByVersionAsc(String entityId);

  /**
   * Returns the snapshot with the given number, if any.
   *
   * @param entityId the entity id
   * @param version the version number
   * @return the snapshot
   */
  Optional<EntityVersion> findByEntityIdAndVersion(String entityId, int version);

  /**
   * Returns the latest snapshot of an entity, if any.
   *
   * @param entityId the entity id
   * @return the snapshot with the highest version number
   */
  Optional<EntityVersion> findFirstByEntityIdOrderByVersionDesc(String entityId);

  /**
   * Returns every snapshot of every entity, grouped by entity and oldest first within each.
   *
   * @return all snapshots
   */
  List<EntityVersion> findAllByOrderByEntityIdAscVersionAsc();

  /**
   * Counts the snapshots validated against a given type version.
   *
   * @param entityTypeVersion the type version snapshot
   * @return the number of entity snapshots referencing it
   */
  long countByEntityTypeVersion(EntityTypeVersion entityTypeVersion);

  /**
   * Deletes every snapshot of an entity.
   *
   * <p>One bulk statement rather than a derived delete: removing the rows one at a time would
   * delete a snapshot while its successor still references it through {@code previous_version_id},
   * and the foreign key is checked per statement.
   *
   * @param entity the entity whose history is removed
   */
  @Modifying
  @Query("DELETE FROM EntityVersion v WHERE v.entity = :entity")
  void deleteByEntity(@Param("entity") Entity entity);
}
