package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.Optional;

/**
 * Abstract base class for entity procedures that provides type checking and error handling.
 *
 * <p>Subclasses must implement {@link #checkInputType(Entity)} to validate that the entity is of
 * the expected type, and {@link #execute(Entity)} to perform the actual procedure logic. The {@link
 * #execute(Entity)} method returns an optional {@link TaskManager.ScheduleHandle} so the {@link
 * ProcedureExecutor} can join any scheduled async work after the plan-building transaction commits.
 *
 * @see EntityProcedure
 * @see ProcedureExecutor
 */
public abstract class AbstractEntityProcedure implements EntityProcedure {

  /**
   * Accepts an entity for processing, performing type checking before execution.
   *
   * @param entity the entity to process
   * @return the {@link TaskManager.ScheduleHandle} to join after the transaction commits, or empty
   *     if no async work was scheduled
   */
  @Override
  public Optional<TaskManager.ScheduleHandle> accept(Entity entity) {
    checkInputType(entity);
    return execute(entity);
  }

  /**
   * Validates that the entity is of the expected type for this procedure.
   *
   * @param entity the entity to validate
   */
  protected abstract void checkInputType(Entity entity);

  /**
   * Executes the procedure logic on the validated entity.
   *
   * @param entity the entity to process
   * @return the {@link TaskManager.ScheduleHandle} to join after the transaction commits, or empty
   *     if no async work was scheduled
   */
  protected abstract Optional<TaskManager.ScheduleHandle> execute(Entity entity);
}
