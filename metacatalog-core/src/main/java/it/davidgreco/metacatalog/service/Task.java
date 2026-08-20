package it.davidgreco.metacatalog.service;

import io.vavr.control.Try;
import it.davidgreco.metacatalog.entity.Entity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for asynchronous tasks that operate on entities.
 *
 * <p>A task is always performed on an {@link Entity} belonging to a specific {@link
 * it.davidgreco.metacatalog.entity.EntityType}: tasks are created through the {@link TaskFactory}
 * registered against the entity's type name, so the entity's type determines which task
 * implementation runs.
 *
 * <p>Tasks can have dependencies on other tasks and are executed asynchronously. The task framework
 * ensures that dependent tasks complete before a task begins execution.
 */
@Slf4j
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public abstract class Task {

  private final AtomicReference<CompletableFuture<Try<Void>>> runningTaskFuture =
      new AtomicReference<>();
  private final AtomicReference<Try<Void>> result = new AtomicReference<>();

  // CopyOnWriteArrayList: dependencies are typically declared during graph construction and then
  // read during schedule(); COW gives safe publication without extra synchronisation on the read
  // path. dependsOn() may be called concurrently with schedule() in principle, and the previous
  // plain ArrayList was not safe for that.
  private final List<Task> dependsOnTasks = new CopyOnWriteArrayList<>();

  private final Entity entity;

  /**
   * Creates a task operating on the given entity.
   *
   * @param entity the entity this task operates on
   */
  protected Task(Entity entity) {
    this.entity = entity;
  }

  /**
   * Schedules this task for asynchronous execution once all of its dependencies have completed, and
   * returns a future for its {@link Try} result.
   *
   * <p>Dependencies are composed with {@link CompletableFuture#allOf} + {@code thenApplyAsync}
   * rather than by blocking a worker thread on their completion. This is what prevents the classic
   * thread-pool starvation deadlock: a worker thread is occupied only while a task's {@link
   * #apply()} actually runs, never while it waits for its dependencies, so a graph deeper or wider
   * than the pool can no longer wedge every thread in a blocking {@code join}.
   *
   * <p>Scheduling is idempotent: the future is claimed under {@code this} lock and cached, so a
   * task shared by several parents (a diamond dependency graph) is submitted — and therefore
   * executed — exactly once. The lock is held only for the (non-blocking) composition, never while
   * awaiting a dependency.
   *
   * <p>The returned future always completes with a {@link Try} — never exceptionally. A {@code
   * handle} stage ensures {@link #getResult()} is populated even when the {@code thenApplyAsync}
   * stage is never run (e.g. the executor rejects the task, or a dependency future completes
   * exceptionally and {@code allOf} short-circuits). Previously, on such paths {@code result} was
   * never set and {@link #getResult()} silently returned {@code Optional.empty()}, which caused
   * {@code TaskManager.getScheduleResults} to skip the failed task and the schedule to report
   * success.
   *
   * @param executor the executor used to run this task's work
   * @return the future of this task's result
   */
  CompletableFuture<Try<Void>> schedule(Executor executor) {
    var existing = runningTaskFuture.get();
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      existing = runningTaskFuture.get();
      if (existing != null) {
        return existing;
      }
      List<CompletableFuture<Try<Void>>> dependencyFutures =
          dependsOnTasks.stream().map(task -> task.schedule(executor)).toList();
      CompletableFuture<Try<Void>> future =
          CompletableFuture.allOf(dependencyFutures.toArray(new CompletableFuture[0]))
              .thenApplyAsync(
                  ignored -> {
                    boolean dependsOnTasksFailed =
                        dependencyFutures.stream()
                            .map(CompletableFuture::join)
                            .anyMatch(Try::isFailure);
                    return dependsOnTasksFailed
                        ? Try.<Void>failure(new DependencyFailedError())
                        : Try.of(Task.this::apply);
                  },
                  executor)
              .handle(
                  (taskResult, ex) -> {
                    Try<Void> finalResult =
                        taskResult != null
                            ? taskResult
                            : Try.failure(new ServiceError(ex.getMessage(), ex));
                    result.set(finalResult);
                    return finalResult;
                  });
      runningTaskFuture.set(future);
      return future;
    }
  }

  /**
   * Adds a dependency on another task.
   *
   * @param task the task that must complete before this task runs
   */
  public void dependsOn(Task task) {
    dependsOnTasks.add(task);
  }

  /**
   * Adds dependencies on multiple tasks.
   *
   * @param tasks the tasks that must complete before this task runs
   */
  public void dependsOn(Collection<? extends Task> tasks) {
    dependsOnTasks.addAll(tasks);
  }

  /**
   * Executes the task logic. Implementations should perform the actual work of the task.
   *
   * @return always returns null (Void)
   */
  public abstract Void apply();

  /**
   * Gets the unique identifier for this task, used for equality comparison and cycle detection.
   * Since a task is always performed on exactly one entity, the entity's id identifies the task.
   *
   * @return the id of the entity this task operates on
   */
  @EqualsAndHashCode.Include
  public String getId() {
    return entity.getId();
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
