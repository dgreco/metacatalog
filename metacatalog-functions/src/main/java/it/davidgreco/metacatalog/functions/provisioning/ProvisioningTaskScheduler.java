package it.davidgreco.metacatalog.functions.provisioning;

import io.vavr.control.Try;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.ServiceError;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;

@Slf4j
public class ProvisioningTaskScheduler {

  private static final ConcurrentMap<String, ProvisioningTaskFactory> provisioningTaskFactories =
      new ConcurrentHashMap<>();

  private static final ConcurrentMap<String, CompletableFuture<Try<Void>>> runningScheduleFutures =
      new ConcurrentHashMap<>();

  private static final ConcurrentMap<String, Schedule> runningSchedules = new ConcurrentHashMap<>();

  private ProvisioningTaskScheduler() {}

  public static void registerProvisioningTaskFactory(
      String entityTypeName, ProvisioningTaskFactory factory) {
    provisioningTaskFactories.put(entityTypeName, factory);
  }

  public static void unregisterProvisioningTaskFactory(String entityTypeName) {
    provisioningTaskFactories.remove(entityTypeName);
  }

  public static ProvisioningTask createTask(Entity entity) throws ServiceError {
    return Optional.ofNullable(provisioningTaskFactories.get(entity.getEntityType().getName()))
        .orElseThrow(
            () ->
                new ServiceError("No factory for entity type: " + entity.getEntityType().getName()))
        .createProvisionTask(entity);
  }

  public static Schedule createSchedule() {
    return Schedule.of(UUID.randomUUID().toString());
  }

  public static void schedule(Schedule schedule) {
    runningScheduleFutures.put(schedule.getId(), schedule.schedule());
    runningSchedules.put(schedule.getId(), schedule);
  }

  public static void joinSchedule(String id) {
    Optional.ofNullable(runningScheduleFutures.get(id)).ifPresent(CompletableFuture::join);
  }

  public static Optional<CompletableFuture<Try<Void>>> getRunningScheduleFuture(String id) {
    return Optional.ofNullable(runningScheduleFutures.get(id));
  }

  public static Optional<Schedule> getRunningSchedule(String id) {
    return Optional.ofNullable(runningSchedules.get(id));
  }

  public static void clearRunningSchedule(String id) {
    runningScheduleFutures.remove(id);
    runningSchedules.remove(id);
  }

  @Getter
  @RequiredArgsConstructor(staticName = "of")
  public static class Schedule {

    private final String id;

    private final List<ProvisioningTask> tasks = new ArrayList<>();

    public void addTask(ProvisioningTask task) {
      tasks.add(task);
    }

    public void addTasks(Collection<ProvisioningTask> tsks) {
      tasks.addAll(tsks);
    }

    @Async("threadPoolProvisioningExecutor")
    public CompletableFuture<Try<Void>> schedule() {
      return CompletableFuture.completedFuture(
          Try.of(
              () -> {
                tasks.forEach(ProvisioningTask::schedule);
                tasks.parallelStream().forEach(ProvisioningTask::join);
                return null;
              }));
    }
  }
}
