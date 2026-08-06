package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.TaskManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Registers {@link StdoutProvisioningTask} as the provisioning task of every entity type listed
 * under {@code application.config.provisioning.entity-types}.
 *
 * <p>Registration is by entity type name because that is the key both procedures look a task up by,
 * and one registration covers both directions — the task implements provisioning and unprovisioning
 * alike.
 *
 * <p>Registering at startup is safe even though entity types are created at runtime: {@link
 * TaskManager#registerTaskFactory} only records a name, and the type itself is not resolved until a
 * run creates the task.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StdoutProvisioningFunctions {

  private final TaskManager taskManager;

  private final EntityService entityService;

  private final ProvisioningConfigProperties provisioningConfigProperties;

  /**
   * Registers the task for each configured entity type. With none configured this does nothing,
   * which leaves provisioning behaving exactly as it did before: a resource type with no registered
   * task fails the run rather than being silently treated as provisioned.
   */
  @PostConstruct
  public void registerFunctions() {
    for (var entityTypeName : provisioningConfigProperties.entityTypes()) {
      taskManager.registerTaskFactory(
          entityTypeName, entity -> new StdoutProvisioningTask(entity, entityService));
      log.info("Registered the stdout provisioning task for entity type {}", entityTypeName);
    }
  }
}
