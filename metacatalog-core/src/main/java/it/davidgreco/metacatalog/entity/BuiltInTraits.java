package it.davidgreco.metacatalog.entity;

/**
 * Names of the built-in traits installed at startup by {@code
 * it.davidgreco.metacatalog.bootstrap.BuiltInModelContributor}. Centralised here so the services
 * and procedures that key off them refer to a single source of truth rather than repeating string
 * literals (a typo in which would silently break trait-based behaviour).
 *
 * <p>Because that wiring is by name, all eight traits are installed immutable and cannot be deleted
 * or re-versioned through the API.
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

  /** The authorizable trait (an aggregate whose access can be authorized or rejected). */
  public static final String AUTHORIZABLE = "Authorizable";

  /** The authorizable-resource trait (a resource within an authorizable aggregate). */
  public static final String AUTHORIZABLE_RESOURCE = "AuthorizableResource";

  /**
   * The asset trait: what makes an aggregate root a data platform asset — something that is managed
   * as a whole, provisioned and authorized.
   *
   * <p>It inherits {@link #PROVISIONABLE} and mixes in {@link #AUTHORIZABLE}, and so carries {@link
   * #AGGREGATE} through both. That is the entire definition: it adds no field and no behaviour of
   * its own, it only names the combination, so that "is this an asset" is one trait test rather
   * than a convention every asset type has to remember to follow.
   */
  public static final String ASSET = "Asset";

  /**
   * The versionable trait: every instance of a type carrying it, directly or through inheritance,
   * keeps a snapshot of each state its values have been in.
   */
  public static final String VERSIONABLE = "Versionable";
}
