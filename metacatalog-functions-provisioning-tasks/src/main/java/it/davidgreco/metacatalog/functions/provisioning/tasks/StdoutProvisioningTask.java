package it.davidgreco.metacatalog.functions.provisioning.tasks;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.provisioning.ProvisioningTask;
import it.davidgreco.metacatalog.service.EntityService;

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
 * <p>Standard output rather than the logger, deliberately: the application ships with {@code
 * logging.level.root: ERROR}, so an {@code INFO} line would never be seen.
 */
public class StdoutProvisioningTask extends ProvisioningTask {

  /** Prefix on every line, so a run can be grepped out of the application output. */
  static final String PROVISION_PREFIX = "[provisioning]";

  /** Prefix used when tearing a resource down. */
  static final String UNPROVISION_PREFIX = "[unprovisioning]";

  /**
   * @param entity the resource this task acts on
   * @param entityService used to record the outcome on the entity
   */
  public StdoutProvisioningTask(Entity entity, EntityService entityService) {
    super(entity, entityService);
  }

  @Override
  public String provision() {
    return report(PROVISION_PREFIX, "Provisioned");
  }

  @Override
  public String unprovision() {
    return report(UNPROVISION_PREFIX, "Unprovisioned");
  }

  private String report(String prefix, String outcome) {
    var entity = getEntity();
    var resource = entity.getEntityType().getName() + " id=" + entity.getId();
    // The values are printed because they are what a real task would act on — the bucket and path
    // of an S3FolderType, the database and table of an AthenaTableType.
    System.out.println(prefix + " start " + resource + " " + entity.getValues());
    System.out.println(prefix + " done  " + resource);
    return outcome + " " + resource + " (stdout task)";
  }
}
