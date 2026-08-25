package it.davidgreco.metacatalog.entity;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A lifecycle the platform offers over an aggregate, declared once: the pair of built-in traits
 * that carry it, the pair of entity value fields it records its answer in, and the statuses that
 * answer may take.
 *
 * <p>There are two, and they are <em>independent</em> — a type may offer provisioning,
 * authorization, both or neither — which is why each owns a separate field pair rather than sharing
 * one: an aggregate can be provisioned and not yet authorized, or authorized and torn down, and one
 * field could not hold both answers.
 *
 * <p>This exists for the same reason {@link BuiltInTraits} does, one level up. The trait names were
 * already a single source of truth; the field names were not — {@code BuiltInModelContributor}
 * spelled them into a JSON schema text block while {@code ProvisioningTask} re-declared them as
 * constants in another module, with nothing connecting the two. A typo in either half is invisible
 * until the end of a successful run, when the status write is refused by validation because a
 * derived schema carries {@code additionalProperties: false} — and since the traits are installed
 * immutable, the only remedy at that point is recreating the database. Declaring the fields and the
 * schema that admits them in one place makes the two halves incapable of disagreeing.
 */
public enum BuiltInCapability {

  /** Creating and tearing down an aggregate's resources. */
  PROVISIONING(
      BuiltInTraits.PROVISIONABLE,
      BuiltInTraits.PROVISIONABLE_RESOURCE,
      "provisioningStatus",
      "provisioningResult",
      List.of("PROVISIONED", "UNPROVISIONED", "FAILED")),

  /** Granting and withdrawing access to them. */
  AUTHORIZATION(
      BuiltInTraits.AUTHORIZABLE,
      BuiltInTraits.AUTHORIZABLE_RESOURCE,
      "authorizationStatus",
      "authorizationResult",
      List.of("AUTHORIZED", "REJECTED", "FAILED"));

  /**
   * The machinery sets both fields, not the user, so both are {@code readOnly}: the UI renders them
   * disabled and validators treat them as server-managed. The root trait and the resource trait
   * carry the same pair — on a resource the task records its own outcome, on a root the procedure
   * records the whole run's ({@code PROVISIONED} / {@code AUTHORIZED} only when every contained
   * resource succeeded).
   */
  private static final String STATUS_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "%s": {
            "type": "string",
            "enum": [%s],
            "readOnly": true
          },
          "%s": {
            "type": "string",
            "readOnly": true
          }
        }
      }
      """;

  private final String rootTrait;
  private final String resourceTrait;
  private final String statusField;
  private final String resultField;
  private final List<String> statuses;

  BuiltInCapability(
      String rootTrait,
      String resourceTrait,
      String statusField,
      String resultField,
      List<String> statuses) {
    this.rootTrait = rootTrait;
    this.resourceTrait = resourceTrait;
    this.statusField = statusField;
    this.resultField = resultField;
    this.statuses = statuses;
  }

  /** The trait an aggregate root must carry to offer this capability. */
  public String rootTrait() {
    return rootTrait;
  }

  /** The trait an aggregate member must carry to take part in a run of this capability. */
  public String resourceTrait() {
    return resourceTrait;
  }

  /** The entity value field a run of this capability records its status in. */
  public String statusField() {
    return statusField;
  }

  /** The entity value field a run of this capability records its result message in. */
  public String resultField() {
    return resultField;
  }

  /**
   * The base schema both of this capability's traits carry: the two fields, and the statuses the
   * first may take.
   *
   * <p>Rendered from the same constants the machinery writes with, so the schema can only ever
   * admit exactly the fields that are written. Kept byte-comparable to what it replaced — {@code
   * ImmutableModelInstaller} compares existing immutable rows against their declaration as parsed
   * nodes and aborts startup on a difference, so a database created before this refactor must still
   * read as unchanged.
   */
  public String statusSchema() {
    return STATUS_SCHEMA.formatted(
        statusField,
        statuses.stream().map(s -> '"' + s + '"').collect(Collectors.joining(", ")),
        resultField);
  }
}
