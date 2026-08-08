package it.davidgreco.metacatalog.bootstrap;

import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.RelationType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Declares the built-in traits the platform itself depends on: {@link BuiltInTraits#AGGREGATE},
 * {@link BuiltInTraits#AGGREGATE_ELEMENT}, {@link BuiltInTraits#PROVISIONABLE}, {@link
 * BuiltInTraits#PROVISIONABLE_RESOURCE}, and the composition between the first two.
 *
 * <p>These used to be {@code INSERT}s in the Flyway baseline. Declaring them here instead leaves a
 * single source of truth, expressed against the services rather than the tables: derived schemas,
 * version groups and the inverse {@code IS_PART_OF} row are computed exactly as they are for a
 * user-declared trait, instead of being hand-written in SQL that can drift from what the services
 * would have produced.
 *
 * <p>All of it is immutable, which is the whole reason the extension point exists. {@code
 * AggregateService}, {@code AggregateSchemaService} and the provisioning procedure key off these
 * traits <em>by name</em>, so deleting or re-versioning one would break that wiring with nothing
 * pointing back at the cause.
 *
 * <p>Ordered first so a module contributing its own model can inherit from these or mix them in.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BuiltInModelContributor implements ImmutableModelContributor {

  /**
   * The provisioning procedure sets {@code provisioningStatus} and {@code provisioningResult}, not
   * the user, so both are {@code readOnly}: the UI renders them disabled and validators treat them
   * as server-managed.
   */
  private static final String PROVISIONABLE_RESOURCE_SCHEMA =
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

  @Override
  public void contribute(ImmutableModelRegistry registry) {
    // Roots first: Provisionable and ProvisionableResource inherit from them.
    registry.trait(BuiltInTraits.AGGREGATE);
    registry.trait(BuiltInTraits.AGGREGATE_ELEMENT);
    registry.trait(BuiltInTraits.PROVISIONABLE, null, BuiltInTraits.AGGREGATE);
    registry.trait(
        BuiltInTraits.PROVISIONABLE_RESOURCE,
        PROVISIONABLE_RESOURCE_SCHEMA,
        BuiltInTraits.AGGREGATE_ELEMENT);
    // Composition: what makes a type able to contain another, and what AggregateSchemaService
    // projects onto the types carrying these traits to derive the aggregate model.
    registry.link(BuiltInTraits.AGGREGATE, RelationType.HAS_PART, BuiltInTraits.AGGREGATE_ELEMENT);
  }
}
