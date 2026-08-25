package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.TaskFactory;
import it.davidgreco.metacatalog.service.TaskManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Procedure for provisioning entities with the Provisionable trait.
 *
 * <p>This procedure orchestrates the provisioning of aggregate entities by:
 *
 * <ol>
 *   <li>Reading the aggregate structure including mapped instances
 *   <li>Identifying all ProvisionableResource entities in the hierarchy
 *   <li>Building a dependency graph based on mapping relationships
 *   <li>Creating provisioning tasks for each resource
 *   <li>Scheduling and executing tasks in dependency order
 * </ol>
 *
 * <p>The procedure detects cycles in the provisioning graph and fails early if any are found.
 *
 * <p>Each task comes from the {@link TaskFactory} registered against the resource's entity type
 * name, so a resource type with no registered factory fails the run rather than being reported as
 * provisioned by nothing. See {@link UnprovisioningProcedure} for the inverse, and {@link
 * AbstractAggregateResourceProcedure} for the machinery all four aggregate procedures share.
 */
@Slf4j
@Service
public class ProvisioningProcedure extends AbstractAggregateResourceProcedure {

  public ProvisioningProcedure(
      AggregateService aggregateService,
      ResourceGraphBuilder resourceGraphBuilder,
      TaskManager taskManager,
      AggregateProvisioningStatusRecorder statusRecorder,
      ObjectProvider<DeferredTaskFactoryRegistrar> deferredRegistrars) {
    super(aggregateService, resourceGraphBuilder, taskManager, statusRecorder, deferredRegistrars);
  }

  @Override
  protected String rootTrait() {
    return BuiltInTraits.PROVISIONABLE;
  }

  @Override
  protected String resourceTrait() {
    return BuiltInTraits.PROVISIONABLE_RESOURCE;
  }

  @Override
  protected ProvisioningTask.Operation operation() {
    return ProvisioningTask.Operation.PROVISION;
  }

  /** A resource is provisioned only once everything it is derived from exists. */
  @Override
  protected boolean dependentsFirst() {
    return false;
  }
}
