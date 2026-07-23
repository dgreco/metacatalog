package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.EntityType;
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
 * Entity type CRUD and entity type version management (list / new / create / delete / delete-all)
 * via the REST API.
 */
@Controller
@RequestMapping("/ui")
public class EntityTypeUiController {

  private final MetacatalogApiDelegate api;

  public EntityTypeUiController(MetacatalogApiDelegate api) {
    this.api = api;
  }

  // --- entity type CRUD -----------------------------------------------------------

  @GetMapping("/entity-types/new")
  public String newEntityType(Model model) {
    if (!model.containsAttribute("entityTypeForm")) {
      model.addAttribute("entityTypeForm", new EntityTypeForm());
    }
    model.addAttribute("entityTypes", api.listEntityTypes().getBody());
    model.addAttribute("traits", api.listTraits().getBody());
    return "entity-type-form";
  }

  @PostMapping("/entity-types")
  public String createEntityType(
      @ModelAttribute("entityTypeForm") EntityTypeForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      var dto = new EntityType();
      dto.setName(form.getName());
      dto.setSchema(form.getSchema());
      dto.setTraits(form.getTraits() == null ? List.of() : form.getTraits());
      if (form.getFather() != null && !form.getFather().isBlank())
        dto.inheritsFrom(form.getFather());
      api.createEntityType(dto);
      redirectAttributes.addFlashAttribute(
          "message", "Entity type '" + form.getName() + "' created.");
      return "redirect:/ui";
    } catch (RuntimeException e) {
      model.addAttribute("error", e.getMessage());
      model.addAttribute("entityTypes", api.listEntityTypes().getBody());
      model.addAttribute("traits", api.listTraits().getBody());
      return "entity-type-form";
    }
  }

  @PostMapping("/entity-types/delete")
  public String deleteEntityType(@RequestParam String name, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> api.deleteEntityType(name),
        "Entity type '" + name + "' deleted.",
        "Entity type",
        name,
        "/ui",
        redirectAttributes);
  }

  // --- entity type versioning ----------------------------------------------------

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
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui";
    }
  }

  @GetMapping("/entity-types/{name}/versions/new")
  public String newEntityTypeVersion(
      @PathVariable String name, Model model, RedirectAttributes redirectAttributes) {
    EntityType live;
    try {
      live = api.getEntityType(name).getBody();
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui";
    }
    if (!model.containsAttribute("entityTypeVersionForm")) {
      var form = new EntityTypeVersionForm();
      form.setName(live.getName());
      form.setFather(live.getInheritsFrom().orElse(null));
      form.setTraits(live.getTraits());
      form.setSchema(live.getSchema());
      model.addAttribute("entityTypeVersionForm", form);
    }
    model.addAttribute("currentVersion", live.getVersion().orElse(null));
    model.addAttribute("entityTypes", api.listEntityTypes().getBody());
    model.addAttribute("traits", api.listTraits().getBody());
    return "entity-type-version-form";
  }

  @PostMapping("/entity-types/{name}/versions")
  public String createEntityTypeVersion(
      @PathVariable String name,
      @ModelAttribute("entityTypeVersionForm") EntityTypeVersionForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      var dto = new EntityType();
      dto.setSchema(form.getSchema());
      dto.setTraits(form.getTraits() == null ? List.of() : form.getTraits());
      if (form.getFather() != null && !form.getFather().isBlank())
        dto.inheritsFrom(form.getFather());
      api.createEntityTypeVersion(name, dto);
      redirectAttributes.addFlashAttribute(
          "message", "New version of entity type '" + name + "' created.");
      return "redirect:/ui/entity-types/" + name + "/versions";
    } catch (RuntimeException e) {
      model.addAttribute("error", e.getMessage());
      populateEntityTypeVersionModel(name, model);
      return "entity-type-version-form";
    }
  }

  private void populateEntityTypeVersionModel(String name, Model model) {
    try {
      var live = api.getEntityType(name).getBody();
      model.addAttribute("currentVersion", live.getVersion().orElse(null));
    } catch (RuntimeException e) {
      model.addAttribute("currentVersion", null);
    }
    model.addAttribute("entityTypes", api.listEntityTypes().getBody());
    model.addAttribute("traits", api.listTraits().getBody());
  }

  @PostMapping("/entity-types/{name}/versions/{version}/delete")
  public String deleteEntityTypeVersion(
      @PathVariable String name, @PathVariable int version, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> api.deleteEntityTypeVersion(name, version),
        "Version " + version + " of entity type '" + name + "' deleted.",
        "/ui/entity-types/" + name + "/versions",
        redirectAttributes);
  }

  @PostMapping("/entity-types/{name}/versions/delete-all")
  public String deleteAllEntityTypeVersions(
      @PathVariable String name, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> {
          for (var v : api.listEntityTypeVersions(name).getBody()) {
            if (v.getVersion().isPresent()) api.deleteEntityTypeVersion(name, v.getVersion().get());
          }
        },
        "All versions of entity type '" + name + "' deleted.",
        "/ui/entity-types/" + name + "/versions",
        redirectAttributes);
  }

  private List<VersionView> entityTypeVersionViews(String name) {
    var views = new ArrayList<VersionView>();
    for (var v : api.listEntityTypeVersions(name).getBody()) {
      views.add(
          new VersionView(
              v.getVersion().orElse(0),
              true,
              v.getInheritsFrom().orElse(null),
              v.getTraits() == null ? List.of() : v.getTraits(),
              v.getSchema(),
              null));
    }
    return views;
  }
}
