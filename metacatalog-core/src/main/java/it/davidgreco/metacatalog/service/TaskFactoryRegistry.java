package it.davidgreco.metacatalog.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Registry of {@link TaskFactory} instances, keyed by name.
 *
 * <p>Factories are registered at startup (or dynamically) and looked up by name when creating
 * tasks. Each registration carries the entity type the factory handles, so {@link #createTask} can
 * verify the entity is of the expected type.
 */
@Slf4j
public class TaskFactoryRegistry {

  private final ConcurrentMap<String, TypedTaskFactory<?>> taskFactories =
      new ConcurrentHashMap<>();

  /**
   * Registers a task factory for creating tasks of a specific type.
   *
   * @param <T> the type of entity the factory creates tasks for
   * @param name the name to register the factory under
   * @param type the class type of entities this factory handles
   * @param factory the task factory implementation
   */
  public <T> void registerTaskFactory(String name, Class<T> type, TaskFactory<T> factory) {
    taskFactories.put(name, new TypedTaskFactory<>(type, factory));
  }

  /**
   * Unregisters a previously registered task factory.
   *
   * @param name the name of the factory to unregister
   */
  public void unregisterTaskFactory(String name) {
    taskFactories.remove(name);
  }

  /**
   * Creates a new task for the given entity using the specified factory.
   *
   * @param <T> the type of the entity
   * @param entity the entity to create a task for
   * @param factoryName the name of the registered factory to use
   * @return the created task
   * @throws ServiceError if no factory is registered for the given name or the entity type doesn't
   *     match
   */
  @SuppressWarnings("unchecked")
  public <T> Task<T> createTask(T entity, String factoryName) {
    TypedTaskFactory<?> typedFactory = taskFactories.get(factoryName);
    if (typedFactory == null || !typedFactory.type.isInstance(entity)) {
      throw new ServiceError("No factory for name: " + factoryName);
    }
    return ((TypedTaskFactory<T>) typedFactory).factory.createTask(entity);
  }

  private record TypedTaskFactory<T>(Class<T> type, TaskFactory<T> factory) {}
}
