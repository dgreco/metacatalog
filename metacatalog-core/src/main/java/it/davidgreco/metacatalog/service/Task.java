package it.davidgreco.metacatalog.service;

import io.vavr.control.Try;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.retry.support.RetryTemplate;

@Slf4j
@Getter
public abstract class Task<T> {

  private final AtomicReference<Future<Try<Void>>> runningTaskFuture = new AtomicReference<>();

  private final List<Task<T>> dependsOnTask = new ArrayList<>();

  private final T entity;

  protected Task(T entity) {
    this.entity = entity;
  }

  Future<Try<Void>> schedule(AsyncTaskExecutor asyncTaskExecutor) {
    Callable<Try<Void>> callable =
        () -> {
          dependsOnTask.forEach(
              task -> task.runningTaskFuture.set(task.schedule(asyncTaskExecutor)));
          dependsOnTask.forEach(Task::join);
          return Try.of(Task.this::apply);
        };
    if (runningTaskFuture.get() == null) {
      dependsOnTask.forEach(task -> task.runningTaskFuture.set(task.schedule(asyncTaskExecutor)));
      dependsOnTask.forEach(Task::join);
      return asyncTaskExecutor.submit(callable);
    } else {
      return runningTaskFuture.get();
    }
  }

  public void dependsOn(Task<T> task) {
    dependsOnTask.add(task);
  }

  public void dependsOn(Collection<Task<T>> tasks) {
    dependsOnTask.addAll(tasks);
  }

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
