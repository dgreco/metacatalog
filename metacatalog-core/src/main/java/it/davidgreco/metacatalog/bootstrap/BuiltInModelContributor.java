package it.davidgreco.metacatalog.bootstrap;

import it.davidgreco.metacatalog.entity.BuiltInCapability;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.RelationType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Declares the built-in traits the platform itself depends on: {@link BuiltInTraits#AGGREGATE},
 * {@link BuiltInTraits#AGGREGATE_ELEMENT}, the trait pair of every {@link BuiltInCapability} — the
 * provisioning pair {@link BuiltInTraits#PROVISIONABLE} / {@link
 * BuiltInTraits#PROVISIONABLE_RESOURCE} and the authorization pair {@link
 * BuiltInTraits#AUTHORIZABLE} / {@link BuiltInTraits#AUTHORIZABLE_RESOURCE} — and the composition
 * between the first two.
 *
 * <p>The capabilities are looped over rather than spelled out one by one: each names its own traits
 * and renders its own status schema, so this class holds no field names and a third capability is
 * declared by adding an enum constant, not by copying a block here.
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

  @Override
  public void contribute(ImmutableModelRegistry registry) {
    // Roots first: every capability trait inherits from one of them.
    registry.trait(BuiltInTraits.AGGREGATE);
    registry.trait(BuiltInTraits.AGGREGATE_ELEMENT);
    // Then each capability's pair, both carrying that capability's status/result schema: on the
    // resource the task records its own outcome, on the root the procedure records the whole run's.
    for (var capability : BuiltInCapability.values()) {
      registry.trait(capability.rootTrait(), capability.statusSchema(), BuiltInTraits.AGGREGATE);
      registry.trait(
          capability.resourceTrait(), capability.statusSchema(), BuiltInTraits.AGGREGATE_ELEMENT);
    }
    // Composition: what makes a type able to contain another, and what AggregateSchemaService
    // projects onto the types carrying these traits to derive the aggregate model.
    registry.link(BuiltInTraits.AGGREGATE, RelationType.HAS_PART, BuiltInTraits.AGGREGATE_ELEMENT);
  }
}
