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
  private final List<Task<?>> tasks = new ArrayList<>();

  public Schedule() {
    this(UUID.randomUUID().toString());
  }

  public Schedule(String id) {
    this.id = id;
  }

  public <T> void addTask(Task<T> task) {
    tasks.add(task);
  }

  public <T> void addTasks(Collection<Task<T>> tsks) {
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
    Graph<Task<?>, DefaultEdge> taskGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
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
        .thenApply(
            ignored ->
                tasks.stream()
                    .map(Task::getResult)
                    .flatMap(Optional::stream)
                    .filter(Try::isFailure)
                    .findFirst()
                    .orElseGet(() -> Try.success(null)));
  }
}
