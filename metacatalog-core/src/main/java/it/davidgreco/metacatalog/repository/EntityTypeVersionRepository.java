package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link EntityTypeVersion} history snapshots.
 *
 * <p>Every snapshot shares the {@code version_group_id} of the live {@link
 * it.davidgreco.metacatalog.entity.EntityType} it was captured from, so the full version chain of a
 * type is the set of snapshots with the same group id, ordered by {@code version}.
 */
@Repository
public interface EntityTypeVersionRepository extends JpaRepository<EntityTypeVersion, String> {

  /** Returns the snapshot with the given version number within a version group, if any. */
  Optional<EntityTypeVersion> findByVersionGroupIdAndVersion(String versionGroupId, int version);

  /** Returns all snapshots in a version group, ordered from oldest to newest. */
  List<EntityTypeVersion> findByVersionGroupIdOrderByVersionAsc(String versionGroupId);

  /**
   * Returns the most recent snapshot in a version group (the immediate predecessor of the live
   * row), if any.
   */
  Optional<EntityTypeVersion> findFirstByVersionGroupIdOrderByVersionDesc(String versionGroupId);

  /**
   * Returns the snapshot whose {@code previousVersionId} points to the given snapshot id, i.e. the
   * successor in the chain, if any.
   */
  Optional<EntityTypeVersion> findByPreviousVersionId(String previousVersionId);

  /** Deletes every snapshot in a version group. Used when the live type is deleted entirely. */
  void deleteByVersionGroupId(String versionGroupId);
}
