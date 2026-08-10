package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Registers {@link StdoutProvisioningTask} as the provisioning task of every entity type named
 * under {@code application.config.provisioning.entity-types}.
 *
 * <p>The task prints what provisioning would do on standard output instead of performing it, which
 * makes this registrar the safe default for a fresh installation whose types are loaded only after
 * the application boots: the names come from configuration, but the types themselves are catalog
 * data created at runtime, so there is nothing to register against at startup. Registration happens
 * through {@link ProvisioningTasks} and is deferred — {@link
 * it.davidgreco.metacatalog.functions.DeferredTaskFactoryRegistrar} asks it again before every
 * procedure run, by which point the types exist.
 */
@Service
public class StdoutProvisioningTasks extends ProvisioningTasks<StdoutProvisioningTask> {

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
    return provisioningConfigProperties.entityTypes();
  }

  @Override
  protected StdoutProvisioningTask createTask(Entity entity) {
    return new StdoutProvisioningTask(entity, entityService);
  }
}
