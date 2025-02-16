package it.davidgreco.metacatalog.functions.provisioning;

import io.vavr.control.Try;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;
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
public class ProvisioningTaskScheduler {

  private final ConcurrentMap<String, ProvisioningTaskFactory> provisioningTaskFactories =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<String, Future<Try<Void>>> runningScheduleFutures =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<String, Schedule> runningSchedules = new ConcurrentHashMap<>();

  private final AsyncTaskExecutor asyncTaskExecutor;

  public void registerProvisioningTaskFactory(
      String entityTypeName, ProvisioningTaskFactory factory) {
    provisioningTaskFactories.put(entityTypeName, factory);
  }

  public void unregisterProvisioningTaskFactory(String entityTypeName) {
    provisioningTaskFactories.remove(entityTypeName);
  }

  public ProvisioningTask createTask(Entity entity) throws ServiceError {
    return Optional.ofNullable(provisioningTaskFactories.get(entity.getEntityType().getName()))
        .orElseThrow(
            () ->
                new ServiceError("No factory for entity type: " + entity.getEntityType().getName()))
        .createProvisionTask(entity);
  }

  public Schedule createSchedule() {
    return new Schedule(UUID.randomUUID().toString());
  }

  public void schedule(Schedule schedule) {
    runningScheduleFutures.put(schedule.getId(), schedule.schedule());
    runningSchedules.put(schedule.getId(), schedule);
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
    private final List<ProvisioningTask> tasks = new ArrayList<>();

    public void addTask(ProvisioningTask task) {
      tasks.add(task);
    }

    public void addTasks(Collection<ProvisioningTask> tsks) {
      tasks.addAll(tsks);
    }

    public Future<Try<Void>> schedule() {
      Callable<Try<Void>> callable =
          () -> {
            tasks.forEach(
                task -> task.getRunningTaskFuture().set(task.schedule(asyncTaskExecutor)));
            tasks.parallelStream().forEach(ProvisioningTask::join);
            return Try.of(() -> null);
          };
      return asyncTaskExecutor.submit(callable);
    }
  }
}
