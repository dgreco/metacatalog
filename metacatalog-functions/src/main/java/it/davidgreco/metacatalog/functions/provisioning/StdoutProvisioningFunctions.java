package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.functions.DeferredTaskFactoryRegistrar;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.NotFoundException;
import it.davidgreco.metacatalog.service.TaskManager;
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
 * <p>The names come from configuration but the types are catalog data created at runtime, so there
 * is nothing to register against when the application boots. This used to register in
 * {@code @PostConstruct} and get away with it, because registration only recorded a string. Now
 * that {@link TaskManager#registerTaskFactory} refuses a name no entity type has — which is what
 * catches a typo in this very list — registering that early would fail on any fresh installation
 * whose types have yet to be loaded, the shipped Docker Compose demo included. So this is a {@link
 * DeferredTaskFactoryRegistrar}: it is asked again before every procedure run, by which point the
 * types exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StdoutProvisioningFunctions implements DeferredTaskFactoryRegistrar {

  private final TaskManager taskManager;

  private final EntityService entityService;

  private final ProvisioningConfigProperties provisioningConfigProperties;

  /**
   * Registers the task for each configured entity type that is not registered yet.
   *
   * <p>Whether the type exists is {@link TaskManager#registerTaskFactory}'s judgement to make, not
   * this class's — re-checking it here would duplicate the rule and let the two drift. So this
   * simply attempts the registration and treats the refusal as "not yet".
   *
   * <p>The refusal is downgraded to a warning rather than propagated because this runs before
   * <em>every</em> procedure execution: one stale name in the list would otherwise fail the
   * provisioning of unrelated aggregates. Nothing is lost by being lenient here — a resource whose
   * type really has no factory still fails its own run, with {@code No factory for name: ...}.
   *
   * <p>With no types configured this does nothing, which leaves provisioning behaving exactly as it
   * did before: a resource type with no registered task fails the run rather than being silently
   * treated as provisioned.
   */
  @Override
  public void ensureRegistered() {
    for (var entityTypeName : provisioningConfigProperties.entityTypes()) {
      if (taskManager.isRegistered(entityTypeName)) continue;
      try {
        taskManager.registerTaskFactory(
            entityTypeName, entity -> new StdoutProvisioningTask(entity, entityService));
        log.info("Registered the stdout provisioning task for entity type {}", entityTypeName);
      } catch (NotFoundException e) {
        log.warn(
            "Not registering the stdout provisioning task yet: {}. Check"
                + " application.config.provisioning.entity-types if this persists after the model"
                + " is loaded.",
            e.getMessage());
      }
    }
  }
}
