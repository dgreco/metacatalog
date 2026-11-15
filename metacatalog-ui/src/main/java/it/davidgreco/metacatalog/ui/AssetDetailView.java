package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.model.Aggregate;
import it.davidgreco.metacatalog.openapi.model.AggregateAccess;
import it.davidgreco.metacatalog.openapi.model.Mapping;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One asset as its own page shows it: the root with its statuses, the whole tree below it, the
 * dependencies on either side, the mappings that derive resources from its members and the access
 * it grants.
 *
 * @param asset the asset's catalog row — name, type, version, statuses, dependencies
 * @param provisioningResult the message the last provisioning run left on the root, or {@code null}
 * @param authorizationResult the message the last authorization run left, or {@code null}
 * @param root the aggregate tree
 * @param candidates what this asset may still be made to depend on — assets, aggregates and plain
 *     entities of a type the model allows, not already depended on, not itself
 * @param mappings the mappings whose source is the type of some member of the asset
 * @param access the access the asset declares and holds, or {@code null} when it could not be read
 * @param actions the lifecycle actions offered, in declaration order
 */
public record AssetDetailView(
    AssetCatalogView.Row asset,
    String provisioningResult,
    String authorizationResult,
    Node root,
    List<AssetCatalogView.Ref> candidates,
    List<MappingRow> mappings,
    Access access,
    List<AggregateAction> actions) {

  /**
   * A member of the asset: the root, a part, or an entity a mapping derived from one of them.
   *
   * @param id the entity id
   * @param name the display name
   * @param entityType the name of its type
   * @param values its values, as pretty-printed JSON
   * @param provisioningStatus the status a provisioning run left on it, or {@code null}
   * @param authorizationStatus the status an authorization run left on it, or {@code null}
   * @param dependsOn the names of the entities it depends on
   * @param parts the parts it contains
   * @param derived the entities derived from it by mappings — the resources the procedures act on
   */
  public record Node(
      String id,
      String name,
      String entityType,
      String values,
      String provisioningStatus,
      String authorizationStatus,
      List<String> dependsOn,
      List<Node> parts,
      List<Node> derived) {

    /** The number of entities below this one, parts and derived entities alike. */
    public int descendants() {
      var count = 0;
      for (var part : parts) count += 1 + part.descendants();
      for (var entity : derived) count += 1 + entity.descendants();
      return count;
    }
  }

  /**
   * A mapping that applies to the asset.
   *
   * @param source the source entity type, the type of some member of the asset
   * @param target the entity type the mapping derives
   * @param pathReferences the mapping's entity path references, as JSON
   * @param walksDependencies whether a path reference traverses a dependency — in which case what
   *     the mapping derives changes with the assets or parts this asset is made to depend on
   */
  public record MappingRow(
      String source, String target, String pathReferences, boolean walksDependencies) {}

  /**
   * The access an asset declares and the grants its resources hold.
   *
   * @param grants the declared policy, one line per grant
   * @param resources the resources, with what the last authorization run applied to each
   */
  public record Access(List<String> grants, List<ResourceGrants> resources) {}

  /**
   * What one resource is holding.
   *
   * @param name the resource's display name
   * @param entityType the name of its type
   * @param status its authorization status, or {@code null}
   * @param grants the grants in force, one line per principal
   */
  public record ResourceGrants(
      String name, String entityType, String status, List<String> grants) {}

  /**
   * Builds the tree from the two readings of one aggregate. The API returns derived entities among
   * a node's parts when asked to include them, with nothing marking them; reading the aggregate
   * once without them is what tells the two apart.
   *
   * @param withDerived the aggregate with the mapped instances included
   * @param authored the aggregate without them
   * @param names display names of entities outside the tree, by id — the other assets
   */
  static Node tree(Aggregate withDerived, Aggregate authored, Map<String, String> names) {
    var authoredIds = new LinkedHashSet<String>();
    collectIds(authored, authoredIds);
    var allNames = new LinkedHashMap<>(names);
    collectNames(withDerived, allNames);
    return node(withDerived, authoredIds, allNames);
  }

  private static Node node(
      Aggregate aggregate, Set<String> authoredIds, Map<String, String> names) {
    var id = aggregate.getId().orElse(null);
    var values = AssetCatalogView.parse(aggregate.getValues());
    var parts = new ArrayList<Node>();
    var derived = new ArrayList<Node>();
    for (var child : aggregate.getParts()) {
      var built = node(child, authoredIds, names);
      if (child.getId().map(authoredIds::contains).orElse(false)) parts.add(built);
      else derived.add(built);
    }
    return new Node(
        id,
        AssetCatalogView.displayName(values, id),
        aggregate.getEntityType(),
        values == null ? aggregate.getValues() : values.toPrettyString(),
        AssetCatalogView.text(values, "provisioningStatus"),
        AssetCatalogView.text(values, "authorizationStatus"),
        aggregate.getDependencies().stream()
            .map(dependency -> names.getOrDefault(dependency, dependency))
            .toList(),
        parts,
        derived);
  }

  private static void collectIds(Aggregate aggregate, Set<String> into) {
    aggregate.getId().ifPresent(into::add);
    aggregate.getParts().forEach(part -> collectIds(part, into));
  }

  private static void collectNames(Aggregate aggregate, Map<String, String> into) {
    aggregate
        .getId()
        .ifPresent(
            id ->
                into.put(
                    id,
                    AssetCatalogView.displayName(
                        AssetCatalogView.parse(aggregate.getValues()), id)));
    aggregate.getParts().forEach(part -> collectNames(part, into));
  }

  /** The names of the entity types of every authored member of the tree, root included. */
  static Set<String> memberTypes(Node node) {
    var types = new LinkedHashSet<String>();
    collectTypes(node, types);
    return types;
  }

  private static void collectTypes(Node node, Set<String> into) {
    into.add(node.entityType());
    node.parts().forEach(part -> collectTypes(part, into));
  }

  /**
   * The mappings whose source is the type of a member of the asset.
   *
   * @param mappings every mapping of the catalog
   * @param memberTypes the types of the asset's members
   */
  static List<MappingRow> mappingsOf(List<Mapping> mappings, Set<String> memberTypes) {
    return mappings.stream()
        .filter(mapping -> memberTypes.contains(mapping.getSourceEntityType()))
        .map(
            mapping -> {
              var references = mapping.getEntityPathReferences();
              return new MappingRow(
                  mapping.getSourceEntityType(),
                  mapping.getTargetEntityType(),
                  references,
                  references != null
                      && (references.contains("DEPENDS_ON")
                          || references.contains("IS_REQUIRED_BY")));
            })
        .toList();
  }

  /**
   * Summarises the access answer for display.
   *
   * @param access what {@code GET /aggregate/{id}/access} returned
   * @param names display names of the asset's members, by id
   */
  static Access access(AggregateAccess access, Map<String, String> names) {
    var grants =
        access.getGrants().stream()
            .map(
                grant ->
                    String.join(", ", grant.getPrincipals())
                        + " → "
                        + String.join(", ", grant.getPermissions())
                        + (grant.getEntityTypes().isEmpty()
                            ? ""
                            : " on " + String.join(", ", grant.getEntityTypes())))
            .toList();
    var resources =
        access.getResources().stream()
            .map(
                resource ->
                    new ResourceGrants(
                        names.getOrDefault(resource.getEntityId(), resource.getEntityId()),
                        resource.getEntityTypeName(),
                        resource.getAuthorizationStatus().orElse(null),
                        resource.getGrants().stream()
                            .map(
                                grant ->
                                    grant.getPrincipal().getId()
                                        + " ("
                                        + grant.getPrincipal().getType()
                                        + ") → "
                                        + String.join(", ", grant.getPermissions()))
                            .toList()))
            .toList();
    return new Access(grants, resources);
  }

  /** Display names of every member of the tree, by id. */
  static Map<String, String> names(Node node) {
    var names = new LinkedHashMap<String, String>();
    collectNodeNames(node, names);
    return names;
  }

  private static void collectNodeNames(Node node, Map<String, String> into) {
    if (node.id() != null) into.put(node.id(), node.name());
    node.parts().forEach(part -> collectNodeNames(part, into));
    node.derived().forEach(entity -> collectNodeNames(entity, into));
  }
}
