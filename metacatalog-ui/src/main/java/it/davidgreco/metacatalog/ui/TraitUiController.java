package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitVersion;
import it.davidgreco.metacatalog.service.TraitService;
import it.davidgreco.metacatalog.service.VersionResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Trait CRUD, trait relationship management, and trait version management (list / new / create /
 * delete / delete-all).
 */
@Controller
@RequestMapping("/ui")
public class TraitUiController {

  private final TraitService traitService;
  private final CatalogGraphService catalogGraphService;

  public TraitUiController(TraitService traitService, CatalogGraphService catalogGraphService) {
    this.traitService = traitService;
    this.catalogGraphService = catalogGraphService;
  }

  // --- trait CRUD ----------------------------------------------------------------

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
      traitService.create(
          form.getName(),
          UiControllerHelper.optional(form.getSchema()),
          UiControllerHelper.optional(form.getFather()));
      redirectAttributes.addFlashAttribute("message", "Trait '" + form.getName() + "' created.");
      return "redirect:/ui";
    } catch (RuntimeException e) {
      model.addAttribute("error", e.getMessage());
      model.addAttribute("traits", traitService.list());
      return "trait-form";
    }
  }

  /** Deletes a trait. Fails if the trait is still referenced (child, relationship, entity type). */
  @PostMapping("/traits/delete")
  public String deleteTrait(@RequestParam String name, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> traitService.delete(name),
        "Trait '" + name + "' deleted.",
        "Trait",
        name,
        "/ui",
        redirectAttributes);
  }

  // --- trait links ----------------------------------------------------------------

  /** Renders the trait relationship creation form. */
  @GetMapping("/trait-links/new")
  public String newTraitLink(Model model) {
    if (!model.containsAttribute("traitLinkForm")) {
      model.addAttribute("traitLinkForm", new TraitLinkForm());
    }
    model.addAttribute("traits", traitService.list());
    model.addAttribute("relationTypes", CatalogGraphService.PRIMARY_RELATION_TYPES);
    model.addAttribute("traitLinks", catalogGraphService.traitLinks());
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
      var relType = RelationType.parse(form.getRelationshipType());
      traitService.link(source, relType, target);
      redirectAttributes.addFlashAttribute(
          "message",
          "Linked '" + source + "' " + relType + " '" + target + "' (inverse created too).");
      return "redirect:/ui";
    } catch (IllegalArgumentException e) {
      return renderTraitLinkError(model, "Invalid relationship type.");
    } catch (RuntimeException e) {
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
      traitService.unlink(sourceTrait, RelationType.parse(relationshipType), targetTrait);
      redirectAttributes.addFlashAttribute(
          "message",
          "Removed relationship between '" + sourceTrait + "' and '" + targetTrait + "'.");
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("error", "Invalid relationship type.");
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui";
  }

  private String renderTraitLinkError(Model model, String message) {
    model.addAttribute("error", message);
    model.addAttribute("traits", traitService.list());
    model.addAttribute("relationTypes", CatalogGraphService.PRIMARY_RELATION_TYPES);
    model.addAttribute("traitLinks", catalogGraphService.traitLinks());
    return "trait-link-form";
  }

  // --- trait versioning -----------------------------------------------------------

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
    } catch (RuntimeException e) {
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
    } catch (RuntimeException e) {
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
      traitService.createVersion(
          name,
          UiControllerHelper.optional(form.getSchema()),
          UiControllerHelper.optional(form.getFather()));
      redirectAttributes.addFlashAttribute(
          "message", "New version of trait '" + name + "' created.");
      return "redirect:/ui/traits/" + name + "/versions";
    } catch (RuntimeException e) {
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
    } catch (RuntimeException e) {
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
    return UiControllerHelper.flashAndRedirect(
        () -> traitService.deleteVersion(name, version),
        "Version " + version + " of trait '" + name + "' deleted.",
        "/ui/traits/" + name + "/versions",
        redirectAttributes);
  }

  /**
   * Deletes every historical snapshot of a trait, keeping the live row. The trait continues to
   * exist at its current version with no history behind it.
   */
  @PostMapping("/traits/{name}/versions/delete-all")
  public String deleteAllTraitVersions(
      @PathVariable String name, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> traitService.deleteAllVersions(name),
        "All versions of trait '" + name + "' deleted.",
        "/ui/traits/" + name + "/versions",
        redirectAttributes);
  }

  /** Builds the {@link VersionView} rows for a trait's version history. */
  private List<VersionView> traitVersionViews(String name) {
    var views = new ArrayList<VersionView>();
    for (var v : traitService.listVersions(name)) {
      switch (v) {
        case VersionResult.Live(Trait live) ->
            views.add(
                new VersionView(
                    live.getVersion(),
                    true,
                    live.getFather() == null ? null : live.getFather().getName(),
                    List.of(),
                    live.getSchema() == null ? null : live.getSchema().toPrettyString(),
                    null));
        case VersionResult.Snapshot(TraitVersion snap) ->
            views.add(
                new VersionView(
                    snap.getVersion(),
                    false,
                    snap.getFatherName(),
                    List.of(),
                    snap.getSchema() == null ? null : snap.getSchema().toPrettyString(),
                    UiControllerHelper.formatInstant(snap.getCreatedAt())));
      }
    }
    return views;
  }
}
