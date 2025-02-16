package it.davidgreco.metacatalog.functions.provisioning;

import io.vavr.CheckedFunction0;
import io.vavr.control.Try;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Getter
@RequiredArgsConstructor
@Component
public abstract class ProvisioningTask implements CheckedFunction0<Void> {

  private final AtomicReference<Future<Try<Void>>> runningTaskFuture = new AtomicReference<>();

  private final List<ProvisioningTask> dependsOnTask = new ArrayList<>();

  private final transient Entity entity;

  public Future<Try<Void>> schedule(AsyncTaskExecutor asyncTaskExecutor) {
    Callable<Try<Void>> callable =
        () -> {
          dependsOnTask.forEach(
              task -> task.runningTaskFuture.set(task.schedule(asyncTaskExecutor)));
          dependsOnTask.forEach(ProvisioningTask::join);
          return Try.of(ProvisioningTask.this::apply);
        };
    if (runningTaskFuture.get() == null) {
      dependsOnTask.forEach(task -> task.runningTaskFuture.set(task.schedule(asyncTaskExecutor)));
      dependsOnTask.forEach(ProvisioningTask::join);
      return asyncTaskExecutor.submit(callable);
    } else {
      return runningTaskFuture.get();
    }
  }

  public void dependsOn(ProvisioningTask task) {
    dependsOnTask.add(task);
  }

  public void dependsOn(List<ProvisioningTask> tasks) {
    dependsOnTask.addAll(tasks);
  }

  @Override
  public abstract Void apply();

  public void join() {
    RetryTemplate createRetryTemplate =
        RetryTemplate.builder()
            .maxAttempts(10)
            .fixedBackoff(500)
            .retryOn(ServiceRuntimeError.class)
            .build();

    try {
      createRetryTemplate.execute(
          _ -> {
            if (runningTaskFuture.get() == null)
              throw new ServiceRuntimeError("Task not yet started");
            else return runningTaskFuture.get().get();
          });
    } catch (Exception e) {
      throw new ServiceRuntimeError(e);
    }
  }
}
