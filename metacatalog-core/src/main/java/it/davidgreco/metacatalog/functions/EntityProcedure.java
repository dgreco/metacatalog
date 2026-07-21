package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.entity.Entity;
import java.util.Optional;

/**
 * Functional interface for procedures that operate on entities.
 *
 * <p>Entity procedures are used to execute custom operations on entities, such as provisioning,
 * validation, or transformation tasks. Unlike {@link EntityFunction}, procedures do not return a
 * result entity; they return an optional schedule ID that the {@link ProcedureExecutor} may join
 * after the plan-building transaction has committed, so async task execution does not hold the
 * plan-building transaction's DB connection.
 *
 * @see AbstractEntityProcedure
 * @see ProcedureExecutor
 */
public interface EntityProcedure {

  /**
   * Runs the procedure on the given entity.
   *
   * @param entity the entity to process
   * @return the ID of a schedule that the caller should join after the transaction commits, or
   *     empty if the procedure did not schedule any async work
   */
  Optional<String> accept(Entity entity);

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
