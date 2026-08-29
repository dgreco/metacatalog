package it.davidgreco.metacatalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import it.davidgreco.metacatalog.entity.AccessControl;
import it.davidgreco.metacatalog.entity.BuiltInCapability;
import it.davidgreco.metacatalog.entity.Entity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The access policy an {@code Authorizable} aggregate root is authored with, and the resolution of
 * it into the grants that actually apply to each {@code AuthorizableResource} a run covers.
 *
 * <p>The root is the single place the answer lives. A resource never declares who may reach it; it
 * is told, by the run, what the root decided about it. That is what makes "who can read this data
 * product?" a question with one place to look, and it is why the expressiveness lives in a grant's
 * {@link ResourceSelector} rather than in a second authority further down the tree.
 *
 * <p><b>Three lists, not one.</b> {@link #principals()} and {@link #permissions()} are declared
 * once and referenced by name from {@link #grants()}, so a group named in four grants is spelled
 * once — and a misspelling in the fifth is a refusal at plan time (see {@link #resolve}) rather
 * than a grant quietly made to nobody. They are also what a catalog UI can show: this product
 * recognises these subjects and offers these permissions, whether or not a grant currently uses
 * them.
 *
 * <p><b>This is desired state, not a change request.</b> Editing the policy and re-running the
 * authorization procedure is how access changes; the run converges the target systems on whatever
 * the policy now says. There is deliberately no per-grant revoke — {@code REJECT} means "no access
 * to this aggregate", needs no list, and therefore keeps working as an emergency stop even when the
 * policy has been emptied or is malformed. Every declarative policy engine in this space works this
 * way, and it is the only reading under which "empty the grants, then reject" has an answer at all.
 *
 * @param principals the subjects this aggregate recognises
 * @param permissions the vocabulary it can grant
 * @param grants the policy: which principals hold which permissions over which resources
 */
public record AccessPolicy(
    List<Principal> principals, List<Permission> permissions, List<Grant> grants) {

  /**
   * A subject a grant can name: a reference into whatever identity provider is behind the
   * deployment, not an entity in the catalog.
   *
   * @param id the identifier the identity provider knows the subject by
   * @param type what kind of subject it is
   * @param description free text, or empty
   */
  public record Principal(
      String id, AccessControl.PrincipalType type, Optional<String> description) {}

  /**
   * A permission the aggregate can grant. Abstract by design — see {@link AccessControl} for why
   * this is not an Iceberg or a Hive privilege name.
   *
   * @param name the permission name grants reference
   * @param description free text, or empty
   */
  public record Permission(String name, Optional<String> description) {}

  /**
   * One line of the policy: these principals hold these permissions over the resources the selector
   * reaches.
   *
   * @param principals principal ids, each of which must appear in {@link AccessPolicy#principals()}
   * @param permissions permission names, each of which must appear in {@link
   *     AccessPolicy#permissions()}
   * @param resources which of the aggregate's resources this reaches
   */
  public record Grant(
      List<String> principals, List<String> permissions, ResourceSelector resources) {
    public Grant {
      resources = resources == null ? ResourceSelector.ALL : resources;
    }
  }

  /**
   * Which resources of an aggregate a grant reaches. The two filters combine with AND, and an empty
   * one of either kind is not a filter at all — {@link #ALL} reaches every resource, which is the
   * common case and the literal reading of "the root's policy authorizes the aggregate".
   *
   * @param entityTypes entity type names a resource must be one of; empty means any
   * @param values value fields a resource must carry with exactly these values; empty means any
   */
  public record ResourceSelector(List<String> entityTypes, Map<String, JsonNode> values) {

    public ResourceSelector {
      entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
      values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** The selector a grant with no {@code resources} gets: every resource in the aggregate. */
    public static final ResourceSelector ALL = new ResourceSelector(List.of(), Map.of());

    /**
     * Whether this selector reaches the given resource.
     *
     * @param resource the candidate resource
     * @return true if every filter admits it
     */
    public boolean matches(Entity resource) {
      if (!entityTypes.isEmpty() && !entityTypes.contains(resource.getEntityType().getName()))
        return false;
      var resourceValues = resource.getValues();
      if (resourceValues == null || !resourceValues.isObject()) return values.isEmpty();
      return values.entrySet().stream()
          .allMatch(
              e ->
                  resourceValues.has(e.getKey())
                      && sameScalar(resourceValues.get(e.getKey()), e.getValue()));
    }

    /**
     * Human-readable form, used in the message when a selector reaches nothing.
     *
     * @return a description of what this selector asks for
     */
    public String describe() {
      if (entityTypes.isEmpty() && values.isEmpty()) return "every resource";
      var parts = new ArrayList<String>();
      if (!entityTypes.isEmpty())
        parts.add(AccessControl.SELECTOR_ENTITY_TYPES + "=" + entityTypes);
      if (!values.isEmpty()) parts.add(AccessControl.SELECTOR_VALUES + "=" + values);
      return String.join(", ", parts);
    }

    /**
     * Scalar equality that does not depend on how a number was spelled. Jackson gives {@code 1} an
     * {@code IntNode} and {@code 1.0} a {@code DoubleNode}, and those are never {@code equals} —
     * which would make a selector match or not depending on whether the document that created the
     * entity happened to write a trailing zero.
     */
    private static boolean sameScalar(JsonNode actual, JsonNode expected) {
      if (actual.isNumber() && expected.isNumber())
        return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
      return actual.equals(expected);
    }
  }

  /**
   * What a run resolved for one resource and one principal: everything the policy grants that
   * principal there, merged across every grant that reached it.
   *
   * @param principal the subject, as declared on the root
   * @param permissions the permissions it holds here, sorted and de-duplicated
   */
  public record EffectiveGrant(Principal principal, List<String> permissions) {}

  /** The policy of an aggregate that declares none: nothing recognised, nothing granted. */
  public static final AccessPolicy EMPTY = new AccessPolicy(List.of(), List.of(), List.of());

  /**
   * The schema the policy fields are checked against before binding: the same fragment the {@code
   * Authorizable} trait carries, compiled once. Reusing it rather than re-implementing its checks
   * imperatively keeps a single definition of the shape — a hand-rolled parser here once accepted
   * empty grant lists and empty principal ids the schema refuses, which is exactly the
   * two-definitions drift {@link BuiltInCapability} exists to prevent.
   */
  private static final JsonSchema ROOT_SCHEMA =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
          .getSchema(BuiltInCapability.AUTHORIZATION.rootSchema());

  /**
   * Binds the validated policy fields into the records. Its own mapper: {@code Optional} needs the
   * Jdk8 module, and this class is reachable from static contexts with no injected one.
   */
  private static final ObjectMapper BINDER =
      JsonMapper.builder().addModule(new Jdk8Module()).build();

  /**
   * Reads the policy off an aggregate root's values.
   *
   * <p>Entity writes already validate against the type's derived schema — but only for as long as
   * the concrete entity type has not restated the property itself, which type linearization
   * permits. So the values are re-validated here against the {@code Authorizable} trait's own
   * schema fragment, naming what did not conform, rather than assuming validation has been here
   * first — and rather than restating the schema's checks as a second, weaker parser.
   *
   * @param root the aggregate root entity
   * @return its policy, or {@link #EMPTY} if it declares none
   * @throws ServiceError if a policy is present but malformed
   */
  public static AccessPolicy of(Entity root) {
    var values = root.getValues();
    if (values == null || !values.isObject()) return EMPTY;
    var where = " on aggregate root " + root.getId();
    var violations = ROOT_SCHEMA.validate(values);
    if (!violations.isEmpty())
      throw new ServiceError(
          "Invalid access policy"
              + where
              + ": "
              + violations.stream().map(Object::toString).collect(Collectors.joining("; ")));
    var principals = bind(values, AccessControl.PRINCIPALS, Principal[].class, where);
    var permissions = bind(values, AccessControl.PERMISSIONS, Permission[].class, where);
    var grants = bind(values, AccessControl.GRANTS, Grant[].class, where);
    rejectDuplicates(principals.stream().map(Principal::id).toList(), "principal", where);
    rejectDuplicates(permissions.stream().map(Permission::name).toList(), "permission", where);
    return new AccessPolicy(principals, permissions, grants);
  }

  /**
   * One schema-validated policy field as a list of bound records; an absent field is an empty one.
   * A binding failure would mean the records and the schema fragment disagree about the shape —
   * refused naming the field rather than letting half a policy through.
   */
  private static <T> List<T> bind(JsonNode values, String field, Class<T[]> type, String where) {
    var node = values.get(field);
    if (node == null || node.isNull()) return List.of();
    try {
      return List.of(BINDER.treeToValue(node, type));
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new ServiceError(
          "'" + field + "'" + where + " could not be read: " + e.getMessage(), e);
    }
  }

  /**
   * Validates the policy against the resources a run covers and resolves it into the grants that
   * apply to each of them.
   *
   * <p>Everything that can be wrong with a policy is wrong <em>here</em>, before any task runs. A
   * grant naming a principal or a permission the root never declared, or a selector reaching none
   * of the aggregate's resources, is a typo whose only symptom would otherwise be access quietly
   * not being granted — the same silence a task factory registered under a misspelled entity type
   * name used to produce, refused now for the same reason. Callers run this inside the
   * plan-building step, so a refusal is recorded as {@code FAILED} on the root rather than leaving
   * the {@code AUTHORIZED} of the last run that worked.
   *
   * @param resources the resources the run covers
   * @return each resource's id mapped to the grants that apply there, the empty list included
   * @throws ServiceError if a grant names something undeclared or reaches nothing
   */
  public Map<String, List<EffectiveGrant>> resolve(Collection<Entity> resources) {
    var byId = new LinkedHashMap<String, Principal>();
    principals.forEach(p -> byId.put(p.id(), p));
    var permissionNames = permissions.stream().map(Permission::name).toList();
    for (var grant : grants) {
      for (var principalId : grant.principals())
        if (!byId.containsKey(principalId))
          throw new ServiceError(
              "Grant names principal '"
                  + principalId
                  + "', which is not declared in '"
                  + AccessControl.PRINCIPALS
                  + "' (declared: "
                  + byId.keySet()
                  + ")");
      for (var permission : grant.permissions())
        if (!permissionNames.contains(permission))
          throw new ServiceError(
              "Grant names permission '"
                  + permission
                  + "', which is not declared in '"
                  + AccessControl.PERMISSIONS
                  + "' (declared: "
                  + permissionNames
                  + ")");
      // A selector reaching nothing is only reported when there was something to reach: an
      // aggregate with no authorizable resources at all is a run with nothing to do, not a typo.
      if (!resources.isEmpty() && resources.stream().noneMatch(grant.resources()::matches))
        throw new ServiceError(
            "Grant selector ["
                + grant.resources().describe()
                + "] matches none of the "
                + resources.size()
                + " authorizable resources in the aggregate");
    }
    var resolved = new LinkedHashMap<String, List<EffectiveGrant>>();
    for (var resource : resources) {
      var permissionsByPrincipal = new LinkedHashMap<String, TreeSet<String>>();
      for (var grant : grants) {
        if (!grant.resources().matches(resource)) continue;
        for (var principalId : grant.principals())
          permissionsByPrincipal
              .computeIfAbsent(principalId, ignored -> new TreeSet<>())
              .addAll(grant.permissions());
      }
      resolved.put(
          resource.getId(),
          permissionsByPrincipal.entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .map(e -> new EffectiveGrant(byId.get(e.getKey()), List.copyOf(e.getValue())))
              .toList());
    }
    return resolved;
  }

  /**
   * Renders resolved grants as the JSON array recorded in {@code effectiveGrants}, shaped exactly
   * as {@link AccessControl#RESOURCE_PROPERTIES} declares it.
   *
   * @param effectiveGrants the grants that applied
   * @return the array to write
   */
  public static ArrayNode toJson(List<EffectiveGrant> effectiveGrants) {
    var array = JsonNodeFactory.instance.arrayNode();
    for (var effectiveGrant : effectiveGrants) {
      var node = array.addObject();
      var principal = node.putObject(AccessControl.EFFECTIVE_GRANT_PRINCIPAL);
      principal.put(AccessControl.PRINCIPAL_ID, effectiveGrant.principal().id());
      principal.put(AccessControl.PRINCIPAL_TYPE, effectiveGrant.principal().type().name());
      effectiveGrant
          .principal()
          .description()
          .ifPresent(d -> principal.put(AccessControl.DESCRIPTION, d));
      var permissions = node.putArray(AccessControl.PERMISSIONS);
      effectiveGrant.permissions().forEach(permissions::add);
    }
    return array;
  }

  private static void rejectDuplicates(List<String> names, String what, String where) {
    var seen = new TreeSet<String>();
    var duplicates = names.stream().filter(name -> !seen.add(name)).distinct().sorted().toList();
    if (!duplicates.isEmpty())
      throw new ServiceError(
          "Duplicate "
              + what
              + (duplicates.size() == 1 ? " name " : " names ")
              + duplicates
              + where);
  }
}
