package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.MappingService;
import it.davidgreco.metacatalog.service.SchemaValidationError;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Mapping CRUD: create / delete mapping entity type relationships, with the mapping creation form.
 */
@Controller
@RequestMapping("/ui")
public class MappingUiController {

  private final MappingService mappingService;
  private final EntityTypeService entityTypeService;
  private final ObjectMapper jsonMapper;

  public MappingUiController(
      MappingService mappingService, EntityTypeService entityTypeService, ObjectMapper jsonMapper) {
    this.mappingService = mappingService;
    this.entityTypeService = entityTypeService;
    this.jsonMapper = jsonMapper;
  }

  /** Renders the mapping creation form. */
  @GetMapping("/mappings/new")
  public String newMapping(Model model) {
    if (!model.containsAttribute("mappingForm")) {
      model.addAttribute("mappingForm", new MappingForm());
    }
    model.addAttribute("entityTypes", entityTypeService.list());
    model.addAttribute("mappings", MappingView.listFrom(mappingService, jsonMapper));
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
    } catch (RuntimeException e) {
      return renderMappingError(model, e.getMessage());
    }
  }

  private String renderMappingError(Model model, String message) {
    model.addAttribute("error", message);
    model.addAttribute("entityTypes", entityTypeService.list());
    model.addAttribute("mappings", MappingView.listFrom(mappingService, jsonMapper));
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
    return UiControllerHelper.flashAndRedirect(
        () -> mappingService.delete(id),
        "Mapping deleted.",
        "Mapping",
        id,
        "/ui",
        redirectAttributes);
  }
}
