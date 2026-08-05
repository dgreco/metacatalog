package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Entity;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import it.davidgreco.metacatalog.openapi.model.Trait;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Builds the interactive catalog graph consumed by the UI, projecting traits, entity types, their
 * version chains, and (optionally) entity instances into a {@link GraphModel}.
 *
 * <p>Every read goes through the REST API delegate, like the rest of the UI, so the page exercises
 * the same contract external clients do.
 */
@Service
public class CatalogGraphService {

  /**
   * The relation types shown in the canonical (primary) direction. Trait relationships are stored
   * bidirectionally, so listing only these directions avoids showing each relationship twice.
   */
  public static final List<String> PRIMARY_RELATION_TYPE_NAMES =
      List.of("DEPENDS_ON", "HAS_PART", "MAPPED_TO");

  private static final String TRAIT_PREFIX = "trait:";
  private static final String TYPE_PREFIX = "type:";
  private static final String TRAIT_VERSION_PREFIX = "trait-version:";
  private static final String TYPE_VERSION_PREFIX = "type-version:";
  private static final String SUCCESSOR_OF = "successor-of";
  private static final String EXTENDS = "extends";
  private static final String MAPPING = "mapping";

  private final MetacatalogApiDelegate api;
  private final HtmlSafeJsonSerializer jsonSerializer;
  private final ObjectMapper jsonMapper;

  public CatalogGraphService(
      MetacatalogApiDelegate api, HtmlSafeJsonSerializer jsonSerializer, ObjectMapper jsonMapper) {
    this.api = api;
    this.jsonSerializer = jsonSerializer;
    this.jsonMapper = jsonMapper;
  }

  /**
   * A trait or entity-type row reduced to the fields the graph needs. {@link Trait} and {@link
   * EntityType} are unrelated generated classes, so normalising them here lets the version-chaining
   * logic be written once.
   *
   * @param id the row id — the snapshot id for a version, the live row's id otherwise
   * @param name the type/trait name
   * @param version the version number
   * @param father the parent type/trait name, or null
   * @param schema the schema as a string, or null
   * @param versionGroupId groups a live row with every snapshot of the same logical type
   * @param previousVersionId the snapshot this one succeeds, or null
   */
  private record VersionRow(
      String id,
      String name,
      Integer version,
      String father,
      String schema,
      String versionGroupId,
      String previousVersionId) {

    static VersionRow of(Trait t) {
      return new VersionRow(
          t.getId().orElse(null),
          t.getName(),
          t.getVersion().orElse(null),
          t.getInheritsFrom().orElse(null),
          t.getSchema().orElse(null),
          t.getVersionGroupId().orElse(null),
          t.getPreviousVersionId().orElse(null));
    }

    static VersionRow of(EntityType e) {
      return new VersionRow(
          e.getId().orElse(null),
          e.getName(),
          e.getVersion().orElse(null),
          e.getInheritsFrom().orElse(null),
          e.getSchema(),
          e.getVersionGroupId().orElse(null),
          e.getPreviousVersionId().orElse(null));
    }
  }

  /**
   * Collects every trait relationship in its canonical (primary) direction, so each bidirectional
   * link appears exactly once.
   *
   * @return the primary-direction trait relationships as view models
   */
  public List<TraitLinkView> traitLinks() {
    var links = new ArrayList<TraitLinkView>();
    for (var rel : api.listTraitRelationships().getBody()) {
      var rt = rel.getRelationType().orElse(null);
      if (rt != null && PRIMARY_RELATION_TYPE_NAMES.contains(rt)) {
        links.add(
            new TraitLinkView(
                rel.getSourceTrait().orElse(null), rt, rel.getTargetTrait().orElse(null)));
      }
    }
    return links;
  }

