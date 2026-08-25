package it.davidgreco.metacatalog.functions.provisioning.tasks;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.provisioning.ProvisioningTask;
import it.davidgreco.metacatalog.service.EntityService;
import java.util.Locale;

/**
 * A provisioning task that reports what it would do on standard output instead of creating or
 * destroying anything.
 *
 * <p>It is a real, registered task rather than a test double: a resource type with no registered
 * factory fails the whole run, so something has to be registered for provisioning to work at all.
 * This one makes a run observable — the lines appear in the order the schedule executes the tasks,
 * which is dependency order going in and the reverse of it coming back out — while leaving the
 * surrounding machinery (dependency graph, status write-back, failure handling) exactly as it is
 * for a task that does real work.
 *
 * <p>It reports the authorization pair the same way, so a type carrying {@code
 * AuthorizableResource} is covered by the same registration and an authorization run is just as
 * observable as a provisioning one.
 *
 * <p>Standard output rather than the logger, deliberately: the application ships with {@code
 * logging.level.root: ERROR}, so an {@code INFO} line would never be seen.
 */
public class StdoutProvisioningTask extends ProvisioningTask {

  /** Pause between the start and done lines, simulating a slow provisioning function. */
  private final java.time.Duration delay;

  /**
   * @param entity the resource this task acts on
   * @param entityService used to record the outcome on the entity
   */
  public StdoutProvisioningTask(Entity entity, EntityService entityService) {
    this(entity, entityService, null);
  }

  /**
   * @param entity the resource this task acts on
   * @param entityService used to record the outcome on the entity
   * @param delay how long to pause per resource between the start and done lines ({@code
   *     application.config.provisioning.tasks.stdout.delay}), making an asynchronous run's progress
   *     observable; {@code null} or zero means no pause
   */
  public StdoutProvisioningTask(
      Entity entity, EntityService entityService, java.time.Duration delay) {
    super(entity, entityService);
    this.delay = delay;
  }

  @Override
  public String provision() {
    return report("Provisioned");
  }

  @Override
  public String unprovision() {
    return report("Unprovisioned");
  }

  @Override
  public String authorize() {
    return report("Authorized");
  }

  @Override
  public String reject() {
    return report("Rejected");
  }

  /**
   * Prints the start/done pair and returns the outcome message.
   *
   * <p>The line prefix — {@code [provisioning]}, {@code [authorizing]}, … — is the operation's own
   * label rather than a constant per direction: the procedure has already selected the operation by
   * the time this runs, so a fifth direction gets its prefix for free and no method can print a
   * prefix belonging to another one.
   */
  private String report(String outcome) {
    var entity = getEntity();
    var prefix = "[" + getOperation().label().toLowerCase(Locale.ROOT) + "]";
    var resource = entity.getEntityType().getName() + " id=" + entity.getId();
    // The values are printed because they are what a real task would act on — the bucket and path
    // of an S3FolderType, the database and table of an AthenaTableType.
    System.out.println(prefix + " start " + resource + " " + entity.getValues());
    pause();
    System.out.println(prefix + " done  " + resource);
    return outcome + " " + resource + " (stdout task)";
  }

  private void pause() {
    if (delay == null || delay.isZero() || delay.isNegative()) {
      return;
    }
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
