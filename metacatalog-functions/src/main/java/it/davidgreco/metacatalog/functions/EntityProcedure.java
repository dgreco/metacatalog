package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.Optional;

/**
 * Functional interface for procedures that operate on entities.
 *
 * <p>Entity procedures are used to execute custom operations on entities, such as provisioning,
 * validation, or transformation tasks. Procedures do not return a result entity; they return an
 * optional {@link TaskManager.ScheduleHandle} that the {@link ProcedureExecutor} may use to join
 * the async schedule after the plan-building transaction has committed, so async task execution
 * does not hold the plan-building transaction's DB connection.
 *
 * @see AbstractEntityProcedure
 * @see ProcedureExecutor
 */
public interface EntityProcedure {

  /**
   * Runs the procedure on the given entity.
   *
   * @param entity the entity to process
   * @return a {@link TaskManager.ScheduleHandle} that the caller should use to await the schedule
   *     result after the transaction commits, or empty if the procedure did not schedule any async
   *     work
   */
  Optional<TaskManager.ScheduleHandle> accept(Entity entity);

  /**
   * The unique name this procedure is registered and invoked under. Defaults to the
   * implementation's simple class name.
   *
   * @return the procedure name
   */
  default String name() {
    return getClass().getSimpleName();
  }
}
