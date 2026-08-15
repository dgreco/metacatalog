package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.ProcedureRun;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository interface for {@link ProcedureRun} persistence operations. */
@Repository
public interface ProcedureRunRepository extends JpaRepository<ProcedureRun, String> {

  /**
   * Deletes terminal (non-RUNNING) runs last written before the cutoff. Idempotent: concurrent
   * replicas running the retention job simply race to delete the same rows.
   *
   * @param running the {@link ProcedureRun.State#RUNNING} literal (excluded from deletion)
   * @param cutoff rows with {@code updatedAt} strictly before this instant are removed
   * @return the number of rows deleted
   */
  @Modifying
  @Query("DELETE FROM ProcedureRun r WHERE r.state <> :running AND r.updatedAt < :cutoff")
  int deleteTerminalRunsOlderThan(
      @Param("running") ProcedureRun.State running, @Param("cutoff") Instant cutoff);

  /**
   * Marks RUNNING runs last written before the cutoff as FAILED with the given error. A run that
   * old can no longer be executing: its instance is gone, so the eternal spinner becomes a visible
   * failure. Idempotent across replicas.
   *
   * @param running the {@link ProcedureRun.State#RUNNING} literal (what to match)
   * @param failed the {@link ProcedureRun.State#FAILED} literal (what to set)
   * @param cutoff rows with {@code updatedAt} strictly before this instant are marked
   * @param error the failure message to record
   * @param now the instant stamped into {@code updatedAt}
   * @return the number of rows marked
   */
  @Modifying
  @Query(
      "UPDATE ProcedureRun r SET r.state = :failed, r.error = :error, r.updatedAt = :now"
          + " WHERE r.state = :running AND r.updatedAt < :cutoff")
  int failStaleRunningRuns(
      @Param("running") ProcedureRun.State running,
      @Param("failed") ProcedureRun.State failed,
      @Param("cutoff") Instant cutoff,
      @Param("error") String error,
      @Param("now") Instant now);
}
