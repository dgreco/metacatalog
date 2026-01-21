package it.davidgreco.metacatalog.functions.provisioning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;
import it.davidgreco.metacatalog.service.Task;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for provisioning tasks that operate on entities.
 *
 * <p>Subclasses must implement the {@link #provision()} method to perform the actual provisioning
 * logic. This base class handles the lifecycle of the provisioning operation, including updating
 * the entity's provisioning status and result.
 *
 * <p>After execution:
 *
 * <ul>
 *   <li>On success: provisioningStatus is set to "PROVISIONED" and provisioningResult contains the
 *       result
 *   <li>On failure: provisioningStatus is set to "FAILED" and provisioningResult contains the error
 *       message
 * </ul>
 */
@Slf4j
@Getter
public abstract class ProvisioningTask extends Task<Entity> {

  /** Service for updating entity values after provisioning. */
  private final EntityService entityService;

  /**
   * Creates a new provisioning task for the given entity.
   *
   * @param entity the entity to provision
   * @param entityService the service for updating entity values
   */
  protected ProvisioningTask(Entity entity, EntityService entityService) {
    super(entity);
    this.entityService = entityService;
  }

  /**
   * Executes the provisioning task and updates the entity's status.
   *
   * @return always returns null (Void)
   * @throws ServiceRuntimeError if provisioning fails
   */
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
      } catch (Exception _) {
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

  /**
   * Gets the unique identifier for this task, which is the entity's ID.
   *
   * @return the entity's ID
   */
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

  /**
   * Performs the actual provisioning operation.
   *
   * <p>Subclasses must implement this method to execute the specific provisioning logic for their
   * resource type.
   *
   * @return a result string describing the provisioning outcome
   */
  public abstract String provision();
}
