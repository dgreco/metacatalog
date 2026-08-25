package it.davidgreco.metacatalog.ui;

/**
 * An action offered on an instances-list row for the aggregate rooted at that entity, and the one
 * place each is described.
 *
 * <p>Adding the authorization pair to the page used to mean two more {@code @PostMapping}s, two
 * more near-identical {@code <form>} blocks differing in four strings, and a wider {@link
 * InstanceRowView} that two unrelated call sites then had to pad. None of that is where the
 * capability is actually defined. Here a row's actions are <em>data</em>: the controller picks the
 * constants that apply, the template renders whatever it is given, and a third capability adds enum
 * constants only.
 *
 * <p>Four of the five are the procedures, and their {@link #path} doubles as the operation name the
 * REST API takes. This deliberately does <em>not</em> reuse {@code ProvisioningTask.Operation}:
 * that lives in {@code metacatalog-functions}, and this module's build excludes the internal
 * modules on purpose so the UI can only reach the REST contract. The overlap is the contract's, not
 * a shortcut around it.
 */
public enum AggregateAction {

  /** Build every resource the aggregate contains, in dependency order. */
  PROVISION(
      "provision",
      "Provision",
      "Provisioning",
      "Provision the whole aggregate",
      "Provision this aggregate? Every resource it contains will be provisioned, in dependency"
          + " order.",
      Capability.PROVISIONABLE),

  /** Tear them down again, in the reverse order. */
  UNPROVISION(
      "unprovision",
      "Unprovision",
      "Unprovisioning",
      "Unprovision the whole aggregate",
      "Unprovision this aggregate? Every resource it contains will be torn down, in the reverse"
          + " order.",
      Capability.PROVISIONABLE),

  /** Grant access to every resource, in dependency order. */
  AUTHORIZE(
      "authorize",
      "Authorize",
      "Authorizing",
      "Authorize the whole aggregate",
      "Authorize this aggregate? Every resource it contains will be granted access, in dependency"
          + " order.",
      Capability.AUTHORIZABLE),

  /** Withdraw that access, in the reverse order. */
  REJECT(
      "reject",
      "Reject",
      "Rejecting",
      "Withdraw access to the whole aggregate",
      "Reject this aggregate? Access to every resource it contains will be withdrawn, in the"
          + " reverse order.",
      Capability.AUTHORIZABLE),

  /** Remove the aggregate as a whole. Not a procedure: it completes synchronously. */
  DELETE(
      "delete-aggregate",
      "Delete aggregate",
      null,
      "Delete the whole aggregate",
      "Delete this whole aggregate? Every entity it contains, and everything mapped from them,"
          + " will be removed.",
      Capability.AGGREGATE_ROOT);

  /** What a row must be for the action to be offered. */
  public enum Capability {
    AGGREGATE_ROOT,
    PROVISIONABLE,
    AUTHORIZABLE
  }

  private final String path;
  private final String label;
  private final String procedureLabel;
  private final String title;
  private final String confirm;
  private final Capability capability;

  AggregateAction(
      String path,
      String label,
      String procedureLabel,
      String title,
      String confirm,
      Capability capability) {
    this.path = path;
    this.label = label;
    this.procedureLabel = procedureLabel;
    this.title = title;
    this.confirm = confirm;
    this.capability = capability;
  }

  /** The last path segment the form posts to, and for a procedure its REST operation name. */
  public String getPath() {
    return path;
  }

  /** The button text. */
  public String getLabel() {
    return label;
  }

  /**
   * The label flashed for the progress popup, or {@code null} when the action is not an
   * asynchronous procedure. {@code procedure-poller.js} renders whatever it is given and knows
   * nothing about which procedure ran, which is why a new one needs no JavaScript change.
   */
  public String getProcedureLabel() {
    return procedureLabel;
  }

  /** The button's tooltip. */
  public String getTitle() {
    return title;
  }

  /** The text of the confirmation prompt. */
  public String getConfirm() {
    return confirm;
  }

  /** Whether this action is an asynchronously launched procedure rather than a direct call. */
  public boolean isProcedure() {
    return procedureLabel != null;
  }

  /** What a row must be for this action to be offered. */
  public Capability getCapability() {
    return capability;
  }
}
