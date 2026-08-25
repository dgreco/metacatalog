package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.TaskFactory;
import it.davidgreco.metacatalog.service.TaskManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The inverse of {@link ProvisioningProcedure}: tears down every ProvisionableResource in an
 * aggregate, leaving each one {@code UNPROVISIONED}.
 *
 * <p>It reads the same aggregate, builds the same graph through {@link ResourceGraphBuilder} and
 * takes its tasks from the same {@link TaskFactory} registered against each resource's entity type
 * name. Two things differ, and both follow from the operation alone.
 *
 * <p>First, the tasks run {@link ProvisioningTask.Operation#UNPROVISION}, so they call {@code
 * unprovision()} and record {@code UNPROVISIONED} rather than {@code PROVISIONED}.
 *
 * <p>Second — and this is the part worth reading twice — the dependencies are wired the other way
 * round. Provisioning makes a resource wait for what it is built from; unprovisioning makes what it
 * is built from wait for <em>it</em>. An Athena table reading an S3 folder is created after the
 * folder and destroyed before it, so nothing is ever removed while something still depends on it.
 */
@Service
public class UnprovisioningProcedure extends AbstractAggregateResourceProcedure {

  public UnprovisioningProcedure(
      AggregateService aggregateService,
      ResourceGraphBuilder resourceGraphBuilder,
      TaskManager taskManager,
      AggregateProvisioningStatusRecorder statusRecorder,
      ObjectProvider<DeferredTaskFactoryRegistrar> deferredRegistrars) {
    super(
        ProvisioningTask.Operation.UNPROVISION,
        aggregateService,
        resourceGraphBuilder,
        taskManager,
        statusRecorder,
        deferredRegistrars);
  }
}
