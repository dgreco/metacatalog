package it.davidgreco.metacatalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vavr.control.Try;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Unit tests for the task engine's scheduling contract — cycle detection and failure propagation —
 * without a Spring context or database.
 */
class TaskEngineTest {

  private final TaskManager taskManager = new TaskManager(new SimpleAsyncTaskExecutor());

  /** A minimal concrete {@link Task} that runs an arbitrary action. */
  private static final class SimpleTask extends Task<String> {
    private final String id;
    private final Runnable action;

    SimpleTask(String id, Runnable action) {
      super(id);
      this.id = id;
      this.action = action;
    }

    @Override
    public Void apply() {
      action.run();
      return null;
    }

    @Override
    public String getId() {
      return id;
    }
  }

  @Test
  void cyclicScheduleIsRejected() {
    var schedule = taskManager.createSchedule();
    var t1 = new SimpleTask("t1", () -> {});
    var t2 = new SimpleTask("t2", () -> {});
    t1.dependsOn(t2);
    t2.dependsOn(t1);
    schedule.addTask(t1);
    schedule.addTask(t2);

    var error = assertThrows(ServiceError.class, () -> taskManager.schedule(schedule));
    assertTrue(error.getMessage().toLowerCase().contains("cycle"));
  }

  @Test
  void successfulScheduleReportsSuccess() throws Exception {
    var counter = new AtomicInteger();
    var schedule = taskManager.createSchedule();
    schedule.addTask(new SimpleTask("t1", counter::incrementAndGet));

    var id = taskManager.schedule(schedule);
    taskManager.joinSchedule(id);

    assertEquals(1, counter.get());
    var future = taskManager.getRunningScheduleFuture(id).orElseThrow();
    assertTrue(future.get().isSuccess());
    assertTrue(taskManager.getScheduleResults(id).stream().allMatch(Try::isSuccess));
  }

  @Test
  void failingTaskPropagatesToScheduleFuture() throws Exception {
    // Regression: the schedule future previously always returned success, masking task failures.
    var schedule = taskManager.createSchedule();
    schedule.addTask(
        new SimpleTask(
            "boom",
            () -> {
              throw new RuntimeException("boom");
            }));

    var id = taskManager.schedule(schedule);
    taskManager.joinSchedule(id);

    var future = taskManager.getRunningScheduleFuture(id).orElseThrow();
    assertFalse(future.get().isSuccess(), "a failing task must make the schedule future fail");
    assertTrue(taskManager.getScheduleResults(id).stream().anyMatch(Try::isFailure));
  }

  @Test
  @Timeout(30)
  void deepDependencyChainDoesNotStarveABoundedPool() throws Exception {
    // Regression for the thread-pool starvation deadlock: with the previous design each task
    // blocked a worker thread in join() while awaiting its dependencies, so a chain longer than the
    // pool wedged every thread. Here the pool has only 2 threads and a tiny queue, and the chain is
    // far longer — the composed (non-blocking) design must still complete.
    var pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(2);
    pool.setMaxPoolSize(2);
    pool.setQueueCapacity(4);
    pool.initialize();
    try {
      var boundedTaskManager = new TaskManager(pool);
      var order = new ConcurrentLinkedQueue<Integer>();
      var schedule = boundedTaskManager.createSchedule();
      SimpleTask previous = null;
      for (int i = 0; i < 40; i++) {
        int idx = i;
        var task = new SimpleTask("t" + i, () -> order.add(idx));
        if (previous != null) {
          task.dependsOn(previous);
        }
        schedule.addTask(task);
        previous = task;
      }

      var id = boundedTaskManager.schedule(schedule);
      // A deadlock would hang here; the timeout turns it into a failure instead.
      boundedTaskManager.getRunningScheduleFuture(id).orElseThrow().get(25, TimeUnit.SECONDS);

      assertEquals(40, order.size());
      assertEquals(IntStream.range(0, 40).boxed().toList(), order.stream().toList());
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void dependenciesRunBeforeDependents() throws Exception {
    var order = new StringBuilder();
    var schedule = taskManager.createSchedule();
    var dependency = new SimpleTask("dep", () -> order.append("dep"));
    var dependent = new SimpleTask("main", () -> order.append("main"));
    dependent.dependsOn(dependency);
    schedule.addTask(dependent);
    schedule.addTask(dependency);

    var id = taskManager.schedule(schedule);
    taskManager.joinSchedule(id);

    assertTrue(order.indexOf("dep") < order.indexOf("main"), "dependency must run first");
  }
}
