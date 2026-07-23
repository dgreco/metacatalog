package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.LinkTraitRequest;
import it.davidgreco.metacatalog.openapi.model.Trait;
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
 * delete / delete-all) via the REST API.
 */
@Controller
@RequestMapping("/ui")
public class TraitUiController {

  private final MetacatalogApiDelegate api;
  private final CatalogGraphService catalogGraphService;

  public TraitUiController(MetacatalogApiDelegate api, CatalogGraphService catalogGraphService) {
    this.api = api;
    this.catalogGraphService = catalogGraphService;
  }

  // --- trait CRUD ----------------------------------------------------------------

  @GetMapping("/traits/new")
  public String newTrait(Model model) {
    if (!model.containsAttribute("traitForm")) {
      model.addAttribute("traitForm", new TraitForm());
    }
    model.addAttribute("traits", api.listTraits().getBody());
    return "trait-form";
  }

  @PostMapping("/traits")
  public String createTrait(
      @ModelAttribute("traitForm") TraitForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      var dto = new Trait();
      dto.setName(form.getName());
      if (form.getSchema() != null && !form.getSchema().isBlank()) dto.schema(form.getSchema());
      if (form.getFather() != null && !form.getFather().isBlank())
        dto.inheritsFrom(form.getFather());
      api.createTrait(dto);
      redirectAttributes.addFlashAttribute("message", "Trait '" + form.getName() + "' created.");
      return "redirect:/ui";
    } catch (RuntimeException e) {
      model.addAttribute("error", e.getMessage());
      model.addAttribute("traits", api.listTraits().getBody());
      return "trait-form";
    }
  }

  @PostMapping("/traits/delete")
  public String deleteTrait(@RequestParam String name, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> api.deleteTrait(name),
        "Trait '" + name + "' deleted.",
        "Trait",
        name,
        "/ui",
        redirectAttributes);
  }

  // --- trait links ----------------------------------------------------------------

  @GetMapping("/trait-links/new")
  public String newTraitLink(Model model) {
    if (!model.containsAttribute("traitLinkForm")) {
      model.addAttribute("traitLinkForm", new TraitLinkForm());
    }
    model.addAttribute("traits", api.listTraits().getBody());
    model.addAttribute("relationTypes", CatalogGraphService.PRIMARY_RELATION_TYPES);
    model.addAttribute(
        "traitLinks", TraitLinkView.listFrom(api.listTraitRelationships().getBody()));
    return "trait-link-form";
  }

  @PostMapping("/trait-links")
  public String createTraitLink(
      @ModelAttribute("traitLinkForm") TraitLinkForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      var source = form.getSourceTrait();
      var target = form.getTargetTrait();
      var relType = RelationType.parse(form.getRelationshipType());
      var req = new LinkTraitRequest();
      req.setSourceTrait(source);
      req.setRelationshipTypeName(relType.name());
      req.setTargetTrait(target);
      api.linkTrait(req);
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

  @PostMapping("/trait-links/delete")
  public String deleteTraitLink(
      @RequestParam String sourceTrait,
      @RequestParam String relationshipType,
      @RequestParam String targetTrait,
      RedirectAttributes redirectAttributes) {
    try {
      api.unlinkTrait(sourceTrait, relationshipType, targetTrait);
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
    model.addAttribute("traits", api.listTraits().getBody());
    model.addAttribute("relationTypes", CatalogGraphService.PRIMARY_RELATION_TYPES);
    model.addAttribute(
        "traitLinks", TraitLinkView.listFrom(api.listTraitRelationships().getBody()));
    return "trait-link-form";
  }

  // --- trait versioning -----------------------------------------------------------

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

  @GetMapping("/traits/{name}/versions/new")
  public String newTraitVersion(
      @PathVariable String name, Model model, RedirectAttributes redirectAttributes) {
    Trait live;
    try {
      live = api.getTrait(name).getBody();
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui";
    }
    if (!model.containsAttribute("traitVersionForm")) {
      var form = new TraitVersionForm();
      form.setName(live.getName());
      form.setFather(live.getInheritsFrom().orElse(null));
      form.setSchema(live.getSchema().orElse(null));
      model.addAttribute("traitVersionForm", form);
    }
    model.addAttribute("currentVersion", live.getVersion().orElse(null));
    model.addAttribute("traits", api.listTraits().getBody());
    return "trait-version-form";
  }

  @PostMapping("/traits/{name}/versions")
  public String createTraitVersion(
      @PathVariable String name,
      @ModelAttribute("traitVersionForm") TraitVersionForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      var dto = new Trait();
      if (form.getSchema() != null && !form.getSchema().isBlank()) dto.schema(form.getSchema());
      if (form.getFather() != null && !form.getFather().isBlank())
        dto.inheritsFrom(form.getFather());
      api.createTraitVersion(name, dto);
      redirectAttributes.addFlashAttribute(
          "message", "New version of trait '" + name + "' created.");
      return "redirect:/ui/traits/" + name + "/versions";
    } catch (RuntimeException e) {
      model.addAttribute("error", e.getMessage());
      populateTraitVersionModel(name, model);
      return "trait-version-form";
    }
  }

  private void populateTraitVersionModel(String name, Model model) {
    try {
      var live = api.getTrait(name).getBody();
      model.addAttribute("currentVersion", live.getVersion().orElse(null));
    } catch (RuntimeException e) {
      model.addAttribute("currentVersion", null);
    }
    model.addAttribute("traits", api.listTraits().getBody());
  }

  @PostMapping("/traits/{name}/versions/{version}/delete")
  public String deleteTraitVersion(
      @PathVariable String name, @PathVariable int version, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> api.deleteTraitVersion(name, version),
        "Version " + version + " of trait '" + name + "' deleted.",
        "/ui/traits/" + name + "/versions",
        redirectAttributes);
  }

  @PostMapping("/traits/{name}/versions/delete-all")
  public String deleteAllTraitVersions(
      @PathVariable String name, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> {
          for (var v : api.listTraitVersions(name).getBody()) {
            if (v.getVersion().isPresent()) api.deleteTraitVersion(name, v.getVersion().get());
          }
        },
        "All versions of trait '" + name + "' deleted.",
        "/ui/traits/" + name + "/versions",
        redirectAttributes);
  }

  private List<VersionView> traitVersionViews(String name) {
    var views = new ArrayList<VersionView>();
    for (var v : api.listTraitVersions(name).getBody()) {
      views.add(
          new VersionView(
              v.getVersion().orElse(0),
              true,
              v.getInheritsFrom().orElse(null),
              List.of(),
              v.getSchema().orElse(null),
              null));
    }
    return views;
  }
}
