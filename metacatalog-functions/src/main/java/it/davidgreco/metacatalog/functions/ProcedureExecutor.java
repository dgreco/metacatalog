package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.CoreConfigProperties;
import it.davidgreco.metacatalog.entity.ProcedureRun;
import it.davidgreco.metacatalog.repository.ProcedureRunRepository;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TaskManager;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service for executing registered entity procedures.
 *
 * <p>Procedures are discovered from the Spring application context: every {@link EntityProcedure}
 * bean is registered under its {@link EntityProcedure#name()}. This removes the previous reliance
 * on a process-global static registry populated from a class-loading-dependent static initializer,
 * which could silently miss lazily-created procedure beans and leaked state across tests.
 *
 * <p>Execution is split into two phases so the plan-building transaction does not overlap with
 * async task execution:
 *
 * <ol>
 *   <li><b>Plan-building</b> (transactional) — the procedure reads the entity graph and builds its
 *       plan. This runs in a transaction because there is no open-session-in-view here, and lazy
 *       associations (entity type, traits, mapping rules, relationship endpoints) need an open
 *       session to avoid {@code LazyInitializationException}.
 *   <li><b>Async execution</b> (non-transactional) — if the procedure returned a schedule ID, the
 *       executor joins the schedule and checks its result <em>after</em> the plan-building
 *       transaction has committed. Previously {@code joinSchedule} was called from inside the
 *       procedure, which blocked the transaction thread (and held its DB connection) for the entire
 *       async execution — risking pool exhaustion / deadlock since the async tasks themselves need
 *       connections from the same pool.
 * </ol>
 *
 * @see EntityProcedure
 */
@Slf4j
@Service
public class ProcedureExecutor {

  /** Registry mapping procedure names to their (Spring-managed) implementations. */
  private final Map<String, EntityProcedure> procedureRegistry;

  /** Entity service for reading entities by ID. */
  private final EntityService entityService;

  private final PlatformTransactionManager transactionManager;

  /**
   * Durable status registry of asynchronously launched procedure runs, one {@link ProcedureRun} row
   * per schedule ID. Shared by every application instance — any replica can answer a status poll,
   * and a run's outcome survives restarts. Rows are removed (terminal states) or marked FAILED
   * (orphaned RUNNING rows) by {@link #cleanupProcedureRuns()} after {@code
   * application.config.procedureRunRetention}, so a poll after cleanup gets 404, not a leak.
   */
  private final ProcedureRunRepository procedureRunRepository;

  /** Retention for run rows; also the staleness threshold for orphaned RUNNING rows. */
  private final CoreConfigProperties coreConfigProperties;

  /** The error recorded on a RUNNING row whose executing instance is gone. */
  public static final String STALE_RUN_ERROR =
      "run lost: no outcome was recorded within the retention period"
          + " (the instance executing it likely terminated)";

  private TransactionTemplate transactionTemplate;

  /**
   * Builds the procedure registry from all {@link EntityProcedure} beans in the context.
   *
   * @param procedures every procedure bean, injected by Spring
   * @param entityService entity service for reading entities by ID
   * @param transactionManager the transaction manager used to scope plan-building
   * @param procedureRunRepository the durable status registry of async runs
   * @param coreConfigProperties retention for async-run status rows
   */
  public ProcedureExecutor(
      List<EntityProcedure> procedures,
      EntityService entityService,
      PlatformTransactionManager transactionManager,
      ProcedureRunRepository procedureRunRepository,
      CoreConfigProperties coreConfigProperties) {
    this.procedureRegistry =
        procedures.stream()
            .collect(Collectors.toUnmodifiableMap(EntityProcedure::name, Function.identity()));
    this.entityService = entityService;
    this.transactionManager = transactionManager;
    this.procedureRunRepository = procedureRunRepository;
    this.coreConfigProperties = coreConfigProperties;
  }

  @PostConstruct
  void initTransactionTemplate() {
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Executes a registered procedure on an entity.
   *
   * @param procedureName the name of the procedure to execute
   * @param entityId the ID of the entity to process
   * @throws ServiceError if the procedure is not found, the entity is not found, the plan-building
   *     fails, or an async task fails
   */
  public void executeProcedure(String procedureName, String entityId) {
    var scheduleHandle = executeInTransaction(entityId, lookup(procedureName));
    scheduleHandle.ifPresent(this::joinAndCheck);
  }

  /**
   * Launches a registered procedure asynchronously: the plan is still built (and validated)
   * synchronously in a transaction — a missing procedure, a missing entity or an illegitimate
   * aggregate fails here, on the caller's thread — but the task execution is not joined. The
   * outcome is recorded in the status registry when the schedule completes.
   *
   * @param procedureName the name of the procedure to execute
   * @param entityId the ID of the entity to process
   * @return the schedule ID to poll via {@link #procedureStatus(String)}
   * @throws ServiceError if the procedure is not found, the entity is not found, or plan-building
   *     fails
   */
  public String executeProcedureAsync(String procedureName, String entityId) {
    var scheduleHandle = executeInTransaction(entityId, lookup(procedureName));
    if (scheduleHandle.isEmpty()) {
      // Nothing to execute (an empty aggregate): the run is trivially complete.
      var id = UUID.randomUUID().toString();
      procedureRunRepository.save(
          new ProcedureRun(id, ProcedureRun.State.SUCCEEDED, null, procedureName, entityId));
      return id;
    }
    var handle = scheduleHandle.get();
    // The RUNNING row is committed before the completion callback is attached, so the terminal
    // update can never be overwritten by it, and the caller's first poll always finds the row.
    procedureRunRepository.save(
        new ProcedureRun(handle.id(), ProcedureRun.State.RUNNING, null, procedureName, entityId));
    handle
        .future()
        .whenComplete(
            (result, throwable) -> {
              if (throwable != null) {
                recordOutcome(handle.id(), throwable.getMessage(), procedureName, entityId, true);
              } else if (result.isFailure()) {
                var cause = result.getCause();
                recordOutcome(
                    handle.id(),
                    cause != null ? cause.getMessage() : "unknown",
                    procedureName,
                    entityId,
                    true);
              } else {
                recordOutcome(handle.id(), null, procedureName, entityId, false);
              }
            });
    return handle.id();
  }

  /**
   * Records a run's terminal state, overwriting the RUNNING row in place. Runs on the async
   * executor thread once the schedule completes. If the retention job already marked the run FAILED
   * as stale — or even deleted it — the real outcome wins: the row is updated or re-created, never
   * silently dropped.
   */
  private void recordOutcome(
      String scheduleId, String error, String procedureName, String entityId, boolean failed) {
    var state = failed ? ProcedureRun.State.FAILED : ProcedureRun.State.SUCCEEDED;
    var run =
        procedureRunRepository
            .findById(scheduleId)
            .orElseGet(() -> new ProcedureRun(scheduleId, state, error, procedureName, entityId));
    run.setState(state);
    run.setError(error);
    run.setUpdatedAt(Instant.now());
    procedureRunRepository.save(run);
  }

  /**
   * Looks up the status of an asynchronously launched run.
   *
   * @param scheduleId the ID returned by {@link #executeProcedureAsync(String, String)}
   * @return the status, or empty if the ID is unknown or its row was removed by retention
   */
  public Optional<ProcedureStatus> procedureStatus(String scheduleId) {
    return procedureRunRepository
        .findById(scheduleId)
        .map(
            run ->
                new ProcedureStatus(
                    run.getId(), ProcedureState.valueOf(run.getState().name()), run.getError()));
  }

  /**
   * Retention job for the durable run registry. Marks RUNNING rows older than {@code
   * procedureRunRetention} as FAILED — a run that old has lost the instance executing it, and a
   * visible failure beats an eternal spinner — then deletes terminal rows older than the same
   * cutoff. Freshly marked rows get a new {@code updatedAt}, so they stay pollable for one more
   * retention period before deletion. Both statements are idempotent, so concurrent replicas need
   * no coordination; the (negligible, retention-sized) race where the job marks a run stale just as
   * it genuinely completes is resolved by {@link #recordOutcome} writing last.
   */
  @Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
  @Transactional
  public void cleanupProcedureRuns() {
    var cutoff = Instant.now().minus(coreConfigProperties.procedureRunRetention());
    var failed =
        procedureRunRepository.failStaleRunningRuns(
            ProcedureRun.State.RUNNING,
            ProcedureRun.State.FAILED,
            cutoff,
            STALE_RUN_ERROR,
            Instant.now());
    var deleted =
        procedureRunRepository.deleteTerminalRunsOlderThan(ProcedureRun.State.RUNNING, cutoff);
    if (failed > 0 || deleted > 0) {
      log.info(
          "Procedure run retention: {} stale RUNNING row(s) marked FAILED, {} terminal row(s)"
              + " deleted",
          failed,
          deleted);
    }
  }

  private EntityProcedure lookup(String procedureName) {
    var procedure = procedureRegistry.get(procedureName);
    if (procedure == null) {
      throw new ServiceError("Procedure not found: " + procedureName);
    }
    return procedure;
  }

  /** The lifecycle of an asynchronously launched procedure run. */
  public enum ProcedureState {
    RUNNING,
    SUCCEEDED,
    FAILED
  }

  /**
   * A point-in-time view of an asynchronously launched run.
   *
   * @param scheduleId the schedule ID
   * @param state the current state
   * @param error the failure message when {@code state} is {@link ProcedureState#FAILED}
   */
  public record ProcedureStatus(String scheduleId, ProcedureState state, String error) {}

  private Optional<TaskManager.ScheduleHandle> executeInTransaction(
      String entityId, EntityProcedure procedure) {
    return transactionTemplate.execute(
        status -> {
          var entity = entityService.read(entityId);
          return procedure.accept(entity);
        });
  }

  private void joinAndCheck(TaskManager.ScheduleHandle handle) {
    try {
      var res = handle.future().get();
      if (res.isFailure()) {
        var cause = res.getCause();
        throw new ServiceError(
            "Procedure execution failed: " + (cause != null ? cause.getMessage() : "unknown"));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceError("Procedure execution interrupted: " + e.getMessage());
    } catch (ExecutionException e) {
      throw new ServiceError("Procedure execution failed: " + e.getMessage());
    }
  }
}
