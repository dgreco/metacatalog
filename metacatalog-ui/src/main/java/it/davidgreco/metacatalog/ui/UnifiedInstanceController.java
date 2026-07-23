package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Entity;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Instances: a single page that lists, creates, edits, deletes and views entities via the REST API
 * delegate ({@link MetacatalogApiDelegate}).
 */
@Controller
@RequestMapping("/ui/instances")
class UnifiedInstanceController {

  private final MetacatalogApiDelegate api;

  UnifiedInstanceController(MetacatalogApiDelegate api) {
    this.api = api;
  }

  @GetMapping
  public String list(@RequestParam(required = false) String type, Model model) {
    List<EntityType> types = api.listEntityTypes().getBody();
    List<Entity> entities = new ArrayList<>();
    if (type != null && !type.isBlank()) {
      entities.addAll(api.getEntities(type, "").getBody());
    } else {
      for (var t : types) {
        entities.addAll(api.getEntities(t.getName(), "").getBody());
      }
    }
    model.addAttribute("instances", InstanceRowView.listFrom(entities));
    model.addAttribute("selectedType", type);
    model.addAttribute("entityTypes", types);
    return "instances-list";
  }

  @GetMapping("/new")
  public String newForm(@RequestParam(required = false) String type, Model model) {
    if (!model.containsAttribute("instanceForm")) {
      InstanceForm form = new InstanceForm();
      form.setEntityType(type);
      model.addAttribute("instanceForm", form);
    }
    var types = api.listEntityTypes().getBody();
    model.addAttribute("entityTypes", types);
    model.addAttribute("typeSchemas", schemaMap(types));
    return "instances-form";
  }

  @PostMapping
  public String create(
      @ModelAttribute("instanceForm") InstanceForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      var dto = new Entity();
      dto.setEntityType(form.getEntityType());
      dto.setValues(form.getValues());
      api.createEntity(dto);
      redirectAttributes.addFlashAttribute("message", "Entity created.");
      return "redirect:/ui/instances";
    } catch (RuntimeException e) {
      model.addAttribute("instanceForm", form);
      var types = api.listEntityTypes().getBody();
      model.addAttribute("entityTypes", types);
      model.addAttribute("typeSchemas", schemaMap(types));
      model.addAttribute("error", e.getMessage());
      return "instances-form";
    }
  }

  @GetMapping("/{id}/edit")
  public String edit(@PathVariable String id, Model model) {
    var entity = (Entity) api.getEntity(id).getBody();
    var form = new InstanceForm();
    form.setEntityType(entity.getEntityType());
    form.setValues(entity.getValues());
    model.addAttribute("instanceForm", form);
    model.addAttribute("instanceId", id);
    var types = api.listEntityTypes().getBody();
    model.addAttribute("entityTypes", types);
    model.addAttribute("typeSchemas", schemaMap(types));
    return "instances-form";
  }

  @PostMapping("/{id}")
  public String update(
      @PathVariable String id,
      @ModelAttribute("instanceForm") InstanceForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      var dto = new Entity();
      dto.setEntityType(form.getEntityType());
      dto.setValues(form.getValues());
      api.updateEntity(id, dto);
      redirectAttributes.addFlashAttribute("message", "Entity updated.");
      return "redirect:/ui/instances";
    } catch (RuntimeException e) {
      model.addAttribute("instanceForm", form);
      model.addAttribute("instanceId", id);
      var types = api.listEntityTypes().getBody();
      model.addAttribute("entityTypes", types);
      model.addAttribute("typeSchemas", schemaMap(types));
      model.addAttribute("error", e.getMessage());
      return "instances-form";
    }
  }

  @PostMapping("/{id}/delete")
  public String delete(@PathVariable String id, RedirectAttributes redirectAttributes) {
    try {
      api.deleteEntity(id);
      redirectAttributes.addFlashAttribute("message", "Entity deleted.");
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui/instances";
  }

  @GetMapping("/{id}")
  public String view(@PathVariable String id, Model model, RedirectAttributes redirectAttributes) {
    try {
      var entity = (Entity) api.getEntity(id).getBody();
      var form = new InstanceForm();
      form.setEntityType(entity.getEntityType());
      form.setValues(entity.getValues());
      var types = api.listEntityTypes().getBody();
      model.addAttribute("instanceForm", form);
      model.addAttribute("instanceId", id);
      model.addAttribute("entityTypes", types);
      model.addAttribute("typeSchemas", schemaMap(types));
      model.addAttribute("readOnly", true);
      return "instances-form";
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui/instances";
    }
  }

  private Map<String, String> schemaMap(List<EntityType> types) {
    var map = new LinkedHashMap<String, String>();
    for (var t : types) {
      map.put(t.getName(), t.getSchema());
    }
    return map;
  }
}
