package it.davidgreco.metacatalog.ui;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitVersion;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.EntityRepository;
import it.davidgreco.metacatalog.repository.EntityTypeVersionRepository;
import it.davidgreco.metacatalog.repository.MappingEntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.TraitVersionRepository;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.MappingService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TraitService;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the interactive catalog graph consumed by the UI. Owns the repositories needed to project
 * traits, entity types, their version chains, and (optionally) entity instances into a {@link
 * GraphModel}. Extracted from {@code UiController} so the controller keeps only request/response
 * wiring while this service concentrates the graph-assembly responsibility.
 */
@Service
public class CatalogGraphService {

  private static final Logger log = LoggerFactory.getLogger(CatalogGraphService.class);

  /**
   * The relation types shown in the canonical (primary) direction. Trait relationships are stored
   * bidirectionally, so listing only these directions avoids showing each relationship twice.
   */
  public static final List<RelationType> PRIMARY_RELATION_TYPES =
      List.of(RelationType.DEPENDS_ON, RelationType.HAS_PART, RelationType.MAPPED_TO);

  private final TraitService traitService;
  private final EntityTypeService entityTypeService;
  private final MappingService mappingService;
  private final EntityTypeVersionRepository entityTypeVersionRepository;
  private final TraitVersionRepository traitVersionRepository;
  private final EntityRepository entityRepository;
  private final EntityRelationshipRepository entityRelationshipRepository;
  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;
  private final HtmlSafeJsonSerializer jsonSerializer;

  public CatalogGraphService(
      TraitService traitService,
      EntityTypeService entityTypeService,
      MappingService mappingService,
      EntityTypeVersionRepository entityTypeVersionRepository,
      TraitVersionRepository traitVersionRepository,
      EntityRepository entityRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      MappingEntityRelationshipRepository mappingEntityRelationshipRepository,
      HtmlSafeJsonSerializer jsonSerializer) {
    this.traitService = traitService;
    this.entityTypeService = entityTypeService;
    this.mappingService = mappingService;
    this.entityTypeVersionRepository = entityTypeVersionRepository;
    this.traitVersionRepository = traitVersionRepository;
    this.entityRepository = entityRepository;
    this.entityRelationshipRepository = entityRelationshipRepository;
    this.mappingEntityRelationshipRepository = mappingEntityRelationshipRepository;
    this.jsonSerializer = jsonSerializer;
  }