  /**
   * Serializes the catalog graph to JSON for embedding in the page via {@code th:utext}.
   *
   * <p>Delegates to {@link HtmlSafeJsonSerializer}, which escapes {@code <}, {@code >} and {@code
   * &} as Unicode escape sequences so the JSON is safe to embed inside an HTML {@code <script>}
   * block. This prevents a stored-XSS attack where an entity value containing {@code </script><img
   * src=x onerror=...>} breaks out of the {@code <script type="application/json">} block in {@code
   * graph.html}.
   */
  public String graphJson(boolean showInverses, boolean showEntities) {
    return jsonSerializer.write(
        buildGraphModel(showInverses, showEntities), "{\"nodes\":[],\"edges\":[]}");
  }

  /**
   * Assembles the catalog graph. Nodes are traits and entity types; edges capture inheritance,
   * trait membership, trait relationships, and entity-type mappings. When {@code showEntities} is
   * set, entity instances are added as nodes with {@code instance-of} edges to their type, plus
   * entity-to-entity relationships and instance-level mappings.
   *
   * <p>When {@code showInverses} is set, a second edge is emitted for each trait relationship and
   * mapping, swapping source/target and labelling it with the inverse relation type. Trait
   * relationships are persisted bidirectionally, but the graph only traverses the primary direction
   * ({@link #traitLinks()}); the inverse edge is synthesised here so the graph stays a single
   * source of truth. Mapping type relationships are stored in the {@code MAPPED_TO} direction only,
   * so the {@code IS_MAPPED_BY} inverse is synthesised the same way. Entity relationships and
   * instance mappings are stored bidirectionally, so when inverses are shown the stored inverse
   * rows are emitted directly (no synthesis needed); when hidden, only the primary direction rows
   * are kept.
   *
   * @param showInverses whether to emit synthesised/stored inverse edges
   * @param showEntities whether to include entity instances
   * @return the assembled graph model
   */
  public GraphModel buildGraphModel(boolean showInverses, boolean showEntities) {
    var nodes = new ArrayList<GraphModel.Node>();
    var edges = new ArrayList<GraphModel.Edge>();

    var traits = api.listTraits().getBody();
    var types = api.listEntityTypes().getBody();

    for (var trait : traits) {
      nodes.add(
          new GraphModel.Node(
              TRAIT_PREFIX + trait.getName(),
              trait.getName(),
              "trait",
              trait.getInheritsFrom().orElse(null),
              null,
              trait.getSchema().orElse(null)));
    }
    for (var type : types) {
      nodes.add(
          new GraphModel.Node(
              TYPE_PREFIX + type.getName(),
              type.getName(),
              "entityType",
              type.getInheritsFrom().orElse(null),
              type.getTraits() == null ? List.of() : type.getTraits(),
              type.getSchema()));
    }

    for (var trait : traits) {
      trait
          .getInheritsFrom()
          .ifPresent(
              father ->
                  edges.add(
                      new GraphModel.Edge(
                          TRAIT_PREFIX + trait.getName(),
                          TRAIT_PREFIX + father,
                          EXTENDS,
                          EXTENDS,
                          null,
                          null)));
    }
    for (var type : types) {
      type.getInheritsFrom()
          .ifPresent(
              father ->
                  edges.add(
                      new GraphModel.Edge(
                          TYPE_PREFIX + type.getName(),
                          TYPE_PREFIX + father,
                          EXTENDS,
                          EXTENDS,
                          null,
                          null)));
      if (type.getTraits() != null) {
        for (var trait : type.getTraits()) {
          edges.add(
              new GraphModel.Edge(
                  TYPE_PREFIX + type.getName(),
                  TRAIT_PREFIX + trait,
                  "has-trait",
                  "trait",
                  null,
                  null));
        }
      }
    }
    for (var link : traitLinks()) {
      edges.add(
          new GraphModel.Edge(
              TRAIT_PREFIX + link.source(),
              TRAIT_PREFIX + link.target(),
              link.relationType(),
              link.relationType(),
              null,
              null));
      if (showInverses) {
        var inv = inverseOf(link.relationType());
        edges.add(
            new GraphModel.Edge(
                TRAIT_PREFIX + link.target(), TRAIT_PREFIX + link.source(), inv, inv, null, null));
      }
    }
    for (var mapping : api.listMappings().getBody()) {
      var mv = mapping.getMappingValues();
      var epr = mapping.getEntityPathReferences();
      edges.add(
          new GraphModel.Edge(
              TYPE_PREFIX + mapping.getSourceEntityType(),
              TYPE_PREFIX + mapping.getTargetEntityType(),
              MAPPING,
              "MAPPED_TO",
              mv,
              epr));
      if (showInverses) {
        edges.add(
            new GraphModel.Edge(
                TYPE_PREFIX + mapping.getTargetEntityType(),
                TYPE_PREFIX + mapping.getSourceEntityType(),
                MAPPING,
                "IS_MAPPED_BY",
                mv,
                epr));
      }
    }

    addVersionNodesAndEdges(
        types.stream().map(VersionRow::of).toList(),
        api.listAllEntityTypeVersions().getBody().stream().map(VersionRow::of).toList(),
        TYPE_VERSION_PREFIX,
        TYPE_PREFIX,
        "entityTypeVersion",
        nodes,
        edges);
    addVersionNodesAndEdges(
        traits.stream().map(VersionRow::of).toList(),
        api.listAllTraitVersions().getBody().stream().map(VersionRow::of).toList(),
        TRAIT_VERSION_PREFIX,
        TRAIT_PREFIX,
        "traitVersion",
        nodes,
        edges);

    if (showEntities) {
      addEntityInstanceNodesAndEdges(nodes, edges, showInverses);
    }

    return new GraphModel(nodes, edges);
  }

