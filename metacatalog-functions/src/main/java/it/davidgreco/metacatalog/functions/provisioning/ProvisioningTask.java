package it.davidgreco.metacatalog.functions.provisioning;

import io.vavr.CheckedFunction0;
import io.vavr.control.Try;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Async;

@Slf4j
@Getter
@RequiredArgsConstructor
public abstract class ProvisioningTask implements CheckedFunction0<Void> {

  private final AtomicReference<CompletableFuture<Try<Void>>> runningTaskFuture =
      new AtomicReference<>();

  private final List<ProvisioningTask> dependsOnTask = new ArrayList<>();

  private final transient Entity entity;

  @Async("threadPoolTaskExecutor")
  public void schedule() {
    dependsOnTask.forEach(ProvisioningTask::schedule);
    dependsOnTask.forEach(ProvisioningTask::join);
    if (runningTaskFuture.get() == null) {
      runningTaskFuture.set(CompletableFuture.completedFuture(Try.of(this::apply)));
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

    createRetryTemplate.execute(
        _ -> {
          if (runningTaskFuture.get() == null)
            throw new ServiceRuntimeError("Task not yet started");
          else return runningTaskFuture.get().join();
        });
  }
}
