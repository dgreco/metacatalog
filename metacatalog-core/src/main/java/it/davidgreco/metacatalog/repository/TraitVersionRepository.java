package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.TraitVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link TraitVersion} history snapshots.
 *
 * <p>Every snapshot shares the {@code version_group_id} of the live {@link
 * it.davidgreco.metacatalog.entity.Trait} it was captured from, so the full version chain of a
 * trait is the set of snapshots with the same group id, ordered by {@code version}.
 */
@Repository
public interface TraitVersionRepository extends JpaRepository<TraitVersion, String> {

  /** Returns the snapshot with the given version number within a version group, if any. */
  Optional<TraitVersion> findByVersionGroupIdAndVersion(String versionGroupId, int version);

  /** Returns all snapshots in a version group, ordered from oldest to newest. */
  List<TraitVersion> findByVersionGroupIdOrderByVersionAsc(String versionGroupId);

  /**
   * Returns the most recent snapshot in a version group (the immediate predecessor of the live
   * row), if any.
   */
  Optional<TraitVersion> findFirstByVersionGroupIdOrderByVersionDesc(String versionGroupId);

  /**
   * Returns the snapshot whose {@code previousVersionId} points to the given snapshot id, i.e. the
   * successor in the chain, if any.
   */
  Optional<TraitVersion> findByPreviousVersionId(String previousVersionId);

  /** Deletes every snapshot in a version group. Used when the live trait is deleted entirely. */
  void deleteByVersionGroupId(String versionGroupId);
}
