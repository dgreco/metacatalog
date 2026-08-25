package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.TaskFactory;
import it.davidgreco.metacatalog.service.TaskManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Procedure for authorizing an aggregate: the authorization counterpart of {@link
 * ProvisioningProcedure}.
 *
 * <p>It acts only on the <em>root</em> of an aggregate, and only when that root carries the {@code
 * Authorizable} trait — a root that is merely {@code Provisionable} is refused, naming the missing
 * trait. From there it plans exactly as provisioning does:
 *
 * <ol>
 *   <li>Reading the aggregate structure including mapped instances
 *   <li>Identifying the {@code AuthorizableResource} entities in the hierarchy — every leaf
 *       <em>and</em> intermediate node carrying the trait, so an aggregate whose middle layer is
 *       itself a resource is authorized at that layer too
 *   <li>Building a dependency graph from the mapping relationships between them
 *   <li>Creating one task per resource, switched to {@link ProvisioningTask.Operation#AUTHORIZE}
 *   <li>Scheduling them in dependency order — a resource is authorized only once everything it is
 *       derived from has been, so a grant is never handed out on something still unreachable
 * </ol>
 *
 * <p>The tasks are the same {@link ProvisioningTask}s provisioning uses, taken from the same {@link
 * TaskFactory} registered against each resource's entity type name — a resource type with no
 * registered factory fails the run rather than being reported as authorized by nothing, and a task
 * that never overrode {@link ProvisioningTask#authorize()} fails naming its own class. Cycles are
 * detected up front and fail the run before any task executes.
 *
 * <p>The root records the whole run's outcome in {@code authorizationStatus} / {@code
 * authorizationResult} — {@code AUTHORIZED} only when every contained resource was, {@code FAILED}
 * with the error otherwise. Those are a different pair of fields from the provisioning ones on
 * purpose: an aggregate can be provisioned and not yet authorized, and one field could not hold
 * both answers.
 *
 * <p>See {@link RejectionProcedure} for the inverse.
 */
@Service
public class AuthorizationProcedure extends AbstractAggregateResourceProcedure {

  public AuthorizationProcedure(
      AggregateService aggregateService,
      ResourceGraphBuilder resourceGraphBuilder,
      TaskManager taskManager,
      AggregateProvisioningStatusRecorder statusRecorder,
      ObjectProvider<DeferredTaskFactoryRegistrar> deferredRegistrars) {
    super(
        ProvisioningTask.Operation.AUTHORIZE,
        aggregateService,
        resourceGraphBuilder,
        taskManager,
        statusRecorder,
        deferredRegistrars);
  }
}
