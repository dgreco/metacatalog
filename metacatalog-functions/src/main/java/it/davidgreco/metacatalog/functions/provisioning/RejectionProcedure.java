package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.TaskManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The inverse of {@link AuthorizationProcedure}: withdraws access from every {@code
 * AuthorizableResource} in an aggregate, leaving each one {@code REJECTED}.
 *
 * <p>It reads the same aggregate, selects the same resources and takes its tasks from the same
 * factories. Two things differ, exactly as they do between provisioning and unprovisioning, and
 * both follow from the operation alone.
 *
 * <p>First, the tasks run {@link ProvisioningTask.Operation#REJECT}, so they call {@code reject()}
 * and record {@code REJECTED} rather than {@code AUTHORIZED}.
 *
 * <p>Second, the dependencies are wired the other way round: access is withdrawn from what is
 * derived from a resource before it is withdrawn from the resource itself, so nothing is ever left
 * holding a grant on something it can no longer reach.
 */
@Service
public class RejectionProcedure extends AbstractAggregateResourceProcedure {

  public RejectionProcedure(
      AggregateService aggregateService,
      ResourceGraphBuilder resourceGraphBuilder,
      TaskManager taskManager,
      AggregateProvisioningStatusRecorder statusRecorder,
      ObjectProvider<DeferredTaskFactoryRegistrar> deferredRegistrars) {
    super(
        ProvisioningTask.Operation.REJECT,
        aggregateService,
        resourceGraphBuilder,
        taskManager,
        statusRecorder,
        deferredRegistrars);
  }
}
