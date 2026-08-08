package it.davidgreco.metacatalog.service;

import io.vavr.control.Try;
import it.davidgreco.metacatalog.CoreConfigProperties;
import it.davidgreco.metacatalog.entity.Entity;
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
  private final EntityTypeService entityTypeService;

  /**
   * Creates a task manager with a fresh, empty {@link TaskFactoryRegistry}.
   *
   * @param asyncTaskExecutor the executor tasks are submitted to
   * @param coreConfigProperties the core configuration properties
   * @param entityTypeService used to verify that a factory is registered against a type that exists
   */
  public TaskManager(
      AsyncTaskExecutor asyncTaskExecutor,
      CoreConfigProperties coreConfigProperties,
      EntityTypeService entityTypeService) {
    this(new TaskFactoryRegistry(), asyncTaskExecutor, coreConfigProperties, entityTypeService);
  }

  /**
   * Creates a task manager backed by the given registry. Useful when the registry is shared or
   * pre-populated (e.g. in tests).
   *
   * @param taskFactoryRegistry the registry used to look up task factories
   * @param asyncTaskExecutor the executor tasks are submitted to
   * @param coreConfigProperties the core configuration properties
   * @param entityTypeService used to verify that a factory is registered against a type that exists
   */
  public TaskManager(
      TaskFactoryRegistry taskFactoryRegistry,
      AsyncTaskExecutor asyncTaskExecutor,
      CoreConfigProperties coreConfigProperties,
      EntityTypeService entityTypeService) {
    this.taskFactoryRegistry = taskFactoryRegistry;
    this.asyncTaskExecutor = asyncTaskExecutor;
    this.entityTypeService = entityTypeService;
  }

  /**
   * Registers a task factory for entities of a specific entity type. Tasks are created from the
   * factory registered against the entity's type name, so registering under any other name makes
   * the factory unreachable.
   *
   * <p>The name must be one an entity type actually has. Registering under a name nothing matches
   * is always a mistake — a typo, or a type that was renamed — and one that used to surface only
   * much later, as {@code No factory for name: ...} at the point a run tried to provision the
   * resource, with nothing tying it back to the registration. Worse, the reverse case was silent: a
   * factory registered under a misspelled name simply never fired.
   *
   * <p>The check means a registrar working from configuration cannot register before the type
   * exists. That is what {@link it.davidgreco.metacatalog.functions.DeferredTaskFactoryRegistrar}
   * is for: entity types are catalog data created at runtime, so such a registrar re-attempts
   * registration before each procedure run rather than once at startup.
   *
   * @param entityTypeName the name of the entity type the factory handles
   * @param factory the task factory implementation
   * @throws NotFoundException if no entity type has that name
   */
  public void registerTaskFactory(String entityTypeName, TaskFactory factory) {
    if (!entityTypeService.exists(entityTypeName)) {
      throw new NotFoundException(
          "Cannot register a task factory for EntityType "
              + entityTypeName
              + ": no entity type with that name exists");
    }
    taskFactoryRegistry.registerTaskFactory(entityTypeName, factory);
  }

  /**
   * Reports whether a factory is already registered for the given entity type name.
   *
   * @param entityTypeName the name of the entity type to check
   * @return true if a factory is registered under that name
   */
  public boolean isRegistered(String entityTypeName) {
    return taskFactoryRegistry.isRegistered(entityTypeName);
  }

  /**
   * Unregisters the task factory registered for the given entity type, if any.
   *
   * @param entityTypeName the name of the entity type whose factory to unregister
   */
  public void unregisterTaskFactory(String entityTypeName) {
    taskFactoryRegistry.unregisterTaskFactory(entityTypeName);
  }

  /**
   * Creates a task for the given entity using the factory registered under the entity's type name.
   *
   * @param entity the entity to create a task for
   * @return the created task
   * @throws ServiceError if no factory is registered for the entity's type
   */
  public Task createTask(Entity entity) {
    return taskFactoryRegistry.createTask(entity);
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
