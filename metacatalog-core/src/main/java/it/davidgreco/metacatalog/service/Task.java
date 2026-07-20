package it.davidgreco.metacatalog.service;

import io.vavr.control.Try;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.retry.support.RetryTemplate;

/**
 * Abstract base class for asynchronous tasks that operate on entities.
 *
 * <p>Tasks can have dependencies on other tasks and are executed asynchronously. The task framework
 * ensures that dependent tasks complete before a task begins execution.
 *
 * @param <T> the type of entity this task operates on
 */
@Slf4j
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public abstract class Task<T> {

  private final AtomicReference<Future<Try<Void>>> runningTaskFuture = new AtomicReference<>();
  private final AtomicReference<Try<Void>> result = new AtomicReference<>();

  private final List<Task<T>> dependsOnTasks = new ArrayList<>();

  private final T entity;

  protected Task(T entity) {
    this.entity = entity;
  }

  Future<Try<Void>> schedule(AsyncTaskExecutor asyncTaskExecutor) {
    var existing = runningTaskFuture.get();
    if (existing != null) {
      return existing;
    }
    // Claim scheduling of this task under a lock so that a task shared by several parents (a
    // diamond dependency graph) is submitted — and therefore executed — exactly once. The previous
    // check-then-act on the AtomicReference allowed two threads to both observe a null future and
    // both submit the same task.
    synchronized (this) {
      existing = runningTaskFuture.get();
      if (existing != null) {
        return existing;
      }
      dependsOnTasks.forEach(task -> task.schedule(asyncTaskExecutor));
      dependsOnTasks.forEach(Task::join);
      boolean dependsOnTasksFailed =
          dependsOnTasks.stream()
              .map(Task::getResult)
              .flatMap(Optional::stream)
              .anyMatch(Try::isFailure);
      Future<Try<Void>> future =
          dependsOnTasksFailed
              ? CompletableFuture.completedFuture(
                  Try.failure(new ServiceRuntimeError("One or more of the depending tasks failed")))
              : asyncTaskExecutor.submit(() -> Try.of(Task.this::apply));
      runningTaskFuture.set(future);
      return future;
    }
  }

  /**
   * Adds a dependency on another task.
   *
   * @param task the task that must complete before this task runs
   */
  public void dependsOn(Task<T> task) {
    dependsOnTasks.add(task);
  }

  /**
   * Adds dependencies on multiple tasks.
   *
   * @param tasks the tasks that must complete before this task runs
   */
  public void dependsOn(Collection<Task<T>> tasks) {
    dependsOnTasks.addAll(tasks);
  }

  /**
   * Executes the task logic. Implementations should perform the actual work of the task.
   *
   * @return always returns null (Void)
   */
  public abstract Void apply();

  /**
   * Gets the unique identifier for this task. Used for equality comparison and cycle detection.
   *
   * @return the task's unique identifier
   */
  @EqualsAndHashCode.Include
  public abstract String getId();

  /**
   * Waits for this task and all its dependencies to complete. Uses retry logic to handle the case
   * where the task has not yet been started.
   */
  public void join() {
    RetryTemplate createRetryTemplate =
        RetryTemplate.builder()
            .maxAttempts(10)
            .fixedBackoff(500)
            .retryOn(ServiceRuntimeError.class)
            .build();

    try {
      createRetryTemplate.execute(
          rc -> {
            if (runningTaskFuture.get() == null)
              throw new ServiceRuntimeError("Task not yet started");
            else {
              try {
                result.set(runningTaskFuture.get().get());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceRuntimeError(e);
              } catch (ExecutionException e) {
                throw new ServiceRuntimeError(e);
              }
            }
            return new Object();
          });
    } catch (Exception e) {
      throw new ServiceRuntimeError(e);
    }
  }

  /**
   * Gets the result of this task after completion.
   *
   * @return an Optional containing the result (success or failure), or empty if task hasn't
   *     completed
   */
  public Optional<Try<Void>> getResult() {
    return Optional.ofNullable(result.get());
  }
}
