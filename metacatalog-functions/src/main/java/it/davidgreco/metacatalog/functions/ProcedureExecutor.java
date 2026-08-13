package it.davidgreco.metacatalog.functions;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import it.davidgreco.metacatalog.CoreConfigProperties;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TaskManager;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
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
   * Status of asynchronously launched procedure runs, by schedule ID. Bounded and time-limited
   * (same knobs as the former TaskManager schedule cache: {@code
   * application.config.taskScheduleCacheMaxSize} / {@code taskScheduleCacheExpireAfterWrite}), so
   * an abandoned run's entry eventually expires — a poll after expiry gets 404, not a leak.
   */
  private final Cache<String, ProcedureStatus> statusCache;

  private TransactionTemplate transactionTemplate;

  /**
   * Builds the procedure registry from all {@link EntityProcedure} beans in the context.
   *
   * @param procedures every procedure bean, injected by Spring
   * @param entityService entity service for reading entities by ID
   * @param transactionManager the transaction manager used to scope plan-building
   * @param coreConfigProperties bounds and TTL for the async-run status cache
   */
  public ProcedureExecutor(
      List<EntityProcedure> procedures,
      EntityService entityService,
      PlatformTransactionManager transactionManager,
      CoreConfigProperties coreConfigProperties) {
    this.procedureRegistry =
        procedures.stream()
            .collect(Collectors.toUnmodifiableMap(EntityProcedure::name, Function.identity()));
    this.entityService = entityService;
    this.transactionManager = transactionManager;
    this.statusCache =
        Caffeine.newBuilder()
            .maximumSize(coreConfigProperties.taskScheduleCacheMaxSize())
            .expireAfterWrite(coreConfigProperties.taskScheduleCacheExpireAfterWrite())
            .build();
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
      statusCache.put(id, new ProcedureStatus(id, ProcedureState.SUCCEEDED, null));
      return id;
    }
    var handle = scheduleHandle.get();
    statusCache.put(handle.id(), new ProcedureStatus(handle.id(), ProcedureState.RUNNING, null));
    handle
        .future()
        .whenComplete(
            (result, throwable) -> {
              if (throwable != null) {
                statusCache.put(
                    handle.id(),
                    new ProcedureStatus(
                        handle.id(), ProcedureState.FAILED, throwable.getMessage()));
              } else if (result.isFailure()) {
                var cause = result.getCause();
                statusCache.put(
                    handle.id(),
                    new ProcedureStatus(
                        handle.id(),
                        ProcedureState.FAILED,
                        cause != null ? cause.getMessage() : "unknown"));
              } else {
                statusCache.put(
                    handle.id(), new ProcedureStatus(handle.id(), ProcedureState.SUCCEEDED, null));
              }
            });
    return handle.id();
  }

  /**
   * Looks up the status of an asynchronously launched run.
   *
   * @param scheduleId the ID returned by {@link #executeProcedureAsync(String, String)}
   * @return the status, or empty if the ID is unknown or the entry has expired
   */
  public Optional<ProcedureStatus> procedureStatus(String scheduleId) {
    return Optional.ofNullable(statusCache.getIfPresent(scheduleId));
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
