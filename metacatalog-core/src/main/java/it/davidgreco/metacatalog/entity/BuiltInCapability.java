package it.davidgreco.metacatalog.entity;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A lifecycle the platform offers over an aggregate, declared once: the pair of built-in traits
 * that carry it, the pair of entity value fields it records its answer in, the statuses that answer
 * may take, and whatever else those two traits contribute beyond the answer itself.
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
 *
 * <p><b>The root and the resource trait no longer carry identical schemas.</b> They did while a
 * capability had nothing to say beyond its status, which was true of both until authorization grew
 * a policy. Now {@link #AUTHORIZATION} adds the policy to its root — the principals, the permission
 * vocabulary and the grants, all authored — and the resolved grants to its resource, written by the
 * machinery like the status. {@link #PROVISIONING} adds nothing to either and returns the same
 * schema from both accessors, which is what it always had.
 */
public enum BuiltInCapability {

  /** Creating and tearing down an aggregate's resources. */
  PROVISIONING(
      BuiltInTraits.PROVISIONABLE,
      BuiltInTraits.PROVISIONABLE_RESOURCE,
      "provisioningStatus",
      "provisioningResult",
      List.of("PROVISIONED", "UNPROVISIONED", "FAILED"),
      List.of(),
      List.of(),
      null),

  /**
   * Granting and withdrawing access to them. The root additionally carries the policy that decides
   * who gets what; see {@link AccessControl}.
   */
  AUTHORIZATION(
      BuiltInTraits.AUTHORIZABLE,
      BuiltInTraits.AUTHORIZABLE_RESOURCE,
      "authorizationStatus",
      "authorizationResult",
      List.of("AUTHORIZED", "REJECTED", "FAILED"),
      AccessControl.ROOT_PROPERTIES,
      AccessControl.RESOURCE_PROPERTIES,
      AccessControl.EFFECTIVE_GRANTS);

  /**
   * The machinery sets the status pair, not the user, so both are {@code readOnly}: the UI renders
   * them disabled and validators treat them as server-managed. The root trait and the resource
   * trait carry the same pair — on a resource the task records its own outcome, on a root the
   * procedure records the whole run's ({@code PROVISIONED} / {@code AUTHORIZED} only when every
   * contained resource succeeded).
   */
  private static final String STATUS_PROPERTIES =
      """
      "%1$s": {
        "type": "string",
        "enum": [%2$s],
        "readOnly": true
      },
      "%3$s": {
        "type": "string",
        "readOnly": true
      }""";

  private final String rootTrait;
  private final String resourceTrait;
  private final String statusField;
  private final String resultField;
  private final List<String> statuses;
  private final List<String> rootProperties;
  private final List<String> resourceProperties;
  private final String grantsField;

  BuiltInCapability(
      String rootTrait,
      String resourceTrait,
      String statusField,
      String resultField,
      List<String> statuses,
      List<String> rootProperties,
      List<String> resourceProperties,
      String grantsField) {
    this.rootTrait = rootTrait;
    this.resourceTrait = resourceTrait;
    this.statusField = statusField;
    this.resultField = resultField;
    this.statuses = statuses;
    this.rootProperties = rootProperties;
    this.resourceProperties = resourceProperties;
    this.grantsField = grantsField;
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
   * The entity value field a run records, on each resource, the grants it applied there — empty for
   * a capability that grants nothing.
   *
   * <p>A capability that has no such field must never have one written: a derived schema carries
   * {@code additionalProperties: false}, so writing {@code effectiveGrants} onto a resource that
   * only carries {@code ProvisionableResource} is refused by validation and fails the task. Callers
   * write it when this is present and not otherwise, rather than testing which capability they are.
   */
  public Optional<String> grantsField() {
    return Optional.ofNullable(grantsField);
  }

  /**
   * The base schema this capability's <em>root</em> trait carries: the status pair, plus whatever
   * else the capability's root declares.
   *
   * <p>Rendered from the same constants the machinery reads and writes with, so the schema can only
   * ever admit exactly the fields that are used.
   */
  public String rootSchema() {
    return objectSchema(rootProperties);
  }

  /**
   * The base schema this capability's <em>resource</em> trait carries: the status pair, plus
   * whatever else the capability records per resource.
   */
  public String resourceSchema() {
    return objectSchema(resourceProperties);
  }

  private String objectSchema(List<String> extraProperties) {
    var properties =
        Stream.concat(
                Stream.of(
                    STATUS_PROPERTIES.formatted(
                        statusField,
                        statuses.stream().map(s -> '"' + s + '"').collect(Collectors.joining(", ")),
                        resultField)),
                extraProperties.stream())
            .collect(Collectors.joining(",\n"));
    return """
        {
          "type": "object",
          "properties": {
        %s
          }
        }
        """
        .formatted(properties);
  }
}
