package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Entity;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import it.davidgreco.metacatalog.openapi.model.EntityVersion;
import it.davidgreco.metacatalog.openapi.model.RecordedAggregate;
import it.davidgreco.metacatalog.openapi.model.Trait;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
  private static final String ENTITY_PREFIX = "entity:";
  private static final String ENTITY_VERSION_PREFIX = "entity-version:";
  private static final String ENTITY_VERSION_PART_PREFIX = "entity-version-part:";
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
   * A trait, entity-type or entity row reduced to the fields the graph needs. {@link Trait}, {@link
   * EntityType}, {@link Entity} and {@link EntityVersion} are unrelated generated classes, so
   * normalising them here lets the version-chaining logic be written once.
   *
   * @param id the row id — the snapshot id for a version, the live row's id otherwise
   * @param name the type/trait name, or the entity's display label
   * @param version the version number
   * @param father the parent type/trait name, or for an entity version the label of the type
   *     version its values were validated against; null if none
   * @param schema the schema as a string, or for an entity version its values; null if none
   * @param versionGroupId groups a live row with every snapshot of the same logical type; for an
   *     entity, the entity id
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

    static VersionRow of(EntityVersion v, String entityLabel, String typeVersionLabel) {
      return new VersionRow(
          v.getId(),
          entityLabel,
          v.getVersion(),
          typeVersionLabel,
          v.getValues(),
          v.getEntityId(),
          v.getPreviousVersionId().orElse(null));
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

    var currentTypeSnapshots =
        addVersionNodesAndEdges(
            types.stream().map(VersionRow::of).toList(),
            api.listAllEntityTypeVersions().getBody().stream().map(VersionRow::of).toList(),
            TYPE_VERSION_PREFIX,
            liveRow -> TYPE_PREFIX + liveRow.name(),
            "entityTypeVersion",
            nodes,
            edges);
    addVersionNodesAndEdges(
        traits.stream().map(VersionRow::of).toList(),
        api.listAllTraitVersions().getBody().stream().map(VersionRow::of).toList(),
        TRAIT_VERSION_PREFIX,
        liveRow -> TRAIT_PREFIX + liveRow.name(),
        "traitVersion",
        nodes,
        edges);

    if (showEntities) {
      addEntityInstanceNodesAndEdges(nodes, edges, showInverses, currentTypeSnapshots);
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
      List<GraphModel.Node> nodes,
      List<GraphModel.Edge> edges,
      boolean showInverses,
      Map<String, String> currentTypeSnapshots) {
    var versionsById =
        api.listAllEntityTypeVersions().getBody().stream()
            .filter(v -> v.getId().isPresent())
            .collect(Collectors.toMap(v -> v.getId().orElseThrow(), Function.identity()));

    var liveEntities = new ArrayList<VersionRow>();
    var liveEntityIds = new HashSet<String>();
    for (var entity : api.getEntities(Optional.empty(), Optional.empty()).getBody()) {
      var typeName = entity.getEntityType();
      if (typeName == null) continue;
      var entityId = entity.getId().orElse("");
      liveEntityIds.add(entityId);
      var label = entityLabel(entity);
      if (entity.getVersion().isPresent())
        liveEntities.add(
            new VersionRow(entityId, label, entity.getVersion().get(), null, null, entityId, null));
      var pinnedVersion =
          Optional.ofNullable(entity.getEntityTypeVersionId())
              .map(versionsById::get)
              .filter(Objects::nonNull);
      // Pinned to the type's current version, the entity is an instance of the live type node,
      // which stands in for that version's snapshot.
      var instanceOfTarget =
          pinnedVersion
              .flatMap(EntityType::getId)
              .map(id -> currentTypeSnapshots.getOrDefault(id, TYPE_VERSION_PREFIX + id))
              .orElse(TYPE_PREFIX + typeName);
      var instanceOfLabel =
          pinnedVersion
              .flatMap(EntityType::getVersion)
              .map(v -> typeName + " (v" + v + ")")
              .orElse(typeName);
      var entityNodeId = ENTITY_PREFIX + entityId;
      nodes.add(
          new GraphModel.Node(
              entityNodeId, label, "entity", instanceOfLabel, null, entity.getValues()));
      edges.add(
          new GraphModel.Edge(
              entityNodeId, instanceOfTarget, "instance-of", "instance-of", null, null));
    }

    // Versionable entities get the same treatment as types: one node per past version, chained by
    // successor-of, behind the live entity.
    var recordedVersions = api.listAllEntityVersions().getBody();
    var entityVersions =
        recordedVersions.stream()
            .map(
                v ->
                    VersionRow.of(
                        v,
                        entityLabel(v.getValues(), v.getEntityId()),
                        Optional.ofNullable(versionsById.get(v.getEntityTypeVersionId()))
                            .map(t -> t.getName() + " (v" + t.getVersion().orElse(null) + ")")
                            .orElse(null)))
            .toList();
    var currentEntitySnapshots =
        addVersionNodesAndEdges(
            liveEntities,
            entityVersions,
            ENTITY_VERSION_PREFIX,
            liveRow -> ENTITY_PREFIX + liveRow.id(),
            "entityVersion",
            nodes,
            edges);
    // The current version is the live root, whose live aggregate is already drawn; only past
    // versions bring a recorded copy of theirs.
    for (var version : recordedVersions)
      if (!currentEntitySnapshots.containsKey(version.getId()))
        version
            .getAggregate()
            .ifPresent(
                tree ->
                    addRecordedAggregate(version, tree, liveEntityIds, showInverses, nodes, edges));

    for (var rel : api.listEntityRelationships().getBody()) {
      var rt = rel.getRelationType().orElse(null);
      if (rt == null) continue;
      if (!showInverses && !PRIMARY_RELATION_TYPE_NAMES.contains(rt)) continue;
      edges.add(
          new GraphModel.Edge(
              ENTITY_PREFIX + rel.getSourceEntityId().orElse(""),
              ENTITY_PREFIX + rel.getTargetEntityId().orElse(""),
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
              ENTITY_PREFIX + rel.getSourceEntityId().orElse(""),
              ENTITY_PREFIX + rel.getTargetEntityId().orElse(""),
              MAPPING,
              rt,
              rel.getMappingValues().orElse(null),
              rel.getEntityPathReferences().orElse(null)));
    }
  }

  /**
   * Draws the aggregate a root version recorded as frozen copies of its members, so a past version
   * shows the aggregate as it was rather than as it is now: each copy carries the values the member
   * had, hangs off the version (or off its copied parent) by {@code HAS_PART} — or by {@code
   * MAPPED_TO} for an entity derived from it — and depends by {@code DEPENDS_ON} on the copies it
   * depended on then. A dependency outside the aggregate points at that entity's live node, the
   * only one there is.
   */
  private void addRecordedAggregate(
      EntityVersion version,
      RecordedAggregate tree,
      Set<String> liveEntityIds,
      boolean showInverses,
      List<GraphModel.Node> nodes,
      List<GraphModel.Edge> edges) {
    var copies = new java.util.LinkedHashMap<String, RecordedAggregate>();
    addRecordedParts(
        ENTITY_VERSION_PREFIX + version.getId(), version, tree, copies, showInverses, nodes, edges);
    var copyIdsByPartId = new java.util.HashMap<String, String>();
    copies.forEach((copyId, part) -> copyIdsByPartId.put(part.getId().orElse(""), copyId));
    copies.forEach(
        (copyId, part) -> {
          for (var dependencyId : part.getDependencies()) {
            var target =
                copyIdsByPartId.containsKey(dependencyId)
                    ? copyIdsByPartId.get(dependencyId)
                    : liveEntityIds.contains(dependencyId) ? ENTITY_PREFIX + dependencyId : null;
            if (target != null) addRelationEdges(copyId, "DEPENDS_ON", target, showInverses, edges);
          }
        });
  }

  private void addRecordedParts(
      String parentNodeId,
      EntityVersion version,
      RecordedAggregate parent,
      Map<String, RecordedAggregate> copies,
      boolean showInverses,
      List<GraphModel.Node> nodes,
      List<GraphModel.Edge> edges) {
    for (var part : parent.getParts()) {
      var copyId = addRecordedCopy(version, part, copies, nodes);
      if (copyId == null) continue;
      addRelationEdges(parentNodeId, "HAS_PART", copyId, showInverses, edges);
      addRecordedParts(copyId, version, part, copies, showInverses, nodes, edges);
    }
    for (var derived : parent.getMapped()) {
      var copyId = addRecordedCopy(version, derived, copies, nodes);
      if (copyId == null) continue;
      // Drawn like the live graph draws a mapping instance, so the two read the same.
      edges.add(new GraphModel.Edge(parentNodeId, copyId, MAPPING, "MAPPED_TO", null, null));
      if (showInverses)
        edges.add(new GraphModel.Edge(copyId, parentNodeId, MAPPING, "IS_MAPPED_BY", null, null));
      addRecordedParts(copyId, version, derived, copies, showInverses, nodes, edges);
    }
  }

  /** Adds the frozen copy of one member, or returns null if it was already drawn. */
  private String addRecordedCopy(
      EntityVersion version,
      RecordedAggregate member,
      Map<String, RecordedAggregate> copies,
      List<GraphModel.Node> nodes) {
    var memberId = member.getId().orElse("");
    var copyId = ENTITY_VERSION_PART_PREFIX + version.getId() + ":" + memberId;
    if (copies.containsKey(copyId)) return null; // a member reached twice is drawn once
    copies.put(copyId, member);
    nodes.add(
        new GraphModel.Node(
            copyId,
            entityLabel(member.getValues(), memberId) + " @v" + version.getVersion(),
            "entityVersion",
            member.getEntityType(),
            null,
            member.getValues()));
    return copyId;
  }

  /** A relationship edge, plus its inverse when inverses are shown, as stored rows would give. */
  private static void addRelationEdges(
      String source,
      String relationType,
      String target,
      boolean showInverses,
      List<GraphModel.Edge> edges) {
    edges.add(new GraphModel.Edge(source, target, relationType, relationType, null, null));
    if (showInverses) {
      var inverse = inverseOf(relationType);
      edges.add(new GraphModel.Edge(target, source, inverse, inverse, null, null));
    }
  }

  /**
   * Picks a readable label for an entity instance: the textual {@code name} field of its {@code
   * values} JSON if present, otherwise a short hash of its id so every node is distinguishable.
   */
  private String entityLabel(Entity entity) {
    return entityLabel(entity.getValues(), entity.getId().orElse(null));
  }

  /**
   * The same label, from a given set of values. An entity version is labelled from its own values,
   * so a past version shows the name it had then rather than the entity's current one.
   */
  private String entityLabel(String values, String id) {
    if (values != null && !values.isBlank()) {
      try {
        var parsed = jsonMapper.readTree(values);
        var name = parsed.get("name");
        if (name != null && name.isTextual()) return name.asText();
      } catch (com.fasterxml.jackson.core.JsonProcessingException _) {
        // fall through to the id-derived label
      }
    }
    return "#" + (id == null ? "?" : id.substring(0, Math.min(8, id.length())));
  }

  /**
   * Adds the version history of traits, entity types or entities to the graph: one node per past
   * version, chained to the one it succeeds by {@code successor-of}, behind the live node.
   *
   * <p>Entity types and entities keep a snapshot of their <em>current</em> version too, so that
   * there is a row to pin to. That snapshot gets no node of its own: it is the live object, and
   * drawing both would show the same state twice. The live node stands in for it, and once there is
   * history behind it, it carries its version number to tell it apart from the older ones.
   *
   * @return the ids of the snapshots folded into a live node, mapped to that node's id, so that
   *     edges aimed at one of them can be aimed at the live node instead
   */
  private Map<String, String> addVersionNodesAndEdges(
      List<VersionRow> live,
      List<VersionRow> snapshots,
      String snapPrefix,
      Function<VersionRow, String> liveNodeId,
      String nodeType,
      List<GraphModel.Node> nodes,
      List<GraphModel.Edge> edges) {
    var liveByGroup =
        live.stream()
            .filter(l -> l.versionGroupId() != null)
            .collect(
                Collectors.toMap(VersionRow::versionGroupId, Function.identity(), (a, _) -> a));
    var current = new java.util.HashMap<String, String>();
    for (var snap : snapshots) {
      var liveRow = snap.versionGroupId() == null ? null : liveByGroup.get(snap.versionGroupId());
      if (snap.id() != null && liveRow != null && Objects.equals(liveRow.version(), snap.version()))
        current.put(snap.id(), liveNodeId.apply(liveRow));
    }
    Function<String, String> nodeIdOf = id -> current.getOrDefault(id, snapPrefix + id);
    var snapshotIds =
        snapshots.stream().map(VersionRow::id).filter(Objects::nonNull).collect(Collectors.toSet());

    var groupsWithHistory = new HashSet<String>();
    for (var snap : snapshots) {
      if (snap.id() == null || current.containsKey(snap.id())) continue;
      if (snap.versionGroupId() != null) groupsWithHistory.add(snap.versionGroupId());
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
              nodeIdOf.apply(snap.id()),
              nodeIdOf.apply(snap.previousVersionId()),
              SUCCESSOR_OF,
              SUCCESSOR_OF,
              null,
              null));
    }

    // A chain whose newest snapshot is a past version — a trait keeps none of its current one —
    // still needs the live node in front of it.
    var succeeded =
        snapshots.stream()
            .map(VersionRow::previousVersionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    for (var snap : snapshots) {
      if (snap.id() == null || succeeded.contains(snap.id()) || current.containsKey(snap.id()))
        continue;
      var liveRow = snap.versionGroupId() == null ? null : liveByGroup.get(snap.versionGroupId());
      if (liveRow == null) continue;
      edges.add(
          new GraphModel.Edge(
              liveNodeId.apply(liveRow),
              snapPrefix + snap.id(),
              SUCCESSOR_OF,
              SUCCESSOR_OF,
              null,
              null));
    }

    for (var liveRow : liveByGroup.values())
      if (groupsWithHistory.contains(liveRow.versionGroupId()) && liveRow.version() != null)
        relabel(nodes, liveNodeId.apply(liveRow), " (v" + liveRow.version() + ")");
    return current;
  }

  /** Appends {@code suffix} to the label of the node with the given id, if it is on the graph. */
  private static void relabel(List<GraphModel.Node> nodes, String nodeId, String suffix) {
    for (var i = 0; i < nodes.size(); i++) {
      var node = nodes.get(i);
      if (!node.id().equals(nodeId)) continue;
      nodes.set(
          i,
          new GraphModel.Node(
              node.id(),
              node.label() + suffix,
              node.kind(),
              node.father(),
              node.traits(),
              node.schema()));
      return;
    }
  }
}
