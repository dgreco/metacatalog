package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TraitService;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-side rendered UI for creating traits and entity types.
 *
 * <p>The pages are served by the main application (same origin, port 8080), so the controller calls
 * the domain services ({@link TraitService}, {@link EntityTypeService}) directly rather than going
 * through the REST API. Each creation form embeds a client-side JSON Schema builder that assembles
 * the schema document posted in the {@code schema} field.
 */
@Controller
@RequestMapping("/ui")
public class UiController {

  private final TraitService traitService;
  private final EntityTypeService entityTypeService;

  public UiController(TraitService traitService, EntityTypeService entityTypeService) {
    this.traitService = traitService;
    this.entityTypeService = entityTypeService;
  }

  /** Dashboard listing the existing traits and entity types with links to the creation forms. */
  @GetMapping({"", "/"})
  public String index(Model model) {
    model.addAttribute("traits", traitService.list());
    model.addAttribute("entityTypes", entityTypeService.list());
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
          form.getTraits() == null ? java.util.List.of() : form.getTraits(),
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

  /** Treats blank strings as absent, so an empty father / schema field becomes {@code empty()}. */
  private static Optional<String> optional(String value) {
    return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
  }
}