  /**
   * The inverse of a relation type name. Mirrors {@code RelationType.inverse()}; kept here as a
   * name-level mapping so the UI stays on the REST contract rather than the core enum.
   */
  private static String inverseOf(String relationType) {
    return switch (relationType) {
      case "DEPENDS_ON" -> "IS_REQUIRED_BY";
      case "IS_REQUIRED_BY" -> "DEPENDS_ON";
      case "HAS_PART" -> "IS_PART_OF";
      case "IS_PART_OF" -> "HAS_PART";
      case "MAPPED_TO" -> "IS_MAPPED_BY";
      case "IS_MAPPED_BY" -> "MAPPED_TO";
      default -> relationType;
    };
  }

  private void addEntityInstanceNodesAndEdges(
      List<GraphModel.Node> nodes, List<GraphModel.Edge> edges, boolean showInverses) {
    var versionsById =
        api.listAllEntityTypeVersions().getBody().stream()
            .filter(v -> v.getId().isPresent())
            .collect(Collectors.toMap(v -> v.getId().orElseThrow(), Function.identity()));

    for (var entity : api.getEntities(Optional.empty(), Optional.empty()).getBody()) {
      var typeName = entity.getEntityType();
      if (typeName == null) continue;
      var pinnedVersion =
          Optional.ofNullable(entity.getEntityTypeVersionId())
              .map(versionsById::get)
              .filter(Objects::nonNull);
      var instanceOfTarget =
          pinnedVersion
              .flatMap(EntityType::getId)
              .map(id -> TYPE_VERSION_PREFIX + id)
              .orElse(TYPE_PREFIX + typeName);
      var instanceOfLabel =
          pinnedVersion
              .flatMap(EntityType::getVersion)
              .map(v -> typeName + " (v" + v + ")")
              .orElse(typeName);
      var entityNodeId = "entity:" + entity.getId().orElse("");
      nodes.add(
          new GraphModel.Node(
              entityNodeId,
              entityLabel(entity),
              "entity",
              instanceOfLabel,
              null,
              entity.getValues()));
      edges.add(
          new GraphModel.Edge(
              entityNodeId, instanceOfTarget, "instance-of", "instance-of", null, null));
    }

    for (var rel : api.listEntityRelationships().getBody()) {
      var rt = rel.getRelationType().orElse(null);
      if (rt == null) continue;
      if (!showInverses && !PRIMARY_RELATION_TYPE_NAMES.contains(rt)) continue;
      edges.add(
          new GraphModel.Edge(
              "entity:" + rel.getSourceEntityId().orElse(""),
              "entity:" + rel.getTargetEntityId().orElse(""),
              rt,
              rt,
              null,
              null));
    }

    for (var rel : api.listMappingEntityRelationships().getBody()) {
      var rt = rel.getRelationType().orElse(null);
      if (rt == null) continue;
      if (!showInverses && !PRIMARY_RELATION_TYPE_NAMES.contains(rt)) continue;
      edges.add(
          new GraphModel.Edge(
              "entity:" + rel.getSourceEntityId().orElse(""),
              "entity:" + rel.getTargetEntityId().orElse(""),
              MAPPING,
              rt,
              rel.getMappingValues().orElse(null),
              rel.getEntityPathReferences().orElse(null)));
    }
  }

