package it.davidgreco.metacatalog.service;

/**
 * Factory interface for creating tasks that operate on entities.
 *
 * <p>Implementations of this interface are registered with the {@link TaskManager} to enable
 * creation of tasks for specific entity types.
 *
 * @param <T> the type of entity this factory creates tasks for
 */
public interface TaskFactory<T> {

  /**
   * Creates a new task for the given entity.
   *
   * @param entity the entity to create a task for
   * @return a new task instance
   */
  Task<T> createTask(T entity);
}
