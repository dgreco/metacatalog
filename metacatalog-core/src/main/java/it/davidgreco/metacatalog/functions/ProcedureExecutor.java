package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TaskManager;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  private final TaskManager taskManager;

  private TransactionTemplate transactionTemplate;

  /**
   * Builds the procedure registry from all {@link EntityProcedure} beans in the context.
   *
   * @param procedures every procedure bean, injected by Spring
   * @param entityService entity service for reading entities by ID
   * @param transactionManager the transaction manager used to scope plan-building
   * @param taskManager the task manager used to join schedules produced by procedures
   */
  public ProcedureExecutor(
      List<EntityProcedure> procedures,
      EntityService entityService,
      PlatformTransactionManager transactionManager,
      TaskManager taskManager) {
    this.procedureRegistry =
        procedures.stream()
            .collect(Collectors.toUnmodifiableMap(EntityProcedure::name, Function.identity()));
    this.entityService = entityService;
    this.transactionManager = transactionManager;
    this.taskManager = taskManager;
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
    var procedure = procedureRegistry.get(procedureName);
    if (procedure == null) {
      throw new ServiceError("Procedure not found: " + procedureName);
    }
    var scheduleHandle = executeInTransaction(entityId, procedure);
    scheduleHandle.ifPresent(this::joinAndCheck);
  }

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
