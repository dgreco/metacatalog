package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.model.Asset;
import it.davidgreco.metacatalog.openapi.model.AssetDependencyRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The asset catalog as the Assets pages show it: every asset with its lifecycle statuses and the
 * assets on either side of its dependencies, and the dependencies the model allows.
 *
 * <p>The same value serves the list, the dependency graph and — serialized — the scripts that drive
 * both, so the three cannot disagree about what an asset is called or what it may be linked to.
 *
 * @param assets the assets, in the order the API lists them
 * @param rules the dependencies the model allows between asset types
 * @param types the names of the asset types, ordered by name
 */
public record AssetCatalogView(List<Row> assets, List<Rule> rules, List<String> types) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * An asset named from another asset's row: the other end of a dependency.
   *
   * @param id the asset id
   * @param name its display name
   * @param entityType the name of its type
   */
  public record Ref(String id, String name, String entityType) {}

  /**
   * One asset.
   *
   * @param id the id of the asset's root entity, which is also the aggregate id
   * @param name the display name: the root's {@code name} value, or the id when it has none
   * @param entityType the name of the root's type
   * @param version the asset's current version, or {@code null} when its type is not versionable
   * @param provisioningStatus the status the last provisioning run left, or {@code null} for none
   * @param authorizationStatus the status the last authorization run left, or {@code null}
   * @param dependsOn the assets this one depends on
   * @param requiredBy the assets depending on this one
   */
  public record Row(
      String id,
      String name,
      String entityType,
      Integer version,
      String provisioningStatus,
      String authorizationStatus,
      List<Ref> dependsOn,
      List<Ref> requiredBy) {

    /** This asset as the other end of a dependency. */
    public Ref ref() {
      return new Ref(id, name, entityType);
    }
  }

  /**
   * A dependency the model allows between assets of two types.
   *
   * @param source the type of the asset that may depend
   * @param target the type of the asset it may depend on
   * @param sanctionedBy the trait relationships allowing it, each spelled {@code Source DEPENDS_ON
   *     Target}
   */
  public record Rule(String source, String target, List<String> sanctionedBy) {}

  /**
   * Builds the catalog from the three answers the API gives about assets.
   *
   * @param assets the assets, as {@code GET /asset} lists them
   * @param rules the allowed dependencies, as {@code GET /asset/dependency-rule} lists them
   * @param types the names of the asset types
   */
  public static AssetCatalogView from(
      List<Asset> assets, List<AssetDependencyRule> rules, List<String> types) {
    var refs = new LinkedHashMap<String, Ref>();
    var values = new LinkedHashMap<String, JsonNode>();
    for (var asset : assets) {
      var parsed = parse(asset.getValues());
      values.put(asset.getId(), parsed);
      refs.put(
          asset.getId(),
          new Ref(asset.getId(), displayName(parsed, asset.getId()), asset.getEntityType()));
    }
    Map<String, List<Ref>> requiredBy = new LinkedHashMap<>();
    for (var asset : assets) {
      for (var dependency : asset.getDependencies()) {
        requiredBy
            .computeIfAbsent(dependency, id -> new ArrayList<>())
            .add(refs.get(asset.getId()));
      }
    }
    var rows = new ArrayList<Row>();
    for (var asset : assets) {
      var ref = refs.get(asset.getId());
      var parsed = values.get(asset.getId());
      rows.add(
          new Row(
              ref.id(),
              ref.name(),
              ref.entityType(),
              asset.getVersion().orElse(null),
              text(parsed, "provisioningStatus"),
              text(parsed, "authorizationStatus"),
              // A dependency on an asset the listing does not hold cannot occur — both come from
              // one read — but a null would break the page, so it is filtered rather than trusted.
              asset.getDependencies().stream()
                  .map(refs::get)
                  .filter(java.util.Objects::nonNull)
                  .toList(),
              requiredBy.getOrDefault(asset.getId(), List.of())));
    }
    var ruleViews =
        rules.stream()
            .map(
                rule ->
                    new Rule(
                        rule.getSourceEntityType(),
                        rule.getTargetEntityType(),
                        rule.getSanctionedBy().stream()
                            .map(
                                relationship ->
                                    relationship.getSourceTrait().orElse("?")
                                        + " "
                                        + relationship.getRelationType().orElse("DEPENDS_ON")
                                        + " "
                                        + relationship.getTargetTrait().orElse("?"))
                            .toList()))
            .toList();
    return new AssetCatalogView(List.copyOf(rows), ruleViews, List.copyOf(types));
  }

  /** The row of the asset with the given id, or {@code null} when the catalog does not hold it. */
  public Row row(String id) {
    return assets.stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
  }

  /** Whether the model allows an asset of the first type to depend on one of the second. */
  public boolean allows(String sourceType, String targetType) {
    return rules.stream()
        .anyMatch(rule -> rule.source().equals(sourceType) && rule.target().equals(targetType));
  }

  /**
   * The name an entity is shown under: its {@code name} value when it has a textual one, its id
   * otherwise. The catalog has no naming convention of its own — a type that wants readable assets
   * carries a trait declaring {@code name}.
   */
  static String displayName(JsonNode values, String id) {
    var name = values == null ? null : values.get("name");
    return name != null && name.isTextual() && !name.asText().isBlank() ? name.asText() : id;
  }

  static JsonNode parse(String values) {
    if (values == null) return null;
    try {
      return MAPPER.readTree(values);
    } catch (Exception e) {
      // Values the API serialized are always JSON; a row is still listed if they somehow are not.
      return null;
    }
  }

  static String text(JsonNode values, String field) {
    var value = values == null ? null : values.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }
}
