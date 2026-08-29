package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.functions.EntityProcedure;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.TaskManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares one {@link AggregateResourceProcedure} bean per {@link ProvisioningTask.Operation}, so
 * {@code ProcedureExecutor} finds each under {@linkplain ProvisioningTask.Operation#procedureName()
 * its own name}. Everything a procedure does follows from its operation, so a fifth direction is an
 * enum constant and a bean method here — not a new class.
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
class AggregateResourceProcedures {

  private final AggregateService aggregateService;
  private final ResourceGraphBuilder resourceGraphBuilder;
  private final TaskManager taskManager;
  private final AggregateProvisioningStatusRecorder statusRecorder;
  private final ObjectProvider<DeferredTaskFactoryRegistrar> deferredRegistrars;

  private AggregateResourceProcedure procedure(ProvisioningTask.Operation operation) {
    return new AggregateResourceProcedure(
        operation,
        aggregateService,
        resourceGraphBuilder,
        taskManager,
        statusRecorder,
        deferredRegistrars);
  }

  @Bean
  EntityProcedure provisioningProcedure() {
    return procedure(ProvisioningTask.Operation.PROVISION);
  }

  @Bean
  EntityProcedure unprovisioningProcedure() {
    return procedure(ProvisioningTask.Operation.UNPROVISION);
  }

  @Bean
  EntityProcedure authorizationProcedure() {
    return procedure(ProvisioningTask.Operation.AUTHORIZE);
  }

  @Bean
  EntityProcedure rejectionProcedure() {
    return procedure(ProvisioningTask.Operation.REJECT);
  }
}
