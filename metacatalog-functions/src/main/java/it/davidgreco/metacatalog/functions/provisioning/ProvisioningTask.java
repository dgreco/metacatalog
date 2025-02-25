package it.davidgreco.metacatalog.functions.provisioning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;
import it.davidgreco.metacatalog.service.Task;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public abstract class ProvisioningTask extends Task<Entity> {

  private final EntityService entityService;

  protected ProvisioningTask(Entity entity, EntityService entityService) {
    super(entity);
    this.entityService = entityService;
  }

  @Override
  public Void apply() {
    log.info(
        "Provisioning task for entity: "
            + getEntity()
            + " executed by thread: "
            + Thread.currentThread().getName());
    try {
      var result = provision();
      ObjectNode values = (ObjectNode) getEntity().getValues();
      values.put("provisioningStatus", "PROVISIONED");
      values.put("provisioningResult", result);
      getEntityService().update(getEntity().getId(), values.toPrettyString());
    } catch (Exception e) {
      try {
        ObjectNode values = (ObjectNode) getEntity().getValues();
        values.put("provisioningStatus", "FAILED");
        values.put("provisioningResult", e.getMessage());
        getEntityService().update(getEntity().getId(), values.toPrettyString());
      } catch (Exception ex) {
        throw new ServiceRuntimeError(e);
      }
      throw new ServiceRuntimeError(e);
    } finally {
      log.info(
          "Provisioning task for entity: "
              + getEntity()
              + " completed by thread: "
              + Thread.currentThread().getName());
    }
    return null;
  }

  @Override
  public String getId() {
    return getEntity().getId();
  }

  @Override
  public boolean equals(Object o) {
    return super.equals(o);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  public abstract String provision();
}
