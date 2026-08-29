package it.davidgreco.metacatalog.entity;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The vocabulary the {@link BuiltInCapability#AUTHORIZATION} capability's policy is written in: the
 * value fields an aggregate root is authored with, the field each resource's answer is recorded in,
 * and the JSON Schema fragments that admit exactly those.
 *
 * <p>It lives beside {@link BuiltInCapability} and for the same reason: the fields are written by
 * {@code metacatalog-functions} and read by {@code AccessPolicy}, so declaring them here — and
 * rendering the schema from the same constants — makes the two halves incapable of disagreeing. A
 * name that appears only in a text block on one side and only in a Java literal on the other stays
 * wrong until the write at the end of an otherwise successful run, and the traits are immutable, so
 * by then the only remedy is recreating the database.
 *
 * <p><b>Shapes are inlined, not factored into {@code $defs}/{@code $ref}.</b> {@code
 * JsonUtils.mergeSchemas} emits a fresh node carrying only {@code type}, {@code properties}, {@code
 * required} and {@code additionalProperties}, so {@code $defs} does not survive into the derived
 * schema and every reference would dangle — and {@code $ref} is a rejected keyword besides. Shared
 * shapes are composed from constants instead, exactly as {@code HiveModel} does.
 *
 * <p><b>The model.</b> An aggregate root declares three things and the resources declare nothing:
 *
 * <ul>
 *   <li>{@link #PRINCIPALS} — the subjects this aggregate recognises, each a reference into
 *       whatever identity provider is behind the deployment. Deliberately <em>not</em> catalog
 *       entities: an identity directory is something a catalog federates from, not something it
 *       owns, which is the conclusion Ranger, Lake Formation and Unity Catalog all reached.
 *   <li>{@link #PERMISSIONS} — the vocabulary this aggregate can grant, and it is deliberately
 *       <em>abstract</em> ({@code READ}, {@code WRITE}) rather than any one catalog's native
 *       privilege names. One data product can publish output ports through two catalogs at once —
 *       the mixed demo does — and native names on the root would leave a consumer reading the
 *       product-level policy unable to tell whether the Iceberg half and the Hive half meant the
 *       same thing. Translating an abstract permission into what a given system actually
 *       understands is the task's job, which is the same split Ranger draws between a policy's
 *       accesses and a service definition's access types.
 *   <li>{@link #GRANTS} — the policy proper: principals × permissions × an optional resource
 *       selector. Both sides are referenced <em>by name</em> into the two lists above, so a
 *       misspelling is a refusal at plan time rather than a grant silently made to nobody.
 * </ul>
 *
 * <p>Every enum here is deliberately wider than any current caller needs. Adding a value later
 * means editing an immutable trait's base schema, which aborts startup on every existing database
 * and can only be resolved by recreating it — this is precisely the {@code
 * Provisionable}-gained-a-schema case {@code ImmutableModelInstaller}'s drift check exists to
 * catch. Being generous once is much cheaper than being sorry twice.
 */
public final class AccessControl {

  private AccessControl() {}

  /** What kind of subject a principal names. */
  public enum PrincipalType {
    /** A single human identity. */
    USER,
    /** A set of identities maintained by the identity provider. */
    GROUP,
    /** A named bundle of permissions identities are assigned to, in the SQL-standard sense. */
    ROLE,
    /** A non-human identity: a pipeline, a job, a machine account. */
    SERVICE
  }

  /** The subjects an aggregate recognises. Authored on the root. */
  public static final String PRINCIPALS = "principals";

  /** The permission vocabulary an aggregate can grant. Authored on the root. */
  public static final String PERMISSIONS = "permissions";

  /** The policy: which principals hold which permissions over which resources. Authored on root. */
  public static final String GRANTS = "grants";

  /**
   * The grants a run actually applied to a resource. Recorded by the machinery on each resource.
   */
  public static final String EFFECTIVE_GRANTS = "effectiveGrants";

  /** A principal's identifier, as the identity provider knows it. */
  public static final String PRINCIPAL_ID = "id";

  /** A principal's {@link PrincipalType}. */
  public static final String PRINCIPAL_TYPE = "type";

  /** A permission's name, referenced by grants. */
  public static final String PERMISSION_NAME = "name";

  /** The free-text explanation carried by a principal or a permission. */
  public static final String DESCRIPTION = "description";

  /** A grant's optional narrowing of which resources it reaches; absent means all of them. */
  public static final String GRANT_RESOURCES = "resources";

  /** A selector's entity-type filter. */
  public static final String SELECTOR_ENTITY_TYPES = "entityTypes";

  /** A selector's exact-match filter over a resource's own values. */
  public static final String SELECTOR_VALUES = "values";

  /** The single principal an effective grant was resolved for. */
  public static final String EFFECTIVE_GRANT_PRINCIPAL = "principal";

  private static final String STRING_ARRAY =
      """
      { "type": "array", "minItems": 1, "items": { "type": "string" } }""";

  private static final String PRINCIPAL_ITEM =
      """
      {
        "type": "object",
        "required": ["%1$s", "%2$s"],
        "properties": {
          "%1$s": { "type": "string", "minLength": 1 },
          "%2$s": { "type": "string", "enum": [%3$s] },
          "%4$s": { "type": "string" }
        },
        "additionalProperties": false
      }"""
          .formatted(PRINCIPAL_ID, PRINCIPAL_TYPE, principalTypes(), DESCRIPTION);

  private static final String PERMISSION_ITEM =
      """
      {
        "type": "object",
        "required": ["%1$s"],
        "properties": {
          "%1$s": { "type": "string", "minLength": 1 },
          "%2$s": { "type": "string" }
        },
        "additionalProperties": false
      }"""
          .formatted(PERMISSION_NAME, DESCRIPTION);

  /**
   * Which resources of the aggregate a grant reaches. Both filters are optional and combine with
   * AND; a selector omitted altogether reaches every resource, which is the common case and the
   * literal reading of "the root's policy authorizes the aggregate".
   *
   * <p>{@link #SELECTOR_ENTITY_TYPES} is the universal filter — entities carry no name column, so
   * their type name is the only label every resource is guaranteed to have. {@link
   * #SELECTOR_VALUES} narrows further by exact match on the resource's own values.
   *
   * <p>Exact match rather than a JSON path expression, deliberately. The selector is evaluated in
   * memory against an aggregate the procedure has already read, so the Postgres {@code jsonpath}
   * that {@code EntityService.list} pushes into the database is not available here, and reaching
   * for Jayway instead would put a <em>second</em> path dialect into the model under the same name
   * — two syntaxes for what looks like one concept, differing only in the cases nobody tests.
   */
  private static final String RESOURCE_SELECTOR =
      """
      {
        "type": "object",
        "properties": {
          "%1$s": %3$s,
          "%2$s": {
            "type": "object",
            "additionalProperties": { "type": ["string", "number", "boolean"] }
          }
        },
        "additionalProperties": false
      }"""
          .formatted(SELECTOR_ENTITY_TYPES, SELECTOR_VALUES, STRING_ARRAY);

  private static final String GRANT_ITEM =
      """
      {
        "type": "object",
        "required": ["%1$s", "%2$s"],
        "properties": {
          "%1$s": %4$s,
          "%2$s": %4$s,
          "%3$s": %5$s
        },
        "additionalProperties": false
      }"""
          .formatted(PRINCIPALS, PERMISSIONS, GRANT_RESOURCES, STRING_ARRAY, RESOURCE_SELECTOR);

  /**
   * The properties the {@code Authorizable} trait adds beyond the capability's status pair: the
   * policy, authored by whoever owns the data product.
   */
  public static final List<String> ROOT_PROPERTIES =
      List.of(
          """
          "%s": { "type": "array", "items": %s }"""
              .formatted(PRINCIPALS, PRINCIPAL_ITEM),
          """
          "%s": { "type": "array", "items": %s }"""
              .formatted(PERMISSIONS, PERMISSION_ITEM),
          """
          "%s": { "type": "array", "items": %s }"""
              .formatted(GRANTS, GRANT_ITEM));

  /**
   * The property the {@code AuthorizableResource} trait adds: what an authorization run actually
   * applied here, written by the machinery and so {@code readOnly} like the status pair.
   *
   * <p>This is what makes access a queryable catalog fact rather than a side effect that only the
   * target system knows about — "which tables may {@code sales-analysts} read?" becomes one jsonb
   * query, answerable over resources published through different catalogs by different tasks.
   *
   * <p>A resource no grant reaches records an empty array rather than omitting the field: "access
   * decided, nobody holds it" and "never asked" must not read the same.
   */
  public static final List<String> RESOURCE_PROPERTIES =
      List.of(
          """
          "%1$s": {
            "type": "array",
            "readOnly": true,
            "items": {
              "type": "object",
              "required": ["%2$s", "%3$s"],
              "properties": {
                "%2$s": %4$s,
                "%3$s": %5$s
              },
              "additionalProperties": false
            }
          }"""
              .formatted(
                  EFFECTIVE_GRANTS,
                  EFFECTIVE_GRANT_PRINCIPAL,
                  PERMISSIONS,
                  PRINCIPAL_ITEM,
                  STRING_ARRAY));

  private static String principalTypes() {
    return Arrays.stream(PrincipalType.values())
        .map(t -> '"' + t.name() + '"')
        .collect(Collectors.joining(", "));
  }
}
