package it.davidgreco.metacatalog.entity;

/**
 * Names of the built-in traits seeded by Flyway migration {@code V2}. Centralised here so the
 * services and procedures that key off them refer to a single source of truth rather than repeating
 * string literals (a typo in which would silently break trait-based behaviour).
 */
public final class BuiltInTraits {

  private BuiltInTraits() {}

  /** The aggregate root trait. */
  public static final String AGGREGATE = "Aggregate";

  /** The aggregate element (leaf) trait. */
  public static final String AGGREGATE_ELEMENT = "AggregateElement";

  /** The provisionable trait (an entity that can be provisioned). */
  public static final String PROVISIONABLE = "Provisionable";

  /** The provisionable-resource trait (a physical resource within a provisionable aggregate). */
  public static final String PROVISIONABLE_RESOURCE = "ProvisionableResource";
}
