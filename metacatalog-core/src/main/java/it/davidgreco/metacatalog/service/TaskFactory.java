package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.Entity;

/**
 * Factory interface for creating tasks that operate on entities.
 *
 * <p>Implementations of this interface are registered with the {@link TaskManager} under the name
 * of the entity type they handle: the task created for an entity is always the one registered
 * against that entity's {@link it.davidgreco.metacatalog.entity.EntityType}.
 */
public interface TaskFactory {

  /**
   * Creates a new task for the given entity.
   *
   * @param entity the entity to create a task for
   * @return a new task instance
   */
  Task createTask(Entity entity);
}
