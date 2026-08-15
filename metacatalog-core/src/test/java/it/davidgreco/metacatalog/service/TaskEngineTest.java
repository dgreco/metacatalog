package it.davidgreco.metacatalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.CoreConfigProperties;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Unit tests for the task engine's scheduling contract — cycle detection and failure propagation —
 * without a Spring context or database.
 */
class TaskEngineTest {

  private static CoreConfigProperties testConfigProperties() {
    return new CoreConfigProperties(false, Duration.ofSeconds(1), 3, 100, Duration.ofHours(1));
  }

  /**
   * These tests never register a factory — they exercise scheduling only — so a stub is enough to
   * satisfy the dependency {@code registerTaskFactory} uses to reject unknown entity types. This is
   * a plain unit test with no Spring context or database behind it.
   */
  private static EntityTypeService stubEntityTypeService() {
    return Mockito.mock(EntityTypeService.class);
  }

  private final TaskManager taskManager =
      new TaskManager(
          new SimpleAsyncTaskExecutor(), testConfigProperties(), stubEntityTypeService());

  /** A transient entity of a synthetic type, enough for the engine to identify a task. */
  private static Entity testEntity(String id) {
    var entityType = new EntityType();
    entityType.setName("SimpleTaskType");
    var entity = new Entity();
    entity.setId(id);
    entity.setEntityType(entityType);
    return entity;
  }

  /** A minimal concrete {@link Task} that runs an arbitrary action. */
  private static final class SimpleTask extends Task {
    private final Runnable action;

    SimpleTask(String id, Runnable action) {
      super(testEntity(id));
      this.action = action;
    }

    @Override
    public Void apply() {
      action.run();
      return null;
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

    var handle = taskManager.schedule(schedule);
    handle.future().get();

    assertEquals(1, counter.get());
    assertTrue(handle.future().get().isSuccess());
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

    var handle = taskManager.schedule(schedule);

    assertFalse(
        handle.future().get().isSuccess(), "a failing task must make the schedule future fail");
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
      var boundedTaskManager =
          new TaskManager(pool, testConfigProperties(), stubEntityTypeService());
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

      var handle = boundedTaskManager.schedule(schedule);
      // A deadlock would hang here; the timeout turns it into a failure instead.
      handle.future().get(25, TimeUnit.SECONDS);

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

    var handle = taskManager.schedule(schedule);
    handle.future().get();

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
   * silently dropped and {@code Task.getResult()} returns empty. This test forces a rejection by
   * sizing the pool (1) and queue (1) so the third submitted task cannot be accepted, then asserts
   * that the schedule future completes with a failure.
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
      var boundedTaskManager =
          new TaskManager(pool, testConfigProperties(), stubEntityTypeService());
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

      var handle = boundedTaskManager.schedule(schedule);
      // Release the blocker so the pool can drain.
      latch.countDown();

      // The schedule future must complete with a failure (the rejected task).
      assertFalse(handle.future().get().isSuccess(), "rejected task must make the schedule fail");
    } finally {
      pool.shutdown();
    }
  }
}
