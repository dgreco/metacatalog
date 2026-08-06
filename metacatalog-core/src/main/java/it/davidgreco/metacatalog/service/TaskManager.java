package it.davidgreco.metacatalog.service;

import io.vavr.control.Try;
import it.davidgreco.metacatalog.CoreConfigProperties;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Manages asynchronous task execution and scheduling.
 *
 * <p>This service is a facade over {@link TaskFactoryRegistry} (factory registration and task
 * creation) and the {@link Schedule} class, which handles JGraphT cycle detection and
 * CompletableFuture-based execution.
 *
 * <p>Procedures call {@link #schedule(Schedule)} to submit work. The method returns a {@link
 * ScheduleHandle} carrying both the schedule ID and the future for its result. Callers should await
 * the result via {@code handle.future().get()} rather than looking it up by ID — the future is
 * passed directly, so there is no cache to evict and no race to lose failures.
 */
@Slf4j
public class TaskManager {

  private final TaskFactoryRegistry taskFactoryRegistry;
  private final AsyncTaskExecutor asyncTaskExecutor;

  /**
   * Creates a task manager with a fresh, empty {@link TaskFactoryRegistry}.
   *
   * @param asyncTaskExecutor the executor tasks are submitted to
   * @param coreConfigProperties the core configuration properties
   */
  public TaskManager(
      AsyncTaskExecutor asyncTaskExecutor, CoreConfigProperties coreConfigProperties) {
    this(new TaskFactoryRegistry(), asyncTaskExecutor, coreConfigProperties);
  }

  /**
   * Creates a task manager backed by the given registry. Useful when the registry is shared or
   * pre-populated (e.g. in tests).
   *
   * @param taskFactoryRegistry the registry used to look up task factories
   * @param asyncTaskExecutor the executor tasks are submitted to
   * @param coreConfigProperties the core configuration properties
   */
  public TaskManager(
      TaskFactoryRegistry taskFactoryRegistry,
      AsyncTaskExecutor asyncTaskExecutor,
      CoreConfigProperties coreConfigProperties) {
    this.taskFactoryRegistry = taskFactoryRegistry;
    this.asyncTaskExecutor = asyncTaskExecutor;
  }

  /**
   * Registers a task factory under the given name. Procedures look factories up by entity type
   * name, so {@code name} is conventionally the name of the entity type the factory handles.
   *
   * @param <T> the type of entity the factory creates tasks for
   * @param name the name to register the factory under
   * @param type the class of entities this factory handles, used to type-check lookups
   * @param factory the task factory implementation
   */
  public <T> void registerTaskFactory(String name, Class<T> type, TaskFactory<T> factory) {
    taskFactoryRegistry.registerTaskFactory(name, type, factory);
  }

  /**
   * Unregisters the task factory registered under the given name, if any.
   *
   * @param entityTypeName the name the factory was registered under
   */
  public void unregisterTaskFactory(String entityTypeName) {
    taskFactoryRegistry.unregisterTaskFactory(entityTypeName);
  }

  /**
   * Creates a task for the given entity using the factory registered under {@code factoryName}.
   *
   * @param <T> the type of the entity
   * @param entity the entity to create a task for
   * @param factoryName the name of the registered factory to use
   * @return the created task
   * @throws ServiceError if no factory is registered under the name or the entity is not an
   *     instance of the factory's registered type
   */
  public <T> Task<T> createTask(T entity, String factoryName) {
    return taskFactoryRegistry.createTask(entity, factoryName);
  }

  /**
   * Creates a new, empty schedule to which tasks can be added before submitting it via {@link
   * #schedule(Schedule)}.
   *
   * @return a new schedule with a random ID
   */
  public Schedule createSchedule() {
    return new Schedule();
  }

  /**
   * Submits a schedule for asynchronous execution.
   *
   * <p>Tasks are executed in dependency order via {@code CompletableFuture} chains — no worker
   * thread blocks while awaiting dependencies, so wide or deep graphs cannot starve the pool.
   *
   * @param schedule the schedule to submit
   * @return a handle carrying the schedule ID and the future for its result; await via {@code
   *     handle.future().get()}
   */
  public ScheduleHandle schedule(Schedule schedule) {
    var future = schedule.schedule(asyncTaskExecutor);
    return new ScheduleHandle(schedule.getId(), future);
  }

  /**
   * Handle returned by {@link #schedule(Schedule)} carrying the schedule ID and the future for its
   * result. Callers should await the result via {@code handle.future().get()} rather than looking
   * it up by ID — the future is passed directly, so there is no cache to evict and no race to lose
   * failures.
   */
  public record ScheduleHandle(String id, CompletableFuture<Try<Void>> future) {}
}
