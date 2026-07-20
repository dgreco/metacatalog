package it.davidgreco.metacatalog.functions.provisioning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
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

  private static final String PROVISIONING_STATUS = "provisioningStatus";
  private static final String PROVISIONING_RESULT = "provisioningResult";
  private static final String STATUS_PROVISIONED = "PROVISIONED";
  private static final String STATUS_FAILED = "FAILED";

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
      writeProvisioningStatus(STATUS_PROVISIONED, result);
    } catch (Exception e) {
      try {
        writeProvisioningStatus(STATUS_FAILED, e.getMessage());
      } catch (Exception statusUpdateFailure) {
        log.error(
            "Failed to record FAILED provisioning status for entity {}",
            getEntity().getId(),
            statusUpdateFailure);
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
   * Writes the provisioning status and result onto the entity's JSON values and persists them.
   *
   * @param status the provisioning status to record
   * @param result the provisioning result (or error message) to record
   * @throws ServiceRuntimeError if the entity values are not a JSON object
   * @throws ServiceError if updating the entity fails
   */
  private void writeProvisioningStatus(String status, String result) throws ServiceError {
    if (!(getEntity().getValues() instanceof ObjectNode values)) {
      throw new ServiceRuntimeError(
          "Entity "
              + getEntity().getId()
              + " values are not a JSON object; cannot record provisioning status");
    }
    values.put(PROVISIONING_STATUS, status);
    values.put(PROVISIONING_RESULT, result);
    getEntityService().update(getEntity().getId(), values.toPrettyString());
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
