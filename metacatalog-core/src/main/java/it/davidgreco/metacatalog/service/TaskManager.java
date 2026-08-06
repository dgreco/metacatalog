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

  public TaskManager(
      AsyncTaskExecutor asyncTaskExecutor, CoreConfigProperties coreConfigProperties) {
    this(new TaskFactoryRegistry(), asyncTaskExecutor, coreConfigProperties);
  }

  public TaskManager(
      TaskFactoryRegistry taskFactoryRegistry,
      AsyncTaskExecutor asyncTaskExecutor,
      CoreConfigProperties coreConfigProperties) {
    this.taskFactoryRegistry = taskFactoryRegistry;
    this.asyncTaskExecutor = asyncTaskExecutor;
  }

  public <T> void registerTaskFactory(String name, Class<T> type, TaskFactory<T> factory) {
    taskFactoryRegistry.registerTaskFactory(name, type, factory);
  }

  public void unregisterTaskFactory(String entityTypeName) {
    taskFactoryRegistry.unregisterTaskFactory(entityTypeName);
  }

  public <T> Task<T> createTask(T entity, String factoryName) {
    return taskFactoryRegistry.createTask(entity, factoryName);
  }

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
