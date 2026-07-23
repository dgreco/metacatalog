package it.davidgreco.metacatalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vavr.control.Try;
import it.davidgreco.metacatalog.CoreConfigProperties;
import java.time.Duration;
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

  private static CoreConfigProperties testConfigProperties() {
    return new CoreConfigProperties(
        false, Duration.ofSeconds(1), 3, 100, Duration.ofHours(1), Duration.ofHours(1));
  }

  private final TaskManager taskManager =
      new TaskManager(new SimpleAsyncTaskExecutor(), testConfigProperties());

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
      var boundedTaskManager = new TaskManager(pool, testConfigProperties());
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

  /**
   * Verifies that when the {@link AsyncTaskExecutor} rejects a task (here via a saturated pool with
   * an {@code AbortPolicy}), the schedule still records a result for that task instead of leaving
   * it absent.
   *
   * <p>The task engine decorates every scheduled task with a {@code .handle()} stage that must
   * populate the per-task result slot whether the task succeeded, failed, or was never accepted by
   * the executor. If the handle stage is skipped on rejection, the result for the rejected task is
   * silently dropped and {@code getScheduleResults} returns a shorter list than the number of
   * submitted tasks, which in turn breaks any caller that joins results by index. This test forces
   * a rejection by sizing the pool (1) and queue (1) so the third submitted task cannot be
   * accepted, then asserts that all three tasks have an entry in the results and that at least one
   * entry is a failure.
   */
  @Test
  void executorRejectionStillSetsResult() throws Exception {
    var pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(1);
    pool.setMaxPoolSize(1);
    pool.setQueueCapacity(1);
    pool.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    pool.initialize();
    try {
      var boundedTaskManager = new TaskManager(pool, testConfigProperties());
      var schedule = boundedTaskManager.createSchedule();
      // Fill the pool (1 running) + queue (1 queued) = 2 tasks. The third must be rejected.
      var latch = new java.util.concurrent.CountDownLatch(1);
      schedule.addTask(
          new SimpleTask(
              "blocker",
              () -> {
                try {
                  latch.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }));
      schedule.addTask(new SimpleTask("queued1", () -> {}));
      schedule.addTask(new SimpleTask("rejected", () -> {}));

      var id = boundedTaskManager.schedule(schedule);
      // Release the blocker so the pool can drain.
      latch.countDown();
      boundedTaskManager.joinSchedule(id);

      // The rejected task's result must be present (Try.failure), not Optional.empty().
      var results = boundedTaskManager.getScheduleResults(id);
      assertEquals(
          3, results.size(), "all three task results must be present, even the rejected one");
      assertTrue(
          results.stream().anyMatch(Try::isFailure),
          "at least the rejected task must report a failure");
    } finally {
      pool.shutdown();
    }
  }

  /**
   * Verifies that the schedule-result cache is bounded by the configured TTL and maximum size.
   *
   * <p>{@link TaskManager} keeps completed schedules in a Caffeine cache so that callers can read
   * results and the running future shortly after a schedule finishes. The cache must not grow
   * unboundedly, so its expiry-after-write and maximum size are taken from {@link
   * CoreConfigProperties}. This test builds a {@link TaskManager} with a 100 ms TTL, schedules a
   * trivial task, waits for the cache entry to expire, and asserts that both {@code
   * getScheduleResults} and {@code getRunningScheduleFuture} return empty for the expired schedule
   * id. This guards against accidental hard-coding of the cache policy.
   */
  @Test
  void evictedScheduleReturnsEmptyResults() throws Exception {
    var tinyConfig =
        new CoreConfigProperties(
            false, Duration.ofSeconds(1), 3, 1, Duration.ofMillis(100), Duration.ofHours(1));
    var tinyTaskManager = new TaskManager(new SimpleAsyncTaskExecutor(), tinyConfig);
    var schedule = tinyTaskManager.createSchedule();
    schedule.addTask(new SimpleTask("t1", () -> {}));

    var id = tinyTaskManager.schedule(schedule);
    tinyTaskManager.joinSchedule(id);

    // Wait for the cache entry to expire.
    Thread.sleep(300);

    assertTrue(
        tinyTaskManager.getScheduleResults(id).isEmpty(),
        "evicted schedule must return an empty result list");
    assertTrue(
        tinyTaskManager.getRunningScheduleFuture(id).isEmpty(),
        "evicted schedule must not have a running future");
  }
}