  /**
   * Collects every trait relationship in its canonical (primary) direction, so each bidirectional
   * link appears exactly once.
   *
   * @return the primary-direction trait relationships as view models
   */
  public List<TraitLinkView> traitLinks() {
    var links = new ArrayList<TraitLinkView>();
    for (var trait : traitService.list()) {
      for (var relType : PRIMARY_RELATION_TYPES) {
        try {
          for (var target : traitService.linked(trait.getName(), relType)) {
            links.add(new TraitLinkView(trait.getName(), relType, target.getName()));
          }
        } catch (ServiceError e) {
          // Trait vanished between listing and traversal; skip it.
          log.debug(
              "Skipping trait '{}' during link traversal: {}", trait.getName(), e.getMessage());
        }
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
   * mapping, swapping source/target and labelling it with the inverse relation type ({@link
   * RelationType#inverse()}). Trait relationships are persisted bidirectionally, but the graph only
   * traverses the primary direction ({@link #traitLinks()}); the inverse edge is synthesised here
   * so the graph stays a single source of truth. Mapping type relationships are stored in the
   * {@code MAPPED_TO} direction only, so the {@code IS_MAPPED_BY} inverse is synthesised the same
   * way. Entity relationships and instance mappings are stored bidirectionally, so when inverses
   * are shown the stored inverse rows are emitted directly (no synthesis needed); when hidden, only
   * the primary direction rows are kept.
   *
   * @param showInverses whether to emit synthesised/stored inverse edges
   * @param showEntities whether to include entity instances
   * @return the assembled graph model
   */
  public GraphModel buildGraphModel(boolean showInverses, boolean showEntities) {
    var nodes = new ArrayList<GraphModel.Node>();
    var edges = new ArrayList<GraphModel.Edge>();

    var traits = traitService.list();
    var types = entityTypeService.list();

    for (var trait : traits) {
      nodes.add(
          new GraphModel.Node(
              "trait:" + trait.getName(),
              trait.getName(),
              "trait",
              trait.getFather() == null ? null : trait.getFather().getName(),
              null,
              trait.getSchema() == null ? null : trait.getSchema().toPrettyString()));
    }
    for (var type : types) {
      nodes.add(
          new GraphModel.Node(
              "type:" + type.getName(),
              type.getName(),
              "entityType",
              type.getFather() == null ? null : type.getFather().getName(),
              type.getTraits() == null
                  ? List.of()
                  : type.getTraits().stream().map(Trait::getName).toList(),
              type.getSchema() == null ? null : type.getSchema().toPrettyString()));
    }

    for (var trait : traits) {
      if (trait.getFather() != null) {
        edges.add(
            new GraphModel.Edge(
                "trait:" + trait.getName(),
                "trait:" + trait.getFather().getName(),
                "extends",
                "extends",
                null,
                null));
      }
    }
    for (var type : types) {
      if (type.getFather() != null) {
        edges.add(
            new GraphModel.Edge(
                "type:" + type.getName(),
                "type:" + type.getFather().getName(),
                "extends",
                "extends",
                null,
                null));
      }
      if (type.getTraits() != null) {
        for (var trait : type.getTraits()) {
          edges.add(
              new GraphModel.Edge(
                  "type:" + type.getName(),
                  "trait:" + trait.getName(),
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
              "trait:" + link.source(),
              "trait:" + link.target(),
              link.relationType().name(),
              link.relationType().name(),
              null,
              null));
      if (showInverses && link.relationType().hasInverse()) {
        var inv = link.relationType().inverse();
        edges.add(
            new GraphModel.Edge(
                "trait:" + link.target(),
                "trait:" + link.source(),
                inv.name(),
                inv.name(),
                null,
                null));
      }
    }
    for (var mapping : mappingService.list()) {
      var mv = mapping.getMappingValues().toPrettyString();
      var epr = jsonFactory.valueToTree(mapping.getEntityPathReferences()).toPrettyString();
      edges.add(
          new GraphModel.Edge(
              "type:" + mapping.getSource().getName(),
              "type:" + mapping.getTarget().getName(),
              "mapping",
              "MAPPED_TO",
              mv,
              epr));
      if (showInverses) {
        edges.add(
            new GraphModel.Edge(
                "type:" + mapping.getTarget().getName(),
                "type:" + mapping.getSource().getName(),
                "mapping",
                "IS_MAPPED_BY",
                mv,
                epr));
      }
    }

    addEntityTypeVersionNodesAndEdges(types, nodes, edges);
    addTraitVersionNodesAndEdges(traits, nodes, edges);

    if (showEntities) {
      addEntityInstanceNodesAndEdges(nodes, edges, showInverses);
    }

    return new GraphModel(nodes, edges);
  }

  private void addEntityInstanceNodesAndEdges(
      List<GraphModel.Node> nodes, List<GraphModel.Edge> edges, boolean showInverses) {
    for (var entity : entityRepository.findAll()) {
      var type = entity.getEntityType();
      if (type == null) continue;
      var typeName = type.getName();
      var pinnedVersion = entity.getEntityTypeVersion();
      var instanceOfTarget =
          pinnedVersion != null ? "type-version:" + pinnedVersion.getId() : "type:" + typeName;
      var instanceOfLabel =
          pinnedVersion != null ? typeName + " (v" + pinnedVersion.getVersion() + ")" : typeName;
      nodes.add(
          new GraphModel.Node(
              "entity:" + entity.getId(),
              entityLabel(entity),
              "entity",
              instanceOfLabel,
              null,
              entity.getValues() == null ? null : entity.getValues().toPrettyString()));
      edges.add(
          new GraphModel.Edge(
              "entity:" + entity.getId(),
              instanceOfTarget,
              "instance-of",
              "instance-of",
              null,
              null));
    }

    for (var rel : entityRelationshipRepository.findAll()) {
      var rt = rel.getRelationType();
      if (!showInverses && !PRIMARY_RELATION_TYPES.contains(rt)) continue;
      edges.add(
          new GraphModel.Edge(
              "entity:" + rel.getSource().getId(),
              "entity:" + rel.getTarget().getId(),
              rt.name(),
              rt.name(),
              null,
              null));
    }

    for (var rel : mappingEntityRelationshipRepository.findAll()) {
      var rt = rel.getRelationType();
      if (!showInverses && !PRIMARY_RELATION_TYPES.contains(rt)) continue;
      var mtr = rel.getMappingEntityTypeRelationship();
      var mv = mtr == null ? null : mtr.getMappingValues().toPrettyString();
      var epr =
          mtr == null
              ? null
              : jsonFactory.valueToTree(mtr.getEntityPathReferences()).toPrettyString();
      edges.add(
          new GraphModel.Edge(
              "entity:" + rel.getSource().getId(),
              "entity:" + rel.getTarget().getId(),
              "mapping",
              rt.name(),
              mv,
              epr));
    }
  }

  /**
   * Picks a readable label for an entity instance: the textual {@code name} field of its {@code
   * values} JSON if present, otherwise a short hash of its id so every node is distinguishable.
   */
  private static String entityLabel(Entity entity) {
    var values = entity.getValues();
    if (values != null && values.isObject()) {
      var name = values.get("name");
      if (name != null && name.isTextual()) return name.asText();
    }
    var id = entity.getId();
    return "#" + (id == null ? "?" : id.substring(0, Math.min(8, id.length())));
  }

  private void addEntityTypeVersionNodesAndEdges(
      List<EntityType> liveTypes, List<GraphModel.Node> nodes, List<GraphModel.Edge> edges) {
    var snapshots = entityTypeVersionRepository.findAll();
    for (var snap : snapshots) {
      nodes.add(
          new GraphModel.Node(
              "type-version:" + snap.getId(),
              snap.getName() + " (v" + snap.getVersion() + ")",
              "entityTypeVersion",
              snap.getFatherName(),
              null,
              snap.getSchema() == null ? null : snap.getSchema().toPrettyString()));
    }
    var liveByGroup =
        liveTypes.stream().collect(Collectors.toMap(EntityType::getVersionGroupId, t -> t));
    var snapById = snapshots.stream().collect(Collectors.toMap(EntityTypeVersion::getId, s -> s));
    for (var snap : snapshots) {
      var successorId = "type-version:" + snap.getId();
      if (snap.getPreviousVersionId() != null) {
        var pred = snapById.get(snap.getPreviousVersionId());
        if (pred != null) {
          edges.add(
              new GraphModel.Edge(
                  successorId,
                  "type-version:" + pred.getId(),
                  "successor-of",
                  "successor-of",
                  null,
                  null));
        }
      }
    }
    for (var snap : snapshots) {
      var isHead = snapshots.stream().noneMatch(s -> snap.getId().equals(s.getPreviousVersionId()));
      if (isHead) {
        var live = liveByGroup.get(snap.getVersionGroupId());
        if (live != null) {
          edges.add(
              new GraphModel.Edge(
                  "type:" + live.getName(),
                  "type-version:" + snap.getId(),
                  "successor-of",
                  "successor-of",
                  null,
                  null));
        }
      }
    }
  }

  private void addTraitVersionNodesAndEdges(
      List<Trait> liveTraits, List<GraphModel.Node> nodes, List<GraphModel.Edge> edges) {
    var snapshots = traitVersionRepository.findAll();
    for (var snap : snapshots) {
      nodes.add(
          new GraphModel.Node(
              "trait-version:" + snap.getId(),
              snap.getName() + " (v" + snap.getVersion() + ")",
              "traitVersion",
              snap.getFatherName(),
              null,
              snap.getSchema() == null ? null : snap.getSchema().toPrettyString()));
    }
    var liveByGroup =
        liveTraits.stream().collect(Collectors.toMap(Trait::getVersionGroupId, t -> t));
    var snapById = snapshots.stream().collect(Collectors.toMap(TraitVersion::getId, s -> s));
    for (var snap : snapshots) {
      var successorId = "trait-version:" + snap.getId();
      if (snap.getPreviousVersionId() != null) {
        var pred = snapById.get(snap.getPreviousVersionId());
        if (pred != null) {
          edges.add(
              new GraphModel.Edge(
                  successorId,
                  "trait-version:" + pred.getId(),
                  "successor-of",
                  "successor-of",
                  null,
                  null));
        }
      }
    }
    for (var snap : snapshots) {
      var isHead = snapshots.stream().noneMatch(s -> snap.getId().equals(s.getPreviousVersionId()));
      if (isHead) {
        var live = liveByGroup.get(snap.getVersionGroupId());
        if (live != null) {
          edges.add(
              new GraphModel.Edge(
                  "trait:" + live.getName(),
                  "trait-version:" + snap.getId(),
                  "successor-of",
                  "successor-of",
                  null,
                  null));
        }
      }
    }
  }
}
