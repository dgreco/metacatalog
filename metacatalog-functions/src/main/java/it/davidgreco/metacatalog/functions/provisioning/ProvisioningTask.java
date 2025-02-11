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
public class ProvisioningTask implements CheckedFunction0<Void> {

  private AtomicReference<CompletableFuture<Try<Void>>> task = new AtomicReference<>();

  private List<ProvisioningTask> dependsOnTask = new ArrayList<>();

  private final transient Entity entity;

  @Async("threadPoolTaskExecutor")
  public void schedule() {
    dependsOnTask.forEach(ProvisioningTask::schedule);
    dependsOnTask.forEach(ProvisioningTask::join);
    if (task.get() == null) {
      task.set(CompletableFuture.completedFuture(Try.of(this::apply)));
    }
  }

  public void dependsOn(ProvisioningTask task) {
    dependsOnTask.add(task);
  }

  @Override
  public Void apply() throws InterruptedException {
    log.error("Provisioning task for entity: " + entity);
    Thread.sleep(1000);
    return null;
  }

  public void join() {
    RetryTemplate createRetryTemplate =
        RetryTemplate.builder()
            .maxAttempts(10)
            .fixedBackoff(500)
            .retryOn(ServiceRuntimeError.class)
            .build();

    createRetryTemplate.execute(
        _ -> {
          if (task.get() == null) throw new ServiceRuntimeError("Task not yet started");
          else return task.get().join();
        });
  }
}
