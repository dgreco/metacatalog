package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TraitService;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-side rendered UI for creating traits, entity types, and the relationships between traits.
 *
 * <p>The pages are served by the main application (same origin, port 8080), so the controller calls
 * the domain services ({@link TraitService}, {@link EntityTypeService}) directly rather than going
 * through the REST API. Each creation form embeds a client-side JSON Schema builder that assembles
 * the schema document posted in the {@code schema} field.
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

  public UiController(TraitService traitService, EntityTypeService entityTypeService) {
    this.traitService = traitService;
    this.entityTypeService = entityTypeService;
  }

  /** Dashboard listing the existing traits, entity types, and trait relationships. */
  @GetMapping({"", "/"})
  public String index(Model model) {
    model.addAttribute("traits", traitService.list());
    model.addAttribute("entityTypes", entityTypeService.list());
    model.addAttribute("traitLinks", traitLinks());
    return "index";
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

  /** Treats blank strings as absent, so an empty father / schema field becomes {@code empty()}. */
  private static Optional<String> optional(String value) {
    return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
  }
}
