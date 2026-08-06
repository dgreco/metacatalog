package it.davidgreco.metacatalog.service;

import io.vavr.control.Try;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class TaskManagerTests extends CommonServiceTestingSupport {

  public TaskManagerTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testSchedulingWithDependencies() throws Exception {
    var taskManager = getApplicationContext().getBean(TaskManager.class);

    var list = new ConcurrentLinkedDeque<String>();

    taskManager.registerTaskFactory(
        "SimpleTaskType",
        String.class,
        entity ->
            new Task<String>(entity) {
              public Void apply() {
                list.add(entity);
                return null;
              }

              public String getId() {
                return entity;
              }
            });

    var task1 = taskManager.createTask("1", "SimpleTaskType");
    var task2 = taskManager.createTask("2", "SimpleTaskType");
    var task3 = taskManager.createTask("3", "SimpleTaskType");
    var task4 = taskManager.createTask("4", "SimpleTaskType");

    var schedule = taskManager.createSchedule();

    schedule.addTasks(List.of(task1, task2, task3, task4));

    task1.dependsOn(task3);

    task2.dependsOn(task4);

    task4.dependsOn(task1);

    var handle = taskManager.schedule(schedule);
    handle.future().get();

    Assertions.assertEquals(List.of("3", "1", "4", "2"), list.stream().toList());
  }

  @Test
  void testSchedulingWithExceptions() throws Exception {
    var taskManager = getApplicationContext().getBean(TaskManager.class);

    var list = new ConcurrentLinkedDeque<String>();

    taskManager.registerTaskFactory(
        "SimpleTaskType",
        String.class,
        entity ->
            new Task<String>(entity) {
              public Void apply() {
                list.add(entity);
                if (entity.equals("2")) {
                  throw new RuntimeException("Error");
                }
                return null;
              }

              public String getId() {
                return entity;
              }
            });

    var task1 = taskManager.createTask("1", "SimpleTaskType");
    var task2 = taskManager.createTask("2", "SimpleTaskType");
    var task3 = taskManager.createTask("3", "SimpleTaskType");

    var schedule = taskManager.createSchedule();

    schedule.addTasks(List.of(task1, task2, task3));

    task1.dependsOn(task2);

    var handle = taskManager.schedule(schedule);

    // The schedule future must complete with a failure (task 2 throws).
    Assertions.assertFalse(handle.future().get().isSuccess());

    // Verify task results directly — task 2's result is recorded on the task itself.
    Assertions.assertEquals(
        List.of(true, true, false),
        List.of(task1, task2, task3).stream()
            .map(Task::getResult)
            .flatMap(java.util.Optional::stream)
            .map(Try::isFailure)
            .toList());
  }
}
