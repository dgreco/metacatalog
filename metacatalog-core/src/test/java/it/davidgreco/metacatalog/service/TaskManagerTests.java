package it.davidgreco.metacatalog.service;

import io.vavr.control.Try;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
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

  /** A transient entity of the type the test factory is registered for. */
  private static Entity testEntity(String id) {
    var entityType = new EntityType();
    entityType.setName("SimpleTaskType");
    var entity = new Entity();
    entity.setId(id);
    entity.setEntityType(entityType);
    return entity;
  }

  @Test
  void testSchedulingWithDependencies() throws Exception {
    var taskManager = getApplicationContext().getBean(TaskManager.class);

    var list = new ConcurrentLinkedDeque<String>();

    taskManager.registerTaskFactory(
        "SimpleTaskType",
        entity ->
            new Task(entity) {
              public Void apply() {
                list.add(entity.getId());
                return null;
              }
            });

    var task1 = taskManager.createTask(testEntity("1"));
    var task2 = taskManager.createTask(testEntity("2"));
    var task3 = taskManager.createTask(testEntity("3"));
    var task4 = taskManager.createTask(testEntity("4"));

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
        entity ->
            new Task(entity) {
              public Void apply() {
                list.add(entity.getId());
                if (entity.getId().equals("2")) {
                  throw new RuntimeException("Error");
                }
                return null;
              }
            });

    var task1 = taskManager.createTask(testEntity("1"));
    var task2 = taskManager.createTask(testEntity("2"));
    var task3 = taskManager.createTask(testEntity("3"));

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

  @Test
  void createTaskFailsWhenNoFactoryIsRegisteredForTheEntityType() {
    var taskManager = getApplicationContext().getBean(TaskManager.class);

    var entityType = new EntityType();
    entityType.setName("UnregisteredType");
    var entity = new Entity();
    entity.setId("orphan");
    entity.setEntityType(entityType);

    var error = Assertions.assertThrows(ServiceError.class, () -> taskManager.createTask(entity));
    Assertions.assertEquals("No factory for name: UnregisteredType", error.getMessage());
  }
}
