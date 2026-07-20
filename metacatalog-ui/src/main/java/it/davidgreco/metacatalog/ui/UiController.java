package it.davidgreco.metacatalog.ui;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitVersion;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.EntityRepository;
import it.davidgreco.metacatalog.repository.EntityTypeVersionRepository;
import it.davidgreco.metacatalog.repository.MappingEntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.TraitVersionRepository;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.MappingService;
import it.davidgreco.metacatalog.service.SchemaValidationError;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TraitService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-side rendered UI for creating and deleting traits and entity types, managing the
 * relationships between traits, and bulk-loading a model from a YAML document.
 *
 * <p>The pages are served by the main application (same origin, port 8080), so the controller calls
 * the domain services ({@link TraitService}, {@link EntityTypeService}, {@link BulkLoaderService})
 * directly rather than going through the REST API. Each creation form embeds a client-side JSON
 * Schema builder that assembles the schema document posted in the {@code schema} field.
 */
@Controller
@RequestMapping("/ui")
public class UiController {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UiController.class);

  /**
   * The relation types offered when creating a trait relationship, and the only ones the dashboard
   * lists. Trait relationships are stored bidirectionally — {@link TraitService#link} creates the
   * inverse automatically — so listing only these "primary" directions avoids showing each
   * relationship twice.
   */
  static final List<RelationType> PRIMARY_RELATION_TYPES =
      List.of(RelationType.DEPENDS_ON, RelationType.HAS_PART, RelationType.MAPPED_TO);

  /** Formats a snapshot's capture instant as a readable UTC timestamp. */
  static final DateTimeFormatter INSTANT_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

  private final TraitService traitService;
  private final EntityTypeService entityTypeService;
  private final BulkLoaderService bulkLoaderService;
  private final MappingService mappingService;
  private final EntityTypeVersionRepository entityTypeVersionRepository;
  private final TraitVersionRepository traitVersionRepository;
  private final EntityRepository entityRepository;
  private final EntityRelationshipRepository entityRelationshipRepository;
  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  public UiController(
      TraitService traitService,
      EntityTypeService entityTypeService,
      BulkLoaderService bulkLoaderService,
      MappingService mappingService,
      EntityTypeVersionRepository entityTypeVersionRepository,
      TraitVersionRepository traitVersionRepository,
      EntityRepository entityRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      MappingEntityRelationshipRepository mappingEntityRelationshipRepository) {
    this.traitService = traitService;
    this.entityTypeService = entityTypeService;
    this.bulkLoaderService = bulkLoaderService;
    this.mappingService = mappingService;
    this.entityTypeVersionRepository = entityTypeVersionRepository;
    this.traitVersionRepository = traitVersionRepository;
    this.entityRepository = entityRepository;
    this.entityRelationshipRepository = entityRelationshipRepository;
    this.mappingEntityRelationshipRepository = mappingEntityRelationshipRepository;
  }

  /** Dashboard listing the existing traits, entity types, trait relationships, and mappings. */
  @GetMapping({"", "/"})
  public String index(Model model) {
    model.addAttribute("traits", traitService.list());
    model.addAttribute("entityTypes", entityTypeService.list());
    model.addAttribute("traitLinks", traitLinks());
    model.addAttribute("mappings", mappings());
    return "index";
  }

  /** Renders the whole catalog as an interactive graph in a separate page. */
  @GetMapping("/graph")
  public String graph(
      @RequestParam(defaultValue = "true") boolean showInverses,
      @RequestParam(defaultValue = "false") boolean showEntities,
      Model model) {
    model.addAttribute("graphJson", graphJson(showInverses, showEntities));
    model.addAttribute("showInverses", showInverses);
    model.addAttribute("showEntities", showEntities);
    return "graph";
  }

  /**
   * Returns the catalog graph as JSON, so the page can re-fetch it when the user toggles the "show
   * inverses" or "show entities" option without a full page reload.
   */
  @GetMapping("/graph/data")
  @ResponseBody
  public GraphModel graphData(
      @RequestParam(defaultValue = "true") boolean showInverses,
      @RequestParam(defaultValue = "false") boolean showEntities) {
    return buildGraphModel(showInverses, showEntities);
  }

  /** Serializes the catalog graph to JSON for embedding in the page via {@code th:utext}. */
  private String graphJson(boolean showInverses, boolean showEntities) {
    try {
      return jsonFactory.writeValueAsString(buildGraphModel(showInverses, showEntities));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      log.error("Failed to serialize catalog graph to JSON; returning an empty graph", e);
      return "{\"nodes\":[],\"edges\":[]}";
    }
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
   */
  private GraphModel buildGraphModel(boolean showInverses, boolean showEntities) {
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
                  : type.getTraits().stream()
                      .map(it.davidgreco.metacatalog.entity.Trait::getName)
                      .toList(),
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

  /**
   * Adds entity instances to the graph. Each entity becomes a node linked to its type by an {@code
   * instance-of} edge, entity-to-entity relationships become edges labelled with their relation
   * type, and instance-level mappings become {@code mapping} edges carrying the type-level mapping
   * detail in their popup.
   *
   * <p>Entity relationships and instance mappings are persisted bidirectionally (the inverse row is
   * stored too), so when {@code showInverses} is set every stored row is emitted; when it is not,
   * only the primary direction rows ({@link #PRIMARY_RELATION_TYPES}) are kept, mirroring how the
   * type-level layer hides inverses.
   */
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

  /**
   * Adds one graph node per historical entity-type snapshot and a {@code successor-of} edge from
   * each node to its predecessor in the version chain. The live type node is the head of the chain;
   * the oldest snapshot is the tail.
   */
  private void addEntityTypeVersionNodesAndEdges(
      List<it.davidgreco.metacatalog.entity.EntityType> liveTypes,
      List<GraphModel.Node> nodes,
      List<GraphModel.Edge> edges) {
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
        liveTypes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    it.davidgreco.metacatalog.entity.EntityType::getVersionGroupId, t -> t));
    var snapById =
        snapshots.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    it.davidgreco.metacatalog.entity.EntityTypeVersion::getId, s -> s));
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

  /**
   * Adds one graph node per historical trait snapshot and a {@code successor-of} edge from each
   * node to its predecessor in the version chain. The live trait node is the head of the chain.
   */
  private void addTraitVersionNodesAndEdges(
      List<it.davidgreco.metacatalog.entity.Trait> liveTraits,
      List<GraphModel.Node> nodes,
      List<GraphModel.Edge> edges) {
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
        liveTraits.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    it.davidgreco.metacatalog.entity.Trait::getVersionGroupId, t -> t));
    var snapById =
        snapshots.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    it.davidgreco.metacatalog.entity.TraitVersion::getId, s -> s));
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

  /** Renders the bulk YAML upload form. */
  @GetMapping("/bulk")
  public String bulkForm() {
    return "bulk-form";
  }

  /**
   * Handles a bulk upload. Accepts either an uploaded YAML file or pasted YAML text and, depending
   * on {@code kind}, feeds it to {@link BulkLoaderService#bulkModelCreation} (traits, entity types,
   * relationships and mappings) or {@link BulkLoaderService#bulkAggregateCreation} (aggregates /
   * entities).
   */
  @PostMapping("/bulk")
  public String bulkUpload(
      @RequestParam(value = "file", required = false) MultipartFile file,
      @RequestParam(value = "yamlText", required = false) String yamlText,
      @RequestParam(value = "kind", required = false, defaultValue = "model") String kind,
      Model model,
      RedirectAttributes redirectAttributes) {
    try (InputStream in = resolveBulkInput(file, yamlText)) {
      if (in == null) {
        model.addAttribute("error", "Provide a YAML file or paste YAML text.");
        return "bulk-form";
      }
      if ("aggregates".equals(kind)) {
        var ids = bulkLoaderService.bulkAggregateCreation(in);
        redirectAttributes.addFlashAttribute("message", ids.size() + " aggregate(s) created.");
      } else {
        bulkLoaderService.bulkModelCreation(in);
        redirectAttributes.addFlashAttribute("message", "Bulk model uploaded successfully.");
      }
      return "redirect:/ui";
    } catch (ServiceError | RuntimeException | IOException e) {
      model.addAttribute("error", e.getMessage());
      return "bulk-form";
    }
  }

  /**
   * Returns the YAML source: the uploaded file if present, otherwise the pasted text, else null.
   */
  private static InputStream resolveBulkInput(MultipartFile file, String yamlText)
      throws IOException {
    if (file != null && !file.isEmpty()) {
      return file.getInputStream();
    }
    if (yamlText != null && !yamlText.isBlank()) {
      return new ByteArrayInputStream(yamlText.getBytes(StandardCharsets.UTF_8));
    }
    return null;
  }

  /** Renders the trait creation form. */
  @GetMapping("/traits/new")
  public String newTrait(Model model) {
    if (!model.containsAttribute("traitForm")) {
      model.addAttribute("traitForm", new TraitForm());
    }
    model.addAttribute("traits", traitService.list());
    return "trait-form";
  }

  /** Handles submission of the trait creation form. */
  @PostMapping("/traits")
  public String createTrait(
      @ModelAttribute("traitForm") TraitForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      traitService.create(form.getName(), optional(form.getSchema()), optional(form.getFather()));
      redirectAttributes.addFlashAttribute("message", "Trait '" + form.getName() + "' created.");
      return "redirect:/ui";
    } catch (ServiceError | RuntimeException e) {
      model.addAttribute("error", e.getMessage());
      model.addAttribute("traits", traitService.list());
      return "trait-form";
    }
  }

  /** Deletes a trait. Fails if the trait is still referenced (child, relationship, entity type). */
  @PostMapping("/traits/delete")
  public String deleteTrait(@RequestParam String name, RedirectAttributes redirectAttributes) {
    try {
      traitService.delete(name);
      redirectAttributes.addFlashAttribute("message", "Trait '" + name + "' deleted.");
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", deleteError("Trait", name, e));
    }
    return "redirect:/ui";
  }

  /**
   * Lists every version of a trait, oldest first, with the live (current) version last. Renders the
   * shared {@code versions} template, parameterised for a trait (no traits column).
   */
  @GetMapping("/traits/{name}/versions")
  public String listTraitVersions(
      @PathVariable String name, Model model, RedirectAttributes redirectAttributes) {
    try {
      model.addAttribute("kind", "Trait");
      model.addAttribute("resource", "traits");
      model.addAttribute("name", name);
      model.addAttribute("showTraits", false);
      model.addAttribute("versions", traitVersionViews(name));
      return "versions";
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui";
    }
  }

  /**
   * Renders the trait new-version form, pre-populated with the current live trait's base schema and
   * father so the user can edit them rather than start from scratch.
   */
  @GetMapping("/traits/{name}/versions/new")
  public String newTraitVersion(
      @PathVariable String name, Model model, RedirectAttributes redirectAttributes) {
    Trait live;
    try {
      live = traitService.read(name);
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui";
    }
    if (!model.containsAttribute("traitVersionForm")) {
      var form = new TraitVersionForm();
      form.setName(live.getName());
      form.setFather(live.getFather() == null ? null : live.getFather().getName());
      form.setSchema(live.getBaseSchema() == null ? null : live.getBaseSchema().toPrettyString());
      model.addAttribute("traitVersionForm", form);
    }
    model.addAttribute("currentVersion", live.getVersion());
    model.addAttribute("traits", traitService.list());
    return "trait-version-form";
  }

  /**
   * Handles submission of the trait new-version form.
   *
   * <p>Delegates to {@link TraitService#createVersion}, which snapshots the current live row into
   * the history table and mutates the live row in place with the new schema / father, bumping its
   * version. The name comes from the URL path, so the form's read-only {@code name} field is
   * ignored.
   */
  @PostMapping("/traits/{name}/versions")
  public String createTraitVersion(
      @PathVariable String name,
      @ModelAttribute("traitVersionForm") TraitVersionForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      traitService.createVersion(name, optional(form.getSchema()), optional(form.getFather()));
      redirectAttributes.addFlashAttribute(
          "message", "New version of trait '" + name + "' created.");
      return "redirect:/ui/traits/" + name + "/versions";
    } catch (ServiceError | RuntimeException e) {
      model.addAttribute("error", e.getMessage());
      populateTraitVersionModel(name, model);
      return "trait-version-form";
    }
  }

  /** Re-supplies the new-version form model attributes after a failed submission. */
  private void populateTraitVersionModel(String name, Model model) {
    try {
      var live = traitService.read(name);
      model.addAttribute("currentVersion", live.getVersion());
    } catch (ServiceError | RuntimeException e) {
      model.addAttribute("currentVersion", null);
    }
    model.addAttribute("traits", traitService.list());
  }

  /**
   * Deletes a single historical snapshot of a trait. The live (current) version is refused by the
   * service; to revert the live trait, create a new version. Redirects back to the versions list.
   */
  @PostMapping("/traits/{name}/versions/{version}/delete")
  public String deleteTraitVersion(
      @PathVariable String name, @PathVariable int version, RedirectAttributes redirectAttributes) {
    try {
      traitService.deleteVersion(name, version);
      redirectAttributes.addFlashAttribute(
          "message", "Version " + version + " of trait '" + name + "' deleted.");
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui/traits/" + name + "/versions";
  }

  /**
   * Deletes every historical snapshot of a trait, keeping the live row. The trait continues to
   * exist at its current version with no history behind it.
   */
  @PostMapping("/traits/{name}/versions/delete-all")
  public String deleteAllTraitVersions(
      @PathVariable String name, RedirectAttributes redirectAttributes) {
    try {
      traitService.deleteAllVersions(name);
      redirectAttributes.addFlashAttribute(
          "message", "All versions of trait '" + name + "' deleted.");
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui/traits/" + name + "/versions";
  }

  /** Renders the entity type creation form. */
  @GetMapping("/entity-types/new")
  public String newEntityType(Model model) {
    if (!model.containsAttribute("entityTypeForm")) {
      model.addAttribute("entityTypeForm", new EntityTypeForm());
    }
    model.addAttribute("entityTypes", entityTypeService.list());
    model.addAttribute("traits", traitService.list());
    return "entity-type-form";
  }

  /** Handles submission of the entity type creation form. */
  @PostMapping("/entity-types")
  public String createEntityType(
      @ModelAttribute("entityTypeForm") EntityTypeForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      entityTypeService.create(
          form.getName(),
          form.getTraits() == null ? List.of() : form.getTraits(),
          optional(form.getFather()),
          form.getSchema());
      redirectAttributes.addFlashAttribute(
          "message", "Entity type '" + form.getName() + "' created.");
      return "redirect:/ui";
    } catch (ServiceError | RuntimeException e) {
      model.addAttribute("error", e.getMessage());
      model.addAttribute("entityTypes", entityTypeService.list());
      model.addAttribute("traits", traitService.list());
      return "entity-type-form";
    }
  }

  /** Deletes an entity type. Fails if it is still referenced (child type or existing entities). */
  @PostMapping("/entity-types/delete")
  public String deleteEntityType(@RequestParam String name, RedirectAttributes redirectAttributes) {
    try {
      entityTypeService.delete(name);
      redirectAttributes.addFlashAttribute("message", "Entity type '" + name + "' deleted.");
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", deleteError("Entity type", name, e));
    }
    return "redirect:/ui";
  }

  /**
   * Lists every version of an entity type, oldest first, with the live (current) version last.
   * Renders the shared {@code versions} template, parameterised for an entity type.
   */
  @GetMapping("/entity-types/{name}/versions")
  public String listEntityTypeVersions(
      @PathVariable String name, Model model, RedirectAttributes redirectAttributes) {
    try {
      model.addAttribute("kind", "Entity Type");
      model.addAttribute("resource", "entity-types");
      model.addAttribute("name", name);
      model.addAttribute("showTraits", true);
      model.addAttribute("versions", entityTypeVersionViews(name));
      return "versions";
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui";
    }
  }

  /**
   * Renders the entity-type new-version form, pre-populated with the current live type's base
   * schema, traits, and father so the user can edit them rather than start from scratch.
   */
  @GetMapping("/entity-types/{name}/versions/new")
  public String newEntityTypeVersion(
      @PathVariable String name, Model model, RedirectAttributes redirectAttributes) {
    EntityType live;
    try {
      live = entityTypeService.read(name);
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui";
    }
    if (!model.containsAttribute("entityTypeVersionForm")) {
      var form = new EntityTypeVersionForm();
      form.setName(live.getName());
      form.setFather(live.getFather() == null ? null : live.getFather().getName());
      form.setTraits(live.getTraits().stream().map(Trait::getName).collect(Collectors.toList()));
      form.setSchema(live.getBaseSchema() == null ? null : live.getBaseSchema().toPrettyString());
      model.addAttribute("entityTypeVersionForm", form);
    }
    model.addAttribute("currentVersion", live.getVersion());
    model.addAttribute("entityTypes", entityTypeService.list());
    model.addAttribute("traits", traitService.list());
    return "entity-type-version-form";
  }

  /**
   * Handles submission of the entity-type new-version form.
   *
   * <p>Delegates to {@link EntityTypeService#createVersion}, which snapshots the current live row
   * into the history table and mutates the live row in place with the new schema / traits / father,
   * bumping its version. The name comes from the URL path, so the form's read-only {@code name}
   * field is ignored.
   */
  @PostMapping("/entity-types/{name}/versions")
  public String createEntityTypeVersion(
      @PathVariable String name,
      @ModelAttribute("entityTypeVersionForm") EntityTypeVersionForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      entityTypeService.createVersion(
          name,
          form.getTraits() == null ? List.of() : form.getTraits(),
          optional(form.getFather()),
          form.getSchema());
      redirectAttributes.addFlashAttribute(
          "message", "New version of entity type '" + name + "' created.");
      return "redirect:/ui/entity-types/" + name + "/versions";
    } catch (ServiceError | RuntimeException e) {
      model.addAttribute("error", e.getMessage());
      populateEntityTypeVersionModel(name, model);
      return "entity-type-version-form";
    }
  }

  /** Re-supplies the new-version form model attributes after a failed submission. */
  private void populateEntityTypeVersionModel(String name, Model model) {
    try {
      var live = entityTypeService.read(name);
      model.addAttribute("currentVersion", live.getVersion());
    } catch (ServiceError | RuntimeException e) {
      model.addAttribute("currentVersion", null);
    }
    model.addAttribute("entityTypes", entityTypeService.list());
    model.addAttribute("traits", traitService.list());
  }

  /**
   * Deletes a single historical snapshot of an entity type. The live (current) version is refused
   * by the service; to revert the live type, create a new version. Redirects back to the versions
   * list.
   */
  @PostMapping("/entity-types/{name}/versions/{version}/delete")
  public String deleteEntityTypeVersion(
      @PathVariable String name, @PathVariable int version, RedirectAttributes redirectAttributes) {
    try {
      entityTypeService.deleteVersion(name, version);
      redirectAttributes.addFlashAttribute(
          "message", "Version " + version + " of entity type '" + name + "' deleted.");
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui/entity-types/" + name + "/versions";
  }

  /**
   * Deletes every historical snapshot of an entity type, keeping the live row. The type continues
   * to exist at its current version with no history behind it.
   */
  @PostMapping("/entity-types/{name}/versions/delete-all")
  public String deleteAllEntityTypeVersions(
      @PathVariable String name, RedirectAttributes redirectAttributes) {
    try {
      entityTypeService.deleteAllVersions(name);
      redirectAttributes.addFlashAttribute(
          "message", "All versions of entity type '" + name + "' deleted.");
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui/entity-types/" + name + "/versions";
  }

  /** Renders the trait relationship creation form. */
  @GetMapping("/trait-links/new")
  public String newTraitLink(Model model) {
    if (!model.containsAttribute("traitLinkForm")) {
      model.addAttribute("traitLinkForm", new TraitLinkForm());
    }
    model.addAttribute("traits", traitService.list());
    model.addAttribute("relationTypes", PRIMARY_RELATION_TYPES);
    model.addAttribute("traitLinks", traitLinks());
    return "trait-link-form";
  }

  /**
   * Handles submission of the trait relationship creation form.
   *
   * <p>Delegates to {@link TraitService#link}, which enforces the constraints: both traits must
   * exist, the link must not already exist, and it must not introduce a loop. A self-referential
   * link (source equals target) is permitted. The inverse relationship is created automatically by
   * the service.
   */
  @PostMapping("/trait-links")
  public String createTraitLink(
      @ModelAttribute("traitLinkForm") TraitLinkForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      var source = form.getSourceTrait();
      var target = form.getTargetTrait();
      var relType = RelationType.valueOf(form.getRelationshipType());
      traitService.link(source, relType, target);
      redirectAttributes.addFlashAttribute(
          "message",
          "Linked '" + source + "' " + relType + " '" + target + "' (inverse created too).");
      return "redirect:/ui";
    } catch (IllegalArgumentException e) {
      return renderTraitLinkError(model, "Invalid relationship type.");
    } catch (ServiceError | RuntimeException e) {
      return renderTraitLinkError(model, e.getMessage());
    }
  }

  /**
   * Removes a trait relationship (and its inverse). Delegates to {@link TraitService#unlink}, which
   * fails if the relationship does not exist.
   */
  @PostMapping("/trait-links/delete")
  public String deleteTraitLink(
      @RequestParam String sourceTrait,
      @RequestParam String relationshipType,
      @RequestParam String targetTrait,
      RedirectAttributes redirectAttributes) {
    try {
      traitService.unlink(sourceTrait, RelationType.valueOf(relationshipType), targetTrait);
      redirectAttributes.addFlashAttribute(
          "message",
          "Removed relationship between '" + sourceTrait + "' and '" + targetTrait + "'.");
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("error", "Invalid relationship type.");
    } catch (ServiceError | RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui";
  }

  private String renderTraitLinkError(Model model, String message) {
    model.addAttribute("error", message);
    model.addAttribute("traits", traitService.list());
    model.addAttribute("relationTypes", PRIMARY_RELATION_TYPES);
    model.addAttribute("traitLinks", traitLinks());
    return "trait-link-form";
  }

  /**
   * Collects every trait relationship in its canonical (primary) direction, so each bidirectional
   * link appears exactly once.
   */
  private List<TraitLinkView> traitLinks() {
    var links = new ArrayList<TraitLinkView>();
    for (var trait : traitService.list()) {
      for (var relType : PRIMARY_RELATION_TYPES) {
        try {
          for (var target : traitService.linked(trait.getName(), relType)) {
            links.add(new TraitLinkView(trait.getName(), relType, target.getName()));
          }
        } catch (ServiceError e) {
          // Trait vanished between listing and traversal; skip it.
        }
      }
    }
    return links;
  }

  /**
   * Collects every mapping entity type relationship as a {@link MappingView}, pretty-printing the
   * JSON fields so the template can render them verbatim.
   */
  private List<MappingView> mappings() {
    var views = new ArrayList<MappingView>();
    for (var mapping : mappingService.list()) {
      views.add(
          new MappingView(
              mapping.getId(),
              mapping.getSource().getName(),
              mapping.getTarget().getName(),
              mapping.getMappingValues().toPrettyString(),
              jsonFactory.valueToTree(mapping.getEntityPathReferences()).toPrettyString()));
    }
    return views;
  }

  /** Renders the mapping creation form. */
  @GetMapping("/mappings/new")
  public String newMapping(Model model) {
    if (!model.containsAttribute("mappingForm")) {
      model.addAttribute("mappingForm", new MappingForm());
    }
    model.addAttribute("entityTypes", entityTypeService.list());
    model.addAttribute("mappings", mappings());
    return "mapping-form";
  }

  /**
   * Handles submission of the mapping creation form.
   *
   * <p>Delegates to {@link MappingService#create}, which validates the mapping values against the
   * target entity type's schema and rejects mappings that would introduce a loop. The alias /
   * reference-path rows are zipped into {@link
   * it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship.EntityPathReference}s, dropping
   * rows where either field is blank.
   */
  @PostMapping("/mappings")
  public String createMapping(
      @ModelAttribute("mappingForm") MappingForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      mappingService.create(
          form.getSourceEntityType(),
          form.getTargetEntityType(),
          form.getMappingValues(),
          entityPathReferences(form));
      redirectAttributes.addFlashAttribute(
          "message",
          "Mapping from '"
              + form.getSourceEntityType()
              + "' to '"
              + form.getTargetEntityType()
              + "' created.");
      return "redirect:/ui";
    } catch (SchemaValidationError e) {
      return renderMappingError(model, String.join("; ", e.getErrors()));
    } catch (ServiceError | RuntimeException e) {
      return renderMappingError(model, e.getMessage());
    }
  }

  private String renderMappingError(Model model, String message) {
    model.addAttribute("error", message);
    model.addAttribute("entityTypes", entityTypeService.list());
    model.addAttribute("mappings", mappings());
    return "mapping-form";
  }

  /**
   * Zips the form's parallel alias / reference-path lists into entity path references, dropping any
   * row where either the alias or the reference path is blank.
   */
  private static List<MappingEntityTypeRelationship.EntityPathReference> entityPathReferences(
      MappingForm form) {
    var aliases = form.getAliases() == null ? List.<String>of() : form.getAliases();
    var paths = form.getReferencePaths() == null ? List.<String>of() : form.getReferencePaths();
    var refs = new ArrayList<MappingEntityTypeRelationship.EntityPathReference>();
    for (int i = 0; i < Math.min(aliases.size(), paths.size()); i++) {
      var alias = aliases.get(i);
      var path = paths.get(i);
      if (alias != null && !alias.isBlank() && path != null && !path.isBlank()) {
        refs.add(new MappingEntityTypeRelationship.EntityPathReference(alias.trim(), path.trim()));
      }
    }
    return refs;
  }

  /**
   * Deletes a mapping entity type relationship by id. Fails if the mapping is still referenced by
   * mapped entities.
   */
  @PostMapping("/mappings/delete")
  public String deleteMapping(@RequestParam String id, RedirectAttributes redirectAttributes) {
    try {
      mappingService.delete(id);
      redirectAttributes.addFlashAttribute("message", "Mapping deleted.");
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", deleteError("Mapping", id, e));
    }
    return "redirect:/ui";
  }

  /** Treats blank strings as absent, so an empty father / schema field becomes {@code empty()}. */
  private static Optional<String> optional(String value) {
    return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
  }

  /**
   * Builds the {@link VersionView} rows for an entity type's version history. The live row is
   * tagged {@code live = true} and carries its trait names; snapshots carry their frozen trait
   * names extracted from the {@code traits} JSON array.
   */
  private List<VersionView> entityTypeVersionViews(String name) throws ServiceError {
    var views = new ArrayList<VersionView>();
    for (var v : entityTypeService.listVersions(name)) {
      if (v instanceof EntityType live) {
        views.add(
            new VersionView(
                live.getVersion(),
                true,
                live.getFather() == null ? null : live.getFather().getName(),
                live.getTraits().stream().map(Trait::getName).collect(Collectors.toList()),
                live.getSchema() == null ? null : live.getSchema().toPrettyString(),
                null));
      } else if (v instanceof EntityTypeVersion snap) {
        views.add(
            new VersionView(
                snap.getVersion(),
                false,
                snap.getFatherName(),
                traitNames(snap.getTraits()),
                snap.getSchema() == null ? null : snap.getSchema().toPrettyString(),
                formatInstant(snap.getCreatedAt())));
      }
    }
    return views;
  }

  /** Builds the {@link VersionView} rows for a trait's version history. */
  private List<VersionView> traitVersionViews(String name) throws ServiceError {
    var views = new ArrayList<VersionView>();
    for (var v : traitService.listVersions(name)) {
      if (v instanceof Trait live) {
        views.add(
            new VersionView(
                live.getVersion(),
                true,
                live.getFather() == null ? null : live.getFather().getName(),
                List.of(),
                live.getSchema() == null ? null : live.getSchema().toPrettyString(),
                null));
      } else if (v instanceof TraitVersion snap) {
        views.add(
            new VersionView(
                snap.getVersion(),
                false,
                snap.getFatherName(),
                List.of(),
                snap.getSchema() == null ? null : snap.getSchema().toPrettyString(),
                formatInstant(snap.getCreatedAt())));
      }
    }
    return views;
  }

  /**
   * Extracts the trait names frozen in an {@link EntityTypeVersion}'s {@code traits} JSON array.
   * Returns an empty list when the node is missing or not an array.
   */
  private static List<String> traitNames(JsonNode traitsNode) {
    if (traitsNode == null || !traitsNode.isArray()) return List.of();
    return StreamSupport.stream(traitsNode.spliterator(), false)
        .filter(JsonNode::isTextual)
        .map(JsonNode::asText)
        .collect(Collectors.toList());
  }

  /**
   * Formats a snapshot's capture instant as a readable UTC timestamp; {@code null} for the live
   * row.
   */
  private static String formatInstant(Instant instant) {
    return instant == null ? null : INSTANT_FMT.format(instant);
  }

  /**
   * Turns a delete failure into a user-friendly message: a missing target reads as "not found",
   * anything else (a foreign-key / integrity violation) reads as "still in use" rather than leaking
   * the raw database error.
   */
  private static String deleteError(String kind, String name, Exception e) {
    var message = e.getMessage() == null ? "" : e.getMessage();
    if (message.contains("not found")) {
      return kind + " '" + name + "' was not found.";
    }
    return "Could not delete "
        + kind.toLowerCase(java.util.Locale.ROOT)
        + " '"
        + name
        + "': it is still in use (referenced by another type, a relationship, or an entity).";
  }
}
