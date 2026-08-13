package it.davidgreco.metacatalog.functions.provisioning.tasks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The configurable delay exists to make an asynchronous run observable (the UI banner, the status
 * endpoint): the task must actually pause between its start and done lines, and must not pause at
 * all when no delay is configured.
 */
class StdoutProvisioningTaskDelayTests {

  private static Entity entity() {
    var type = new EntityType();
    type.setName("S3FolderType");
    return new Entity(type, new ObjectMapper().createObjectNode());
  }

  @Test
  void configuredDelayPausesTheTask() {
    var task = new StdoutProvisioningTask(entity(), null, Duration.ofMillis(200));
    var start = System.nanoTime();
    task.provision();
    var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
    assertTrue(elapsedMillis >= 200, "expected >= 200ms, took " + elapsedMillis + "ms");
  }

  @Test
  void withoutDelayTheTaskDoesNotPause() {
    var task = new StdoutProvisioningTask(entity(), null);
    var start = System.nanoTime();
    task.unprovision();
    var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
    assertTrue(elapsedMillis < 200, "expected no pause, took " + elapsedMillis + "ms");
  }
}
