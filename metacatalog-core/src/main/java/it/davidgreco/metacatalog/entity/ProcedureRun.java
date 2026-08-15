package it.davidgreco.metacatalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Durable status record of an asynchronously launched procedure run.
 *
 * <p>One row per schedule id, written by the instance executing the run and readable by every
 * instance, so a status poll can be answered by any replica and the outcome survives restarts. Rows
 * are removed (terminal states) or marked {@link State#FAILED} (orphaned {@link State#RUNNING}
 * rows, whose executing instance is gone) by the scheduled retention job in {@code
 * ProcedureExecutor}.
 *
 * <p>No optimistic locking: the only concurrent writers are the completion callback and the
 * retention job marking a run stale, and with a retention measured in hours the race window is
 * negligible — last writer wins.
 */
@Entity
@Table(name = "procedure_run")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ProcedureRun {

  /** The lifecycle of a run. */
  public enum State {
    RUNNING,
    SUCCEEDED,
    FAILED
  }

  /**
   * The schedule id handed to the caller for polling. Assigned by the executor, never generated.
   */
  @Id
  @Column(name = "id", nullable = false)
  private String id;

  /** The current state of the run. */
  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private State state;

  /** The failure message when {@link #state} is {@link State#FAILED}. */
  @Column(name = "error")
  private String error;

  /** The name of the procedure that was launched. Informational only, never exposed over REST. */
  @Column(name = "procedure_name", nullable = false)
  private String procedureName;

  /** The id of the entity the procedure ran on. Informational only, never exposed over REST. */
  @Column(name = "entity_id", nullable = false)
  private String entityId;

  /** When the run was launched. */
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** When the row was last written; drives the retention job's cutoffs. */
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Creates a new run record.
   *
   * @param id the schedule id
   * @param state the initial state
   * @param error the failure message, or {@code null}
   * @param procedureName the launched procedure's name
   * @param entityId the target entity's id
   */
  public ProcedureRun(String id, State state, String error, String procedureName, String entityId) {
    this.id = id;
    this.state = state;
    this.error = error;
    this.procedureName = procedureName;
    this.entityId = entityId;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }
}
