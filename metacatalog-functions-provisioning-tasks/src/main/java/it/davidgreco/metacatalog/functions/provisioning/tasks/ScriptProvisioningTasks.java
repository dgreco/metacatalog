package it.davidgreco.metacatalog.functions.provisioning.tasks;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.provisioning.ProvisioningTasks;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Registers {@link ScriptProvisioningTask} as the provisioning task of every entity type named
 * under {@code application.config.provisioning.tasks.script.entity-types}, running the bash script
 * configured as {@code application.config.provisioning.tasks.script.path}.
 *
 * <p>Like {@link StdoutProvisioningTasks} the registration is deferred — the entity type names are
 * configuration, but the types themselves are catalog data created at runtime. The script path is
 * different: it is pure configuration with nothing runtime about it, so a {@code script} entry that
 * names entity types but no path is refused at startup rather than surfacing as a failure of the
 * first provisioning run.
 */
@Service
public class ScriptProvisioningTasks extends ProvisioningTasks<ScriptProvisioningTask> {

  /** The key of this task's entry in {@code application.config.provisioning.tasks}. */
  public static final String TASK_NAME = "script";

  private final ProvisioningConfigProperties provisioningConfigProperties;

  public ScriptProvisioningTasks(
      TaskManager taskManager,
      EntityService entityService,
      ProvisioningConfigProperties provisioningConfigProperties) {
    super(taskManager, entityService);
    this.provisioningConfigProperties = provisioningConfigProperties;
    var taskConfig = provisioningConfigProperties.taskConfig(TASK_NAME);
    if (taskConfig != null && !taskConfig.entityTypes().isEmpty() && taskConfig.path() == null) {
      throw new IllegalStateException(
          "application.config.provisioning.tasks.script names entity types but no script path:"
              + " set application.config.provisioning.tasks.script.path");
    }
  }

  @Override
  protected List<String> entityTypeNames() {
    return provisioningConfigProperties.entityTypesOf(TASK_NAME);
  }

  @Override
  protected ScriptProvisioningTask createTask(Entity entity) {
    return new ScriptProvisioningTask(
        entity, entityService, provisioningConfigProperties.taskConfig(TASK_NAME).path());
  }
}
