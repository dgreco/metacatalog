package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.TraitService;
import it.davidgreco.metacatalog.service.VersionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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
 * Entity type CRUD and entity type version management (list / new / create / delete / delete-all).
 */
@Controller
@RequestMapping("/ui")
public class EntityTypeUiController {

  private final EntityTypeService entityTypeService;
  private final TraitService traitService;

  public EntityTypeUiController(EntityTypeService entityTypeService, TraitService traitService) {
    this.entityTypeService = entityTypeService;
    this.traitService = traitService;
  }

  // --- entity type CRUD -----------------------------------------------------------

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
          UiControllerHelper.optional(form.getFather()),
          form.getSchema());
      redirectAttributes.addFlashAttribute(
          "message", "Entity type '" + form.getName() + "' created.");
      return "redirect:/ui";
    } catch (RuntimeException e) {
      model.addAttribute("error", e.getMessage());
      model.addAttribute("entityTypes", entityTypeService.list());
      model.addAttribute("traits", traitService.list());
      return "entity-type-form";
    }
  }

  /** Deletes an entity type. Fails if it is still referenced (child type or existing entities). */
  @PostMapping("/entity-types/delete")
  public String deleteEntityType(@RequestParam String name, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> entityTypeService.delete(name),
        "Entity type '" + name + "' deleted.",
        "Entity type",
        name,
        "/ui",
        redirectAttributes);
  }

  // --- entity type versioning ----------------------------------------------------

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
    } catch (RuntimeException e) {
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
    } catch (RuntimeException e) {
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
          UiControllerHelper.optional(form.getFather()),
          form.getSchema());
      redirectAttributes.addFlashAttribute(
          "message", "New version of entity type '" + name + "' created.");
      return "redirect:/ui/entity-types/" + name + "/versions";
    } catch (RuntimeException e) {
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
    } catch (RuntimeException e) {
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
    return UiControllerHelper.flashAndRedirect(
        () -> entityTypeService.deleteVersion(name, version),
        "Version " + version + " of entity type '" + name + "' deleted.",
        "/ui/entity-types/" + name + "/versions",
        redirectAttributes);
  }

  /**
   * Deletes every historical snapshot of an entity type, keeping the live row. The type continues
   * to exist at its current version with no history behind it.
   */
  @PostMapping("/entity-types/{name}/versions/delete-all")
  public String deleteAllEntityTypeVersions(
      @PathVariable String name, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> entityTypeService.deleteAllVersions(name),
        "All versions of entity type '" + name + "' deleted.",
        "/ui/entity-types/" + name + "/versions",
        redirectAttributes);
  }

  /**
   * Builds the {@link VersionView} rows for an entity type's version history. The live row is
   * tagged {@code live = true} and carries its trait names; snapshots carry their frozen trait
   * names extracted from the {@code traits} JSON array.
   */
  private List<VersionView> entityTypeVersionViews(String name) {
    var views = new ArrayList<VersionView>();
    for (var v : entityTypeService.listVersions(name)) {
      switch (v) {
        case VersionResult.Live(EntityType live) ->
            views.add(
                new VersionView(
                    live.getVersion(),
                    true,
                    live.getFather() == null ? null : live.getFather().getName(),
                    live.getTraits().stream().map(Trait::getName).collect(Collectors.toList()),
                    live.getSchema() == null ? null : live.getSchema().toPrettyString(),
                    null));
        case VersionResult.Snapshot(EntityTypeVersion snap) ->
            views.add(
                new VersionView(
                    snap.getVersion(),
                    false,
                    snap.getFatherName(),
                    UiControllerHelper.traitNames(snap.getTraits()),
                    snap.getSchema() == null ? null : snap.getSchema().toPrettyString(),
                    UiControllerHelper.formatInstant(snap.getCreatedAt())));
      }
    }
    return views;
  }
}
