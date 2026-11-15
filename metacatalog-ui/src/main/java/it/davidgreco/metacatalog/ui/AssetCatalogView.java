package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.model.Asset;
import it.davidgreco.metacatalog.openapi.model.AssetDependencyRule;
import it.davidgreco.metacatalog.openapi.model.AssetDependencyTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The asset catalog as the Assets pages show it: every asset with its lifecycle statuses and what
 * stands on either side of its dependencies, what an asset may depend on besides other assets, and
 * the dependencies the model allows.
 *
 * <p>The same value serves the list, the dependency graph and — serialized — the scripts that drive
 * both, so the three cannot disagree about what something is called or what it may be linked to.
 *
 * @param assets the assets, in the order the API lists them — the only things the list shows
 * @param targets what an asset may depend on that is not an asset: aggregate roots and plain
 *     entities. The graph draws them next to the assets, since a dependency has to end somewhere
 * @param rules the dependencies the model allows an asset type to have
 * @param types the names of the asset types, ordered by name
 * @param creatable the types a new node of the graph can be created from
 */
public record AssetCatalogView(
    List<Row> assets,
    List<Ref> targets,
    List<Rule> rules,
    List<String> types,
    List<Creatable> creatable) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The three kinds of thing a dependency can point at, as the pages name them. */
  static final String ASSET = "asset";

  static final String AGGREGATE = "aggregate";
  static final String ENTITY = "entity";

  /**
   * Something named from an asset's row or drawn on the graph: an asset, or what one depends on.
   *
   * @param id the entity id
   * @param name its display name
   * @param entityType the name of its type
   * @param kind {@code asset}, {@code aggregate} or {@code entity}
   * @param href the page showing it — an asset has its own, the other two are shown by the
   *     Administration half
   */
  public record Ref(String id, String name, String entityType, String kind, String href) {

    static Ref of(String id, String values, String entityType, String kind) {
      return new Ref(id, displayName(parse(values), id), entityType, kind, hrefOf(kind, id));
    }

    private static String hrefOf(String kind, String id) {
      return switch (kind) {
        case ASSET -> "/ui/assets/" + id;
        case AGGREGATE -> "/ui/admin/aggregates/" + id;
        default -> "/ui/admin/instances/" + id;
      };
    }
  }

  /**
   * One asset.
   *
   * @param id the id of the asset's root entity, which is also the aggregate id
   * @param name the display name: the root's {@code name} value, or the id when it has none
   * @param entityType the name of the root's type
   * @param version the asset's current version, or {@code null} when its type is not versionable
   * @param provisioningStatus the status the last provisioning run left, or {@code null} for none
   * @param authorizationStatus the status the last authorization run left, or {@code null}
   * @param dependsOn what this asset depends on: assets, aggregates and plain entities
   * @param requiredBy the assets depending on this one
   * @param mapped the entities mappings derived from the asset's members
   */
  public record Row(
      String id,
      String name,
      String entityType,
      Integer version,
      String provisioningStatus,
      String authorizationStatus,
      List<Ref> dependsOn,
      List<Ref> requiredBy,
      List<Mapped> mapped) {

    /** This asset as the other end of a dependency. */
    public Ref ref() {
      return new Ref(id, name, entityType, ASSET, "/ui/assets/" + id);
    }
  }

  /**
   * An entity a mapping derived from a member of an asset — what the asset materialises as.
   *
   * @param id the derived entity's id
   * @param name its display name: its {@code name} value, or its type when it has none, which is
   *     the usual case — a derived entity is described by its type, not named
   * @param entityType the name of its type, the mapping's target
   * @param provisioningStatus the status a provisioning run left on it, or {@code null}
   * @param authorizationStatus the status an authorization run left on it, or {@code null}
   * @param sourceName the display name of the member it was derived from
   * @param sourceEntityType the name of that member's type, the mapping's source
   * @param href the page showing the derived entity
   * @param mappingHref the mapping editor, opened on the mapping that derived it
   */
  public record Mapped(
      String id,
      String name,
      String entityType,
      String provisioningStatus,
      String authorizationStatus,
      String sourceName,
      String sourceEntityType,
      String href,
      String mappingHref) {

    static Mapped of(it.davidgreco.metacatalog.openapi.model.AssetMappedEntity mapped) {
      var values = parse(mapped.getValues());
      var named = displayName(values, null);
      return new Mapped(
          mapped.getId(),
          named == null ? mapped.getEntityType() : named,
          mapped.getEntityType(),
          text(values, "provisioningStatus"),
          text(values, "authorizationStatus"),
          displayName(parse(mapped.getSourceValues().orElse(null)), mapped.getSourceEntityType()),
          mapped.getSourceEntityType(),
          "/ui/admin/instances/" + mapped.getId(),
          "/ui/admin/mappings/new?source="
              + java.net.URLEncoder.encode(
                  mapped.getSourceEntityType(), java.nio.charset.StandardCharsets.UTF_8)
              + "&target="
              + java.net.URLEncoder.encode(
                  mapped.getEntityType(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  /**
   * A dependency the model allows an asset of one type to have.
   *
   * @param source the type of the asset that may depend
   * @param target the type of what it may depend on
   * @param targetKind what the target is: {@code asset}, {@code aggregate} or {@code entity}
   * @param sanctionedBy the trait relationships allowing it, each spelled {@code Source DEPENDS_ON
   *     Target}
   */
  public record Rule(String source, String target, String targetKind, List<String> sanctionedBy) {}

  /**
   * A type a new node of the graph can be created from: an asset type, or a type some asset may
   * depend on.
   *
   * @param type the entity type name
   * @param kind what an instance of it is: {@code asset}, {@code aggregate} or {@code entity}
   * @param schema the type's schema as JSON, from which the creation dialog builds its fields
   */
  public record Creatable(String type, String kind, String schema) {}

  /**
   * Builds the catalog from the answers the API gives about assets.
   *
   * @param assets the assets, as {@code GET /asset} lists them
   * @param targets what else an asset may depend on, as {@code GET /asset/dependency-target} lists
   * @param rules the allowed dependencies, as {@code GET /asset/dependency-rule} lists them
   * @param types the names of the asset types
   * @param schemas the schema of every entity type, by name
   */
  public static AssetCatalogView from(
      List<Asset> assets,
      List<AssetDependencyTarget> targets,
      List<AssetDependencyRule> rules,
      List<String> types,
      Map<String, String> schemas) {
    var refs = new LinkedHashMap<String, Ref>();
    var values = new LinkedHashMap<String, JsonNode>();
    for (var asset : assets) {
      values.put(asset.getId(), parse(asset.getValues()));
      refs.put(
          asset.getId(), Ref.of(asset.getId(), asset.getValues(), asset.getEntityType(), ASSET));
    }
    var targetRefs = new ArrayList<Ref>();
    for (var target : targets) {
      var ref =
          Ref.of(
              target.getId(),
              target.getValues(),
              target.getEntityType(),
              target.getKind() == AssetDependencyTarget.KindEnum.AGGREGATE ? AGGREGATE : ENTITY);
      targetRefs.add(ref);
      refs.putIfAbsent(ref.id(), ref);
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
              // A dependency on something neither listing holds — a part of another aggregate,
              // linked through the entity API — has nothing to be drawn as, and is left out.
              asset.getDependencies().stream()
                  .map(refs::get)
                  .filter(java.util.Objects::nonNull)
                  .toList(),
              requiredBy.getOrDefault(asset.getId(), List.of()),
              asset.getMapped().stream().map(Mapped::of).toList()));
    }
    var ruleViews =
        rules.stream()
            .map(
                rule ->
                    new Rule(
                        rule.getSourceEntityType(),
                        rule.getTargetEntityType(),
                        rule.getTargetKind().name().toLowerCase(java.util.Locale.ROOT),
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

    // An asset can always be created; anything else only where some asset could depend on it —
    // the board is for assets and what they depend on, not a second instance editor.
    var creatable = new LinkedHashMap<String, Creatable>();
    for (var type : types) {
      creatable.put(type, new Creatable(type, ASSET, schemas.getOrDefault(type, "{}")));
    }
    for (var rule : ruleViews) {
      creatable.putIfAbsent(
          rule.target(),
          new Creatable(
              rule.target(), rule.targetKind(), schemas.getOrDefault(rule.target(), "{}")));
    }
    return new AssetCatalogView(
        List.copyOf(rows),
        List.copyOf(targetRefs),
        ruleViews,
        List.copyOf(types),
        List.copyOf(creatable.values()));
  }

  /** The row of the asset with the given id, or {@code null} when the catalog does not hold it. */
  public Row row(String id) {
    return assets.stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
  }

  /**
   * Anything a dependency can end at — an asset or one of the other targets — by id, or {@code
   * null} when the catalog does not hold it.
   */
  public Ref node(String id) {
    var asset = row(id);
    if (asset != null) return asset.ref();
    return targets.stream().filter(target -> target.id().equals(id)).findFirst().orElse(null);
  }

  /** Whether a new node of the graph may be created from the given type. */
  public boolean canCreate(String type) {
    return creatable.stream().anyMatch(candidate -> candidate.type().equals(type));
  }

  /** Whether the model allows an asset of the first type to depend on something of the second. */
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
