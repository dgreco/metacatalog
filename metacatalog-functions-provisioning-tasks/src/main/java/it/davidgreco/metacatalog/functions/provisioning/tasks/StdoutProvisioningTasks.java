package it.davidgreco.metacatalog.functions.provisioning.tasks;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.provisioning.ProvisioningTasks;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Registers {@link StdoutProvisioningTask} as the provisioning task of every entity type named
 * under {@code application.config.provisioning.tasks.stdout.entity-types}.
 *
 * <p>The task prints what provisioning would do on standard output instead of performing it, which
 * makes this registrar the safe default for a fresh installation whose types are loaded only after
 * the application boots: the names come from configuration, but the types themselves are catalog
 * data created at runtime, so there is nothing to register against at startup. Registration happens
 * through {@link ProvisioningTasks} and is deferred — as a {@link
 * it.davidgreco.metacatalog.functions.provisioning.DeferredTaskFactoryRegistrar} it is asked again
 * by the provisioning procedures before every run, by which point the types exist.
 */
@Service
public class StdoutProvisioningTasks extends ProvisioningTasks<StdoutProvisioningTask> {

  /** The key of this task's entry in {@code application.config.provisioning.tasks}. */
  public static final String TASK_NAME = "stdout";

  private final ProvisioningConfigProperties provisioningConfigProperties;

  public StdoutProvisioningTasks(
      TaskManager taskManager,
      EntityService entityService,
      ProvisioningConfigProperties provisioningConfigProperties) {
    super(taskManager, entityService);
    this.provisioningConfigProperties = provisioningConfigProperties;
  }

  @Override
  protected List<String> entityTypeNames() {
    return provisioningConfigProperties.entityTypesOf(TASK_NAME);
  }

  @Override
  protected StdoutProvisioningTask createTask(Entity entity) {
    var taskConfig = provisioningConfigProperties.taskConfig(TASK_NAME);
    return new StdoutProvisioningTask(
        entity, entityService, taskConfig == null ? null : taskConfig.delay());
  }
}
