package it.davidgreco.metacatalog.functions.provisioning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.BuiltInCapability;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.AccessPolicy;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.Task;
import java.util.List;
import java.util.Locale;
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
 * <p>The same class also carries the authorization pair, {@link #authorize()} and {@link
 * #reject()}, driven by the {@code AUTHORIZE} / {@code REJECT} runs of {@link
 * AggregateResourceProcedure} over the {@code AuthorizableResource} entities of an aggregate.
 * Unlike the provisioning pair those two are <em>not</em> abstract: the two capabilities are
 * declared by two independent pairs of built-in traits, so a type may well be a {@code
 * ProvisionableResource} and never an {@code AuthorizableResource}, and forcing every existing task
 * to implement an authorization it does not have would buy nothing. They default to failing the
 * task, naming the class that did not implement them — a resource whose type carries {@code
 * AuthorizableResource} but whose task never overrode them fails its own run loudly, exactly as a
 * resource type with no registered factory does. A task that <em>does</em> authorize must override
 * both, for the same reason the provisioning pair is abstract.
 *
 * <p>Which of the four runs is decided by the procedure that schedules the task, through {@link
 * #setOperation}; it defaults to {@link Operation#PROVISION}. After execution:
 *
 * <ul>
 *   <li>On success: the operation's status field is set to "PROVISIONED", "UNPROVISIONED",
 *       "AUTHORIZED" or "REJECTED" depending on the operation, and its result field contains the
 *       result
 *   <li>On failure: the status field is set to "FAILED" and the result field contains the error
 *       message
 * </ul>
 *
 * <p>Which pair of fields is written is part of the operation, not of this class: provisioning
 * writes {@code provisioningStatus} / {@code provisioningResult}, authorization writes {@code
 * authorizationStatus} / {@code authorizationResult}. They are separate because the two answers are
 * independent — an aggregate can be provisioned and not yet authorized — and because a derived
 * schema carries {@code additionalProperties: false}, so writing the wrong pair on an entity whose
 * type does not carry the matching trait is refused by validation.
 */
@Slf4j
@Getter
public abstract class ProvisioningTask extends Task {

  static final String STATUS_FAILED = "FAILED";

  /**
   * The directions a provisioning task can run in, two per {@link BuiltInCapability}: one building
   * up and one tearing down.
   *
   * <p>Everything that distinguishes one direction from another is here, and the procedures read it
   * from here rather than restating it. Which traits a run selects on, which field pair it records
   * into and which way round it wires its tasks are all decided the moment the constant is named,
   * so no procedure can pair an operation with the wrong capability's traits — a combination that
   * used to be expressible and surfaced only at the end of a successful run, as a validation
   * refusal on the status write.
   */
  public enum Operation {
    /** Create the resource; leaves it {@code PROVISIONED}. */
    PROVISION(
        BuiltInCapability.PROVISIONING,
        "Provisioning",
        "PROVISIONED",
        false,
        "ProvisioningProcedure"),
    /** Tear the resource down; leaves it {@code UNPROVISIONED}. */
    UNPROVISION(
        BuiltInCapability.PROVISIONING,
        "Unprovisioning",
        "UNPROVISIONED",
        true,
        "UnprovisioningProcedure"),
    /** Grant access to the resource; leaves it {@code AUTHORIZED}. */
    AUTHORIZE(
        BuiltInCapability.AUTHORIZATION,
        "Authorizing",
        "AUTHORIZED",
        false,
        "AuthorizationProcedure"),
    /** Withdraw access to the resource; leaves it {@code REJECTED}. */
    REJECT(BuiltInCapability.AUTHORIZATION, "Rejecting", "REJECTED", true, "RejectionProcedure");

    private final BuiltInCapability capability;
    private final String label;
    private final String successStatus;
    private final boolean dependentsFirst;
    private final String procedureName;

    Operation(
        BuiltInCapability capability,
        String label,
        String successStatus,
        boolean dependentsFirst,
        String procedureName) {
      this.capability = capability;
      this.label = label;
      this.successStatus = successStatus;
      this.dependentsFirst = dependentsFirst;
      this.procedureName = procedureName;
    }

    /** The lifecycle this operation is one direction of. */
    BuiltInCapability capability() {
      return capability;
    }

    /** The status a successful run of this operation leaves behind. */
    String successStatus() {
      return successStatus;
    }

    /** The human label used in log lines and result messages. */
    public String label() {
      return label;
    }

    /** The name a script or external tool is handed to select this direction. */
    public String command() {
      return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The name the {@link AggregateResourceProcedure} running this operation is registered under —
     * the key {@code ProcedureExecutor} resolves and the name recorded in durable {@code
     * procedure_run} rows. Kept exactly as it was when each procedure was a class of this name.
     */
    public String procedureName() {
      return procedureName;
    }

    /**
     * The success status as a sentence word — {@code Provisioned}, {@code Authorized}, … — for
     * result messages. Derived rather than restated per task method: a hand-typed literal there
     * could drift from the operation actually run, the same slip {@code command()} exists to
     * prevent for the direction argument.
     */
    public String outcomeWord() {
      return successStatus.charAt(0) + successStatus.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The entity value field this operation's status is recorded in. */
    String statusField() {
      return capability.statusField();
    }

    /** The entity value field this operation's result message is recorded in. */
    String resultField() {
      return capability.resultField();
    }

    /**
     * {@code false} to make each resource wait for what it is derived from (building up), {@code
     * true} to make what it is derived from wait for the resource (tearing down).
     *
     * <p>The reasoning is the same in both capabilities. An Athena table reading an S3 folder is
     * created after the folder and destroyed before it, and by the same token access to it is
     * granted after — and withdrawn before — access to the folder it reads, so nothing is ever left
     * holding a grant on something it can no longer reach.
     */
    boolean dependentsFirst() {
      return dependentsFirst;
    }

    /**
     * Whether a run of this operation resolves the root's {@link
     * it.davidgreco.metacatalog.service.AccessPolicy} and hands each task the grants that apply to
     * its resource. Only {@code AUTHORIZE} does.
     *
     * <p>Tearing down deliberately does not. {@code REJECT} means "no access to this aggregate" and
     * needs no list to say it, which is what keeps it working as an emergency stop after the policy
     * has been emptied — or when it is malformed enough that resolving it would fail. Provisioning
     * has no policy to read in the first place.
     */
    public boolean appliesAccessPolicy() {
      return capability.grantsField().isPresent() && !dependentsFirst;
    }
  }

  /** Service for updating entity values after provisioning. */
  private final EntityService entityService;

  /**
   * Which operation {@link #apply()} performs. Defaults to provisioning, so a task scheduled by a
   * plain provisioning run needs no setting up.
   */
  @Setter private Operation operation = Operation.PROVISION;

  /**
   * The grants the root's policy resolves for this task's resource, set by the procedure alongside
   * {@link #setOperation} and empty for every operation that {@linkplain
   * Operation#appliesAccessPolicy() applies none}.
   *
   * <p>A task is handed this rather than fetching it: the policy lives on the aggregate root, the
   * procedure has already read the whole aggregate to build the plan, and a task walking {@code
   * IS_PART_OF} upwards for itself would re-read and re-parse the same document once per resource,
   * on whichever thread the executor happened to pick.
   *
   * <p>Subclasses translate these abstract permissions into whatever the system they drive actually
   * understands. One that is handed a permission it cannot translate must fail the resource naming
   * it — quietly ignoring it is the version of this bug that an auditor finds.
   */
  @Setter private List<AccessPolicy.EffectiveGrant> accessGrants = List.of();

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
            case AUTHORIZE -> authorize();
            case REJECT -> reject();
          };
      writeStatus(operation.successStatus, result);
    } catch (Exception e) {
      log.error("{} failed for entity {}", operation.label, getEntity().getId(), e);
      try {
        writeStatus(
            STATUS_FAILED, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } catch (Exception statusUpdateFailure) {
        log.error(
            "Failed to record FAILED {} status for entity {}",
            operation.label,
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

  /**
   * Records the outcome, and for a capability that grants anything, what this run left holding
   * here.
   *
   * <p>The grants are written from {@link #getAccessGrants()} on success and cleared to an empty
   * array on failure. Clearing rather than leaving the previous run's answer in place is the safe
   * reading of a failure: a query asking who may reach this resource would otherwise find grants
   * from a run that no longer describes reality, sitting beside a status field it never looked at.
   * An empty list next to {@code FAILED} says the catalog is claiming nothing, which is true.
   *
   * <p>The field is written only when the capability {@linkplain BuiltInCapability#grantsField()
   * has one}. Writing {@code effectiveGrants} onto a resource that carries only {@code
   * ProvisionableResource} would be refused by validation — a derived schema carries {@code
   * additionalProperties: false} — and would fail a provisioning run for a field it has no business
   * setting.
   */
  private void writeStatus(String status, String result) {
    if (!(getEntity().getValues() instanceof ObjectNode values)) {
      throw new ServiceError(
          "Entity "
              + getEntity().getId()
              + " values are not a JSON object; cannot record "
              + operation.label().toLowerCase(Locale.ROOT)
              + " status");
    }
    values.put(operation.statusField(), status);
    values.put(operation.resultField(), result);
    operation
        .capability()
        .grantsField()
        .ifPresent(
            field ->
                values.set(
                    field,
                    AccessPolicy.toJson(STATUS_FAILED.equals(status) ? List.of() : accessGrants)));
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

  /**
   * Grants access to the resource — whatever authorization means for its type: adding a grant to a
   * warehouse table, attaching a bucket policy, opening a share.
   *
   * <p>Runs in dependency order, like {@link #provision()}: a resource is authorized only once
   * everything it is derived from has been, so a grant is never handed out on something still
   * unreachable behind a closed door.
   *
   * <p>The default fails the task. Override it — together with {@link #reject()} — in any task
   * registered for a type carrying the {@code AuthorizableResource} trait.
   *
   * @return a result string describing the authorization outcome
   */
  public String authorize() {
    throw new ServiceError(unsupported("authorize"));
  }

  /**
   * Withdraws access to the resource — the inverse of {@link #authorize()}.
   *
   * <p>Runs in the reverse of the order authorization runs in, so access is withdrawn from what is
   * derived from a resource before it is withdrawn from the resource itself.
   *
   * <p>The default fails the task. Override it — together with {@link #authorize()} — in any task
   * registered for a type carrying the {@code AuthorizableResource} trait.
   *
   * @return a result string describing the rejection outcome
   */
  public String reject() {
    throw new ServiceError(unsupported("reject"));
  }

  private String unsupported(String method) {
    return getClass().getName()
        + " does not implement "
        + method
        + "(), so entity type "
        + getEntity().getEntityType().getName()
        + " cannot take part in an authorization run. Override authorize() and reject() on the"
        + " task registered for it.";
  }
}
