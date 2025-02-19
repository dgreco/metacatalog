package it.davidgreco.metacatalog.service;

import io.vavr.control.Try;
import java.util.*;
import java.util.concurrent.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

@Slf4j
@Getter
@RequiredArgsConstructor
@Service
public class TaskManager {

  private final ConcurrentMap<String, TaskFactory<?>> taskFactories = new ConcurrentHashMap<>();

  private final ConcurrentMap<String, Future<Try<Void>>> runningScheduleFutures =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<String, Schedule> runningSchedules = new ConcurrentHashMap<>();

  private final AsyncTaskExecutor asyncTaskExecutor;

  public <T> void registerTaskFactory(String entityTypeName, TaskFactory<T> factory) {
    taskFactories.put(entityTypeName, factory);
  }

  public void unregisterTaskFactory(String entityTypeName) {
    taskFactories.remove(entityTypeName);
  }

  public <T> Task<T> createTask(T entity, String factoryName) throws ServiceError {
    return Optional.ofNullable((TaskFactory<T>) taskFactories.get(factoryName))
        .orElseThrow(() -> new ServiceError("No factory for name: " + factoryName))
        .createTask(entity);
  }

  public Schedule createSchedule() {
    return new Schedule(UUID.randomUUID().toString());
  }

  public String schedule(Schedule schedule) {
    runningScheduleFutures.put(schedule.getId(), schedule.schedule());
    runningSchedules.put(schedule.getId(), schedule);
    return schedule.id;
  }

  public void joinSchedule(String id) {
    Optional.ofNullable(runningScheduleFutures.get(id))
        .ifPresent(
            fut -> {
              try {
                fut.get();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceRuntimeError(e);
              } catch (ExecutionException e) {
                throw new ServiceRuntimeError(e);
              }
            });
  }

  public List<Try<Void>> getScheduleResults(String id) {
    if (!runningSchedules.containsKey(id)) {
      return List.of();
    }
    return runningSchedules.get(id).getTasks().stream()
        .map(Task::getResult)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toList();
  }

  public Optional<Future<Try<Void>>> getRunningScheduleFuture(String id) {
    return Optional.ofNullable(runningScheduleFutures.get(id));
  }

  public Optional<Schedule> getRunningSchedule(String id) {
    return Optional.ofNullable(runningSchedules.get(id));
  }

  public void clearRunningSchedule(String id) {
    runningScheduleFutures.remove(id);
    runningSchedules.remove(id);
  }

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

    public Future<Try<Void>> schedule() {
      Callable<Try<Void>> callable =
          () -> {
            tasks.forEach(
                task -> task.getRunningTaskFuture().set(task.schedule(asyncTaskExecutor)));
            tasks.parallelStream().forEach(Task::join);
            return Try.of(() -> null);
          };
      return asyncTaskExecutor.submit(callable);
    }
  }
}
