package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;

/**
 * Abstract base class for entity procedures that provides type checking and error handling.
 *
 * <p>Subclasses must implement {@link #checkInputType(Entity)} to validate that the entity is of
 * the expected type, and {@link #execute(Entity)} to perform the actual procedure logic.
 *
 * @see EntityProcedure
 * @see ProcedureExecutor
 */
public abstract class AbstractEntityProcedure implements EntityProcedure {

  /**
   * Accepts an entity for processing, performing type checking before execution.
   *
   * @param entity the entity to process
   * @throws ServiceRuntimeError if type checking fails or execution throws a ServiceError
   */
  @Override
  public void accept(Entity entity) {
    try {
      checkInputType(entity);
      execute(entity);
    } catch (ServiceError e) {
      throw new ServiceRuntimeError(e);
    }
  }

  /**
   * Validates that the entity is of the expected type for this procedure.
   *
   * @param entity the entity to validate
   * @throws ServiceError if the entity is not of the expected type
   */
  protected abstract void checkInputType(Entity entity) throws ServiceError;

  /**
   * Executes the procedure logic on the validated entity.
   *
   * @param entity the entity to process
   * @throws ServiceError if an error occurs during execution
   */
  protected abstract void execute(Entity entity) throws ServiceError;
}
