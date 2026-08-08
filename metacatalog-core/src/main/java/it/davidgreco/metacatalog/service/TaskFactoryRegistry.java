package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.Entity;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Registry of {@link TaskFactory} instances, keyed by entity type name.
 *
 * <p>Factories are registered at startup (or dynamically) under the name of the entity type they
 * handle. {@link #createTask} resolves the factory from the entity's own {@link
 * it.davidgreco.metacatalog.entity.EntityType}, so an entity can never be handed to the factory of
 * a different type.
 */
@Slf4j
public class TaskFactoryRegistry {

  private final ConcurrentMap<String, TaskFactory> taskFactories = new ConcurrentHashMap<>();

  /**
   * Registers a task factory for entities of a specific entity type.
   *
   * @param entityTypeName the name of the entity type the factory handles
   * @param factory the task factory implementation
   */
  public void registerTaskFactory(String entityTypeName, TaskFactory factory) {
    taskFactories.put(entityTypeName, factory);
  }

  /**
   * Unregisters a previously registered task factory.
   *
   * @param entityTypeName the name of the entity type whose factory to unregister
   */
  public void unregisterTaskFactory(String entityTypeName) {
    taskFactories.remove(entityTypeName);
  }

  /**
   * Reports whether a factory is already registered for the given entity type name. Lets a
   * registrar that runs repeatedly re-attempt only what it has not managed to register yet.
   *
   * @param entityTypeName the name of the entity type to check
   * @return true if a factory is registered under that name
   */
  public boolean isRegistered(String entityTypeName) {
    return taskFactories.containsKey(entityTypeName);
  }

  /**
   * Creates a new task for the given entity, using the factory registered under the entity's type
   * name.
   *
   * @param entity the entity to create a task for
   * @return the created task
   * @throws ServiceError if no factory is registered for the entity's type
   */
  public Task createTask(Entity entity) {
    var entityTypeName = entity.getEntityType().getName();
    TaskFactory factory = taskFactories.get(entityTypeName);
    if (factory == null) {
      throw new ServiceError("No factory for name: " + entityTypeName);
    }
    return factory.createTask(entity);
  }
}
