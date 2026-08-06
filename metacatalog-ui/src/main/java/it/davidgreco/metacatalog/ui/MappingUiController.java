package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Mapping;
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

/** Mapping CRUD: create / delete mapping entity type relationships via the REST API. */
@Controller
@RequestMapping("/ui")
public class MappingUiController {

  private final MetacatalogApiDelegate api;
  private final ObjectMapper jsonMapper;
  private final HtmlSafeJsonSerializer jsonSerializer;

  public MappingUiController(
      MetacatalogApiDelegate api, ObjectMapper jsonMapper, HtmlSafeJsonSerializer jsonSerializer) {
    this.api = api;
    this.jsonMapper = jsonMapper;
    this.jsonSerializer = jsonSerializer;
  }

  @GetMapping("/mappings/new")
  public String newMapping(Model model) {
    if (!model.containsAttribute("mappingForm")) {
      model.addAttribute("mappingForm", new MappingForm());
    }
    model.addAttribute("entityTypes", api.listEntityTypes().getBody());
    addMappings(model);
    return "mapping-form";
  }

  /**
   * Adds the existing mappings twice: as view objects for the table, and as embedded JSON so the
   * client-side editor can propose the values of a previous mapping with the same source and target
   * types.
   */
  private void addMappings(Model model) {
    var mappings = MappingView.listFrom(api.listMappings().getBody());
    model.addAttribute("mappings", mappings);
    model.addAttribute("mappingsJson", jsonSerializer.write(mappings, "[]"));
  }

  @PostMapping("/mappings")
  public String createMapping(
      @ModelAttribute("mappingForm") MappingForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      var dto = new Mapping();
      dto.setSourceEntityType(form.getSourceEntityType());
      dto.setTargetEntityType(form.getTargetEntityType());
      dto.setMappingValues(form.getMappingValues());
      dto.setEntityPathReferences(entityPathReferencesJson(form));
      api.createMapping(dto);
      redirectAttributes.addFlashAttribute(
          "message",
          "Mapping from '"
              + form.getSourceEntityType()
              + "' to '"
              + form.getTargetEntityType()
              + "' saved (an existing mapping for the pair is replaced).");
      return "redirect:/ui";
    } catch (RuntimeException e) {
      return renderMappingError(model, e.getMessage());
    }
  }

  private String renderMappingError(Model model, String message) {
    model.addAttribute("error", message);
    model.addAttribute("entityTypes", api.listEntityTypes().getBody());
    addMappings(model);
    return "mapping-form";
  }

  private String entityPathReferencesJson(MappingForm form) {
    var aliases = form.getAliases() == null ? List.<String>of() : form.getAliases();
    var paths = form.getReferencePaths() == null ? List.<String>of() : form.getReferencePaths();
    var refs = new ArrayList<java.util.Map<String, String>>();
    for (int i = 0; i < Math.min(aliases.size(), paths.size()); i++) {
      var alias = aliases.get(i);
      var path = paths.get(i);
      if (alias != null && !alias.isBlank() && path != null && !path.isBlank()) {
        refs.add(java.util.Map.of("alias", alias.trim(), "referencePath", path.trim()));
      }
    }
    try {
      return jsonMapper.writeValueAsString(refs);
    } catch (Exception e) {
      return "[]";
    }
  }

  @PostMapping("/mappings/delete")
  public String deleteMapping(@RequestParam String id, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> api.deleteMapping(id), "Mapping deleted.", "Mapping", id, "/ui", redirectAttributes);
  }
}
