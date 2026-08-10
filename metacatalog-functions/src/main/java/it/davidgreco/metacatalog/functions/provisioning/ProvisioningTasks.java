package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.DeferredTaskFactoryRegistrar;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.NotFoundException;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for {@link DeferredTaskFactoryRegistrar}s that register a single {@link
 * ProvisioningTask} subclass as the provisioning task of several entity type names.
 *
 * <p>A concrete registrar declares its task class as the {@code T} type parameter and implements
 * {@link #createTask(Entity)} to build one; it reports the entity type names it handles through
 * {@link #entityTypeNames()}. {@link #ensureRegistered()} then registers {@code createTask} as the
 * factory of every one of those names.
 *
 * <p>Registration is idempotent and lenient, because the names are usually configuration while the
 * types are catalog data created at runtime: a name that already has a factory is skipped, and a
 * name that no entity type has yet is downgraded to a warning rather than failing the run — see
 * {@link TaskManager#registerTaskFactory}.
 */
@Slf4j
public abstract class ProvisioningTasks<T extends ProvisioningTask>
    implements DeferredTaskFactoryRegistrar {

  private final TaskManager taskManager;

  /** Exposed to subclasses so {@link #createTask} can hand it to the task constructor. */
  protected final EntityService entityService;

  protected ProvisioningTasks(TaskManager taskManager, EntityService entityService) {
    this.taskManager = taskManager;
    this.entityService = entityService;
  }

  @Override
  public void ensureRegistered() {
    ensureRegistered(entityTypeNames());
  }

  /**
   * Registers {@link #createTask} as the provisioning task factory of every entity type name in the
   * list.
   *
   * <p>Names that already have a factory are skipped, and names that no entity type has yet are
   * left to a warning rather than failing the run: this is asked before <em>every</em> procedure
   * execution, so one stale name must not fail the provisioning of unrelated aggregates. Nothing is
   * lost by being lenient — a resource whose type really has no factory still fails its own run,
   * with {@code No factory for name: ...}.
   *
   * @param entityTypeNames the names of the entity types this registrar provisions
   */
  protected void ensureRegistered(List<String> entityTypeNames) {
    for (var entityTypeName : entityTypeNames) {
      if (taskManager.isRegistered(entityTypeName)) continue;
      try {
        taskManager.registerTaskFactory(entityTypeName, this::createTask);
        log.info("Registered the provisioning task for entity type {}", entityTypeName);
      } catch (NotFoundException e) {
        log.warn(
            "Not registering the provisioning task for entity type {} yet: {}. Check the"
                + " provisioning configuration if this persists after the model is loaded.",
            entityTypeName,
            e.getMessage());
      }
    }
  }

  /** The names of the entity types this registrar provisions. */
  protected abstract List<String> entityTypeNames();

  /** Builds a {@code T} task for the given entity. */
  protected abstract T createTask(Entity entity);
}
