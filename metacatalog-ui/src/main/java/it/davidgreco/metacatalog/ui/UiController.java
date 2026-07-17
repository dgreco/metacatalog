package it.davidgreco.metacatalog.ui;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;

import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

  /**
   * The relation types offered when creating a trait relationship, and the only ones the dashboard
   * lists. Trait relationships are stored bidirectionally — {@link TraitService#link} creates the
   * inverse automatically — so listing only these "primary" directions avoids showing each
   * relationship twice.
   */
  static final List<RelationType> PRIMARY_RELATION_TYPES =
      List.of(RelationType.DEPENDS_ON, RelationType.HAS_PART, RelationType.MAPPED_TO);

  private final TraitService traitService;
  private final EntityTypeService entityTypeService;
  private final BulkLoaderService bulkLoaderService;
  private final MappingService mappingService;

  public UiController(
      TraitService traitService,
      EntityTypeService entityTypeService,
      BulkLoaderService bulkLoaderService,
      MappingService mappingService) {
    this.traitService = traitService;
    this.entityTypeService = entityTypeService;
    this.bulkLoaderService = bulkLoaderService;
    this.mappingService = mappingService;
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
  public String graph(Model model) {
    model.addAttribute("graphJson", graphJson());
    return "graph";
  }

  /**
   * Assembles the catalog graph and serializes it to JSON. Nodes are traits and entity types; edges
   * capture inheritance, trait membership, trait relationships, and entity-type mappings.
   */
  private String graphJson() {
    var nodes = new ArrayList<GraphModel.Node>();
    var edges = new ArrayList<GraphModel.Edge>();

    var traits = traitService.list();
    var types = entityTypeService.list();

    for (var trait : traits) {
      nodes.add(new GraphModel.Node("trait:" + trait.getName(), trait.getName(), "trait"));
    }
    for (var type : types) {
      nodes.add(new GraphModel.Node("type:" + type.getName(), type.getName(), "entityType"));
    }

    for (var trait : traits) {
      if (trait.getFather() != null) {
        edges.add(
            new GraphModel.Edge(
                "trait:" + trait.getName(),
                "trait:" + trait.getFather().getName(),
                "extends",
                "extends"));
      }
    }
    for (var type : types) {
      if (type.getFather() != null) {
        edges.add(
            new GraphModel.Edge(
                "type:" + type.getName(),
                "type:" + type.getFather().getName(),
                "extends",
                "extends"));
      }
      if (type.getTraits() != null) {
        for (var trait : type.getTraits()) {
          edges.add(
              new GraphModel.Edge(
                  "type:" + type.getName(), "trait:" + trait.getName(), "has-trait", "trait"));
        }
      }
    }
    for (var link : traitLinks()) {
      edges.add(
          new GraphModel.Edge(
              "trait:" + link.source(),
              "trait:" + link.target(),
              link.relationType().name(),
              link.relationType().name()));
    }
    for (var mapping : mappingService.list()) {
      edges.add(
          new GraphModel.Edge(
              "type:" + mapping.getSource().getName(),
              "type:" + mapping.getTarget().getName(),
              "mapping",
              "MAPPED_TO"));
    }

    try {
      return jsonFactory.writeValueAsString(new GraphModel(nodes, edges));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return "{\"nodes\":[],\"edges\":[]}";
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
