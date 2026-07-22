package it.davidgreco.metacatalog.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vavr.control.Try;
import it.davidgreco.metacatalog.CoreConfigProperties;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Manages asynchronous task execution and scheduling.
 *
 * <p>This service is a facade over {@link TaskFactoryRegistry} (factory registration and task
 * creation) and a Caffeine-backed schedule cache. {@link Schedule} is a first-class class that
 * handles JGraphT cycle detection and CompletableFuture-based execution.
 *
 * <p>The schedule-result cache size and TTL are configurable via {@link CoreConfigProperties}
 * ({@code taskScheduleCacheMaxSize}, {@code taskScheduleCacheExpireAfterWrite}). A single cache
 * holds {@link RunningSchedule} entries that pair each {@link Schedule} with its {@link Future}, so
 * the two are always evicted together. When an entry is evicted, {@link #joinSchedule} and {@link
 * #getScheduleResults} return empty results — callers should not assume a schedule is cached
 * indefinitely.
 */
@Slf4j
public class TaskManager {

  private final TaskFactoryRegistry taskFactoryRegistry;
  private final Cache<String, RunningSchedule> runningSchedules;
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
    this.runningSchedules =
        Caffeine.newBuilder()
            .maximumSize(coreConfigProperties.taskScheduleCacheMaxSize())
            .expireAfterWrite(coreConfigProperties.taskScheduleCacheExpireAfterWrite())
            .build();
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

  public String schedule(Schedule schedule) {
    runningSchedules.put(
        schedule.getId(), new RunningSchedule(schedule, schedule.schedule(asyncTaskExecutor)));
    return schedule.getId();
  }

  public void joinSchedule(String id) {
    var rs = runningSchedules.getIfPresent(id);
    if (rs != null) {
      try {
        rs.future().get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ServiceError("Schedule execution interrupted: " + e.getMessage());
      } catch (java.util.concurrent.ExecutionException e) {
        throw new ServiceError("Schedule execution failed: " + e.getMessage());
      }
    }
  }

  public List<Try<Void>> getScheduleResults(String id) {
    var rs = runningSchedules.getIfPresent(id);
    if (rs == null) {
      return List.of();
    }
    return rs.schedule().getTasks().stream()
        .map(Task::getResult)
        .flatMap(Optional::stream)
        .toList();
  }

  public Optional<Future<Try<Void>>> getRunningScheduleFuture(String id) {
    return Optional.ofNullable(runningSchedules.getIfPresent(id)).map(RunningSchedule::future);
  }

  private record RunningSchedule(Schedule schedule, Future<Try<Void>> future) {}
}
