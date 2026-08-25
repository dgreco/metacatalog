package it.davidgreco.metacatalog.bootstrap;

import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.RelationType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Declares the built-in traits the platform itself depends on: {@link BuiltInTraits#AGGREGATE},
 * {@link BuiltInTraits#AGGREGATE_ELEMENT}, the provisioning pair {@link
 * BuiltInTraits#PROVISIONABLE} / {@link BuiltInTraits#PROVISIONABLE_RESOURCE}, the authorization
 * pair {@link BuiltInTraits#AUTHORIZABLE} / {@link BuiltInTraits#AUTHORIZABLE_RESOURCE}, and the
 * composition between the first two.
 *
 * <p>These used to be {@code INSERT}s in the Flyway baseline. Declaring them here instead leaves a
 * single source of truth, expressed against the services rather than the tables: derived schemas,
 * version groups and the inverse {@code IS_PART_OF} row are computed exactly as they are for a
 * user-declared trait, instead of being hand-written in SQL that can drift from what the services
 * would have produced.
 *
 * <p>All of it is immutable, which is the whole reason the extension point exists. {@code
 * AggregateService}, {@code AggregateSchemaService} and the provisioning and authorization
 * procedures key off these traits <em>by name</em>, so deleting or re-versioning one would break
 * that wiring with nothing pointing back at the cause.
 *
 * <p>Ordered first so a module contributing its own model can inherit from these or mix them in.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BuiltInModelContributor implements ImmutableModelContributor {

  /**
   * The provisioning machinery sets {@code provisioningStatus} and {@code provisioningResult}, not
   * the user, so both are {@code readOnly}: the UI renders them disabled and validators treat them
   * as server-managed. Both provisioning traits carry the same pair — on a {@code
   * ProvisionableResource} the task records its own outcome, on a {@code Provisionable} the
   * procedure records the whole run's outcome ({@code PROVISIONED} only when every contained
   * resource provisioned successfully).
   */
  private static final String PROVISIONING_STATUS_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "provisioningStatus": {
            "type": "string",
            "enum": ["PROVISIONED", "UNPROVISIONED", "FAILED"],
            "readOnly": true
          },
          "provisioningResult": {
            "type": "string",
            "readOnly": true
          }
        }
      }
      """;

  /**
   * The authorization counterpart of {@code PROVISIONING_STATUS_SCHEMA}, and deliberately a
   * <em>separate</em> pair of fields rather than a reuse of the provisioning ones: an aggregate can
   * be provisioned and not yet authorized, or authorized and torn down, so one status field could
   * not hold both answers. Both authorization traits carry the same pair — on an {@code
   * AuthorizableResource} the task records its own outcome, on an {@code Authorizable} the
   * procedure records the whole run's outcome ({@code AUTHORIZED} only when every contained
   * resource was authorized successfully).
   *
   * <p>Note that a merged derived schema carries {@code additionalProperties: false}, so a task
   * writing these fields on an entity whose type does not carry an authorization trait is refused
   * by validation rather than silently accepted — which is why the authorization procedures only
   * ever build tasks for {@code AuthorizableResource} carriers.
   */
  private static final String AUTHORIZATION_STATUS_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "authorizationStatus": {
            "type": "string",
            "enum": ["AUTHORIZED", "REJECTED", "FAILED"],
            "readOnly": true
          },
          "authorizationResult": {
            "type": "string",
            "readOnly": true
          }
        }
      }
      """;

  @Override
  public void contribute(ImmutableModelRegistry registry) {
    // Roots first: the provisioning and authorization traits both inherit from them.
    registry.trait(BuiltInTraits.AGGREGATE);
    registry.trait(BuiltInTraits.AGGREGATE_ELEMENT);
    registry.trait(
        BuiltInTraits.PROVISIONABLE, PROVISIONING_STATUS_SCHEMA, BuiltInTraits.AGGREGATE);
    registry.trait(
        BuiltInTraits.PROVISIONABLE_RESOURCE,
        PROVISIONING_STATUS_SCHEMA,
        BuiltInTraits.AGGREGATE_ELEMENT);
    registry.trait(
        BuiltInTraits.AUTHORIZABLE, AUTHORIZATION_STATUS_SCHEMA, BuiltInTraits.AGGREGATE);
    registry.trait(
        BuiltInTraits.AUTHORIZABLE_RESOURCE,
        AUTHORIZATION_STATUS_SCHEMA,
        BuiltInTraits.AGGREGATE_ELEMENT);
    // Composition: what makes a type able to contain another, and what AggregateSchemaService
    // projects onto the types carrying these traits to derive the aggregate model.
    registry.link(BuiltInTraits.AGGREGATE, RelationType.HAS_PART, BuiltInTraits.AGGREGATE_ELEMENT);
  }
}
