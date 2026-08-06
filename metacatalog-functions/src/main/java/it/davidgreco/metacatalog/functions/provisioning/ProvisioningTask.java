package it.davidgreco.metacatalog.functions.provisioning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.Task;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for provisioning tasks that operate on entities.
 *
 * <p>Subclasses must implement {@link #provision()} and {@link #unprovision()} — what creating and
 * tearing down the resource actually mean for their type. Both live on the same class so a type
 * cannot end up with a way to create a resource and no way to remove it. This base class handles
 * the lifecycle around them, including updating the entity's provisioning status and result.
 *
 * <p>Which of the two runs is decided by the procedure that schedules the task, through {@link
 * #setOperation}; it defaults to {@link Operation#PROVISION}. After execution:
 *
 * <ul>
 *   <li>On success: provisioningStatus is set to "PROVISIONED" or "UNPROVISIONED" depending on the
 *       operation, and provisioningResult contains the result
 *   <li>On failure: provisioningStatus is set to "FAILED" and provisioningResult contains the error
 *       message
 * </ul>
 */
@Slf4j
@Getter
public abstract class ProvisioningTask extends Task {

  private static final String PROVISIONING_STATUS = "provisioningStatus";
  private static final String PROVISIONING_RESULT = "provisioningResult";
  private static final String STATUS_FAILED = "FAILED";

  /** The two directions a provisioning task can run in. */
  public enum Operation {
    /** Create the resource; leaves it {@code PROVISIONED}. */
    PROVISION("Provisioning", "PROVISIONED"),
    /** Tear the resource down; leaves it {@code UNPROVISIONED}. */
    UNPROVISION("Unprovisioning", "UNPROVISIONED");

    private final String label;
    private final String successStatus;

    Operation(String label, String successStatus) {
      this.label = label;
      this.successStatus = successStatus;
    }
  }

  /** Service for updating entity values after provisioning. */
  private final EntityService entityService;

  /**
   * Which operation {@link #apply()} performs. Defaults to provisioning, so a task scheduled by
   * {@link ProvisioningProcedure} needs no setting up.
   */
  @Setter private Operation operation = Operation.PROVISION;

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

  @Override
  public Void apply() {
    log.info(
        "{} task for entity: {} executed by thread: {}",
        operation.label,
        getEntity().getId(),
        Thread.currentThread().getName());
    try {
      var result =
          switch (operation) {
            case PROVISION -> provision();
            case UNPROVISION -> unprovision();
          };
      writeProvisioningStatus(operation.successStatus, result);
    } catch (Exception e) {
      log.error("{} failed for entity {}", operation.label, getEntity().getId(), e);
      try {
        writeProvisioningStatus(
            STATUS_FAILED, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } catch (Exception statusUpdateFailure) {
        log.error(
            "Failed to record FAILED provisioning status for entity {}",
            getEntity().getId(),
            statusUpdateFailure);
      }
      throw new ServiceError(operation.label + " failed for entity " + getEntity().getId(), e);
    } finally {
      log.info(
          "{} task for entity: {} completed by thread: {}",
          operation.label,
          getEntity().getId(),
          Thread.currentThread().getName());
    }
    return null;
  }

  private void writeProvisioningStatus(String status, String result) {
    if (!(getEntity().getValues() instanceof ObjectNode values)) {
      throw new ServiceError(
          "Entity "
              + getEntity().getId()
              + " values are not a JSON object; cannot record provisioning status");
    }
    values.put(PROVISIONING_STATUS, status);
    values.put(PROVISIONING_RESULT, result);
    getEntityService().updateValues(getEntity().getId(), values.toPrettyString());
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

  /**
   * Performs the actual unprovisioning operation — the inverse of {@link #provision()}.
   *
   * <p>Runs in the reverse of the order provisioning runs in, so by the time this is called nothing
   * in the aggregate still depends on the resource being removed.
   *
   * @return a result string describing the unprovisioning outcome
   */
  public abstract String unprovision();
}
