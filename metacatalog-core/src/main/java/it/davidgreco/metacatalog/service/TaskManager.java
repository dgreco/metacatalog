package it.davidgreco.metacatalog.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vavr.control.Try;
import java.util.*;
import java.util.concurrent.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Manages asynchronous task execution and scheduling.
 *
 * <p>This service provides functionality for registering task factories, creating tasks, and
 * scheduling groups of tasks with dependency management. Tasks can be organized into schedules that
 * respect dependency ordering and detect cycles.
 */
@Slf4j
@Getter
@RequiredArgsConstructor
@Service
public class TaskManager {

  private final ConcurrentMap<String, TypedTaskFactory<?>> taskFactories =
      new ConcurrentHashMap<>();

  private final Cache<String, Future<Try<Void>>> runningScheduleFutures =
      Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(100).build();

  private final Cache<String, Schedule> runningSchedules =
      Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(100).build();

  private final AsyncTaskExecutor asyncTaskExecutor;

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
   * @param entityTypeName the name of the factory to unregister
   */
  public void unregisterTaskFactory(String entityTypeName) {
    taskFactories.remove(entityTypeName);
  }

  /**
   * Creates a new task for the given entity using the specified factory.
   *
   * @param <T> the type of the entity
   * @param entity the entity to create a task for
   * @param factoryName the name of the registered factory to use
   * @return the created task
   * @throws ServiceError if no factory is registered with the given name or the entity type doesn't
   *     match
   */
  @SuppressWarnings("unchecked")
  public <T> Task<T> createTask(T entity, String factoryName) throws ServiceError {
    TypedTaskFactory<?> typedFactory = taskFactories.get(factoryName);
    if (typedFactory == null || !typedFactory.type.isInstance(entity)) {
      throw new ServiceError("No factory for name: " + factoryName);
    }
    return ((TypedTaskFactory<T>) typedFactory).factory.createTask(entity);
  }

  /**
   * Creates a new empty schedule for organizing tasks.
   *
   * @return a new Schedule instance with a unique ID
   */
  public Schedule createSchedule() {
    return new Schedule(UUID.randomUUID().toString());
  }

  /**
   * Submits a schedule for execution and returns its ID.
   *
   * @param schedule the schedule to execute
   * @return the ID of the submitted schedule
   * @throws ServiceError if the schedule contains cycles
   */
  public String schedule(Schedule schedule) throws ServiceError {
    runningScheduleFutures.put(schedule.getId(), schedule.schedule());
    runningSchedules.put(schedule.getId(), schedule);
    return schedule.id;
  }

  /**
   * Waits for a schedule to complete execution.
   *
   * @param id the ID of the schedule to wait for
   */
  public void joinSchedule(String id) {
    var fut = runningScheduleFutures.getIfPresent(id);
    if (fut != null) {
      try {
        fut.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ServiceRuntimeError(e);
      } catch (ExecutionException e) {
        throw new ServiceRuntimeError(e);
      }
    }
  }

  /**
   * Gets the results of all tasks in a completed schedule.
   *
   * @param id the ID of the schedule
   * @return a list of task results (success or failure)
   */
  public List<Try<Void>> getScheduleResults(String id) {
    var schedule = runningSchedules.getIfPresent(id);
    if (schedule == null) {
      return List.of();
    }
    return schedule.getTasks().stream().map(Task::getResult).flatMap(Optional::stream).toList();
  }

  /**
   * Gets the Future for a running schedule.
   *
   * @param id the ID of the schedule
   * @return an Optional containing the Future if the schedule exists, empty otherwise
   */
  public Optional<Future<Try<Void>>> getRunningScheduleFuture(String id) {
    return Optional.ofNullable(runningScheduleFutures.getIfPresent(id));
  }

  /**
   * Gets a running schedule by its ID.
   *
   * @param id the ID of the schedule
   * @return an Optional containing the schedule if it exists, empty otherwise
   */
  public Optional<Schedule> getRunningSchedule(String id) {
    return Optional.ofNullable(runningSchedules.getIfPresent(id));
  }

  /**
   * Represents a collection of tasks to be executed together.
   *
   * <p>Tasks can be added to a schedule and then submitted for parallel execution. The schedule
   * validates that there are no cycles in the task dependency graph before execution.
   */
  @Getter
  @RequiredArgsConstructor
  public class Schedule {

    private final String id;
    private final List<Task<?>> tasks = new ArrayList<>();

    public <T> void addTask(Task<T> task) {
      tasks.add(task);
    }

    public <T> void addTasks(Collection<Task<T>> tsks) {
      tasks.addAll(tsks);
    }

    public Future<Try<Void>> schedule() throws ServiceError {
      Graph<Task<?>, DefaultEdge> taskGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
      tasks.forEach(
          t -> {
            if (!taskGraph.containsVertex(t)) taskGraph.addVertex(t);
            t.getDependsOnTasks()
                .forEach(
                    e -> {
                      if (!taskGraph.containsVertex(e)) taskGraph.addVertex(e);
                      taskGraph.addEdge(t, e);
                    });
          });
      if (new CycleDetector<>(taskGraph).detectCycles())
        throw new ServiceError("Cycle detected in task graph");

      Callable<Try<Void>> callable =
          () -> {
            tasks.forEach(
                task -> task.getRunningTaskFuture().set(task.schedule(asyncTaskExecutor)));
            tasks.parallelStream().forEach(Task::join);
            // Report the first task failure so callers observing the schedule future can detect
            // that the schedule did not fully succeed (previously this always returned success,
            // masking every individual task failure).
            return tasks.stream()
                .map(Task::getResult)
                .flatMap(Optional::stream)
                .filter(Try::isFailure)
                .findFirst()
                .orElseGet(() -> Try.success(null));
          };
      return asyncTaskExecutor.submit(callable);
    }
  }

  private record TypedTaskFactory<T>(Class<T> type, TaskFactory<T> factory) {}
}