  /**
   * Picks a readable label for an entity instance: the textual {@code name} field of its {@code
   * values} JSON if present, otherwise a short hash of its id so every node is distinguishable.
   */
  private String entityLabel(Entity entity) {
    var values = entity.getValues();
    if (values != null && !values.isBlank()) {
      try {
        var parsed = jsonMapper.readTree(values);
        var name = parsed.get("name");
        if (name != null && name.isTextual()) return name.asText();
      } catch (com.fasterxml.jackson.core.JsonProcessingException _) {
        // fall through to the id-derived label
      }
    }
    var id = entity.getId().orElse(null);
    return "#" + (id == null ? "?" : id.substring(0, Math.min(8, id.length())));
  }

  /**
   * Adds version-snapshot nodes and {@code successor-of} edges to the graph, chaining each snapshot
   * to the one it succeeds and each live row to the head of its chain.
   */
  private void addVersionNodesAndEdges(
      List<VersionRow> live,
      List<VersionRow> snapshots,
      String snapPrefix,
      String livePrefix,
      String nodeType,
      List<GraphModel.Node> nodes,
      List<GraphModel.Edge> edges) {
    var snapshotIds =
        snapshots.stream().map(VersionRow::id).filter(Objects::nonNull).collect(Collectors.toSet());

    for (var snap : snapshots) {
      if (snap.id() == null) continue;
      nodes.add(
          new GraphModel.Node(
              snapPrefix + snap.id(),
              snap.name() + " (v" + snap.version() + ")",
              nodeType,
              snap.father(),
              null,
              snap.schema()));
    }

    for (var snap : snapshots) {
      if (snap.id() == null
          || snap.previousVersionId() == null
          || !snapshotIds.contains(snap.previousVersionId())) continue;
      edges.add(
          new GraphModel.Edge(
              snapPrefix + snap.id(),
              snapPrefix + snap.previousVersionId(),
              SUCCESSOR_OF,
              SUCCESSOR_OF,
              null,
              null));
    }

    var liveByGroup =
        live.stream()
            .filter(l -> l.versionGroupId() != null)
            .collect(
                Collectors.toMap(VersionRow::versionGroupId, Function.identity(), (a, _) -> a));
    // A snapshot no other snapshot succeeds is the head of its chain; the live row follows it.
    var succeeded =
        snapshots.stream()
            .map(VersionRow::previousVersionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    for (var snap : snapshots) {
      if (snap.id() == null || succeeded.contains(snap.id())) continue;
      var liveRow = snap.versionGroupId() == null ? null : liveByGroup.get(snap.versionGroupId());
      if (liveRow == null) continue;
      edges.add(
          new GraphModel.Edge(
              livePrefix + liveRow.name(),
              snapPrefix + snap.id(),
              SUCCESSOR_OF,
              SUCCESSOR_OF,
              null,
              null));
    }
  }
}
