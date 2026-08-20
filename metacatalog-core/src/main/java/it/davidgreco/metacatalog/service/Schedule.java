package it.davidgreco.metacatalog.service;

import io.vavr.control.Try;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * A collection of tasks to be executed together.
 *
 * <p>Tasks can be added to a schedule and then submitted for parallel execution. The schedule
 * validates that there are no cycles in the task dependency graph before execution.
 */
@Getter
@Slf4j
public class Schedule {

  private final String id;
  private final List<Task> tasks = new ArrayList<>();

  /** Creates a schedule with a random UUID as its ID. */
  public Schedule() {
    this(UUID.randomUUID().toString());
  }

  /**
   * Creates a schedule with the given ID.
   *
   * @param id the schedule identifier
   */
  public Schedule(String id) {
    this.id = id;
  }

  /**
   * Adds a task to this schedule. Dependencies of the task need not be added explicitly — each task
   * schedules its own dependencies — but only tasks added here contribute their result to the
   * outcome of {@link #schedule(AsyncTaskExecutor)}.
   *
   * @param task the task to add
   */
  public void addTask(Task task) {
    tasks.add(task);
  }

  /**
   * Adds all of the given tasks to this schedule.
   *
   * @param tsks the tasks to add
   */
  public void addTasks(Collection<? extends Task> tsks) {
    tasks.addAll(tsks);
  }

  /**
   * Schedules all tasks for execution, respecting dependency ordering.
   *
   * <p>Cycle detection is performed via JGraphT before any task is submitted. Each task recursively
   * schedules its dependencies, and the returned future completes when all tasks have completed.
   * Nothing blocks a worker thread while awaiting dependencies, so wide/deep graphs cannot starve
   * the pool.
   *
   * @param executor the executor used to run tasks
   * @return a future that completes with the first task failure, or success if all tasks succeed
   */
  public CompletableFuture<Try<Void>> schedule(AsyncTaskExecutor executor) {
    Graph<Task, DefaultEdge> taskGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
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

    List<CompletableFuture<Try<Void>>> futures =
        tasks.stream().map(task -> task.schedule(executor)).toList();
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(ignored -> reportedFailure().orElseGet(() -> Try.success(null)));
  }

  /**
   * The one failure a schedule reports when several tasks failed.
   *
   * <p>A failed task takes its dependents down with it, and each of those is failed with a {@link
   * DependencyFailedError} that says nothing about what went wrong. Those are picked last: in a
   * chain of three where the first resource fails, two of the three results are that restatement,
   * so choosing by position — the task list is built from a {@code HashMap}'s values, in no
   * meaningful order — reported the symptom rather than the cause most of the time, and varied
   * between runs of the same failure. This is what the aggregate root records in {@code
   * provisioningResult}, so it is the text someone reads to find out what broke.
   *
   * <p>Which originating failure wins when genuinely several resources failed independently is
   * still unspecified. They are all real causes, so any of them is a useful answer.
   */
  private Optional<Try<Void>> reportedFailure() {
    var failures =
        tasks.stream()
            .map(Task::getResult)
            .flatMap(Optional::stream)
            .filter(Try::isFailure)
            .toList();
    return failures.stream()
        .filter(failure -> !(failure.getCause() instanceof DependencyFailedError))
        .findFirst()
        .or(() -> failures.stream().findFirst());
  }
}
