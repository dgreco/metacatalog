package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.entity.Entity;
import java.util.function.Consumer;

/**
 * Functional interface for procedures that operate on entities.
 *
 * <p>Entity procedures are used to execute custom operations on entities, such as provisioning,
 * validation, or transformation tasks. Unlike {@link EntityFunction}, procedures do not return a
 * result.
 *
 * @see AbstractEntityProcedure
 * @see ProcedureExecutor
 */
public interface EntityProcedure extends Consumer<Entity> {

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
