package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.entity.Entity;

public interface ProvisioningTaskFactory {

  /**
   * Create a provisioning task for the given entity.
   *
   * @param entity the entity for which to create the provisioning task
   * @return the provisioning task
   */
  ProvisioningTask createProvisionTask(Entity entity);
}
