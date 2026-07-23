package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.ServiceError;
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
 * Instances: a single page that lists, creates, edits, deletes and views entities. Both the list
 * and the form share a schema-driven values editor ({@link InstanceForm} backed by {@code
 * instance-values-form.js}).
 *
 * <p>Updates go through {@link EntityService#update}, which enforces the same rules as the {@code
 * PUT /metacatalog/v1/entity/{id}} REST endpoint — entities whose type is a mapping target are
 * rejected server-side with a {@link ServiceError}.
 */
@Controller
@RequestMapping("/ui/instances")
class UnifiedInstanceController {

  private final EntityService entityService;
  private final EntityTypeService entityTypeService;
  private final ObjectMapper jsonMapper;

  UnifiedInstanceController(
      EntityService entityService, EntityTypeService entityTypeService, ObjectMapper jsonMapper) {
    this.entityService = entityService;
    this.entityTypeService = entityTypeService;
    this.jsonMapper = jsonMapper;
  }

  /** Renders the instance list, optionally filtered by entity type. */
  @GetMapping
  public String list(@RequestParam(required = false) String type, Model model) {
    List<EntityType> types = entityTypeService.list();
    List<Entity> entities;
    if (type != null && !type.isBlank()) {
      entities = entityService.list(type, "");
    } else {
      entities = entityService.listAll();
    }
    model.addAttribute("instances", InstanceRowView.listFrom(entities, jsonMapper));
    model.addAttribute("selectedType", type);
    model.addAttribute("entityTypes", types);
    return "instances-list";
  }

  /** Renders the schema-driven create form. */
  @GetMapping("/new")
  public String newForm(@RequestParam(required = false) String type, Model model) {
    if (!model.containsAttribute("instanceForm")) {
      InstanceForm form = new InstanceForm();
      form.setEntityType(type);
      model.addAttribute("instanceForm", form);
    }
    model.addAttribute("entityTypes", entityTypeService.list());
    return "instances-form";
  }

  /** Handles submission of the create form. */
  @PostMapping
  public String create(
      @ModelAttribute("instanceForm") InstanceForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      entityService.create(form.getEntityType(), form.getValues());
      redirectAttributes.addFlashAttribute("message", "Entity created.");
      return "redirect:/ui/instances";
    } catch (ServiceError e) {
      model.addAttribute("instanceForm", form);
      model.addAttribute("entityTypes", entityTypeService.list());
      model.addAttribute("error", e.getMessage());
      return "instances-form";
    }
  }

  /** Renders the schema-driven edit form, seeded from the existing entity. */
  @GetMapping("/{id}/edit")
  public String edit(@PathVariable String id, Model model) {
    InstanceForm form = new InstanceForm();
    Entity entity = entityService.read(id);
    form.setEntityType(entity.getEntityType().getName());
    form.setValues(entity.getValues().toPrettyString());
    model.addAttribute("instanceForm", form);
    model.addAttribute("instanceId", id);
    model.addAttribute("entityTypes", entityTypeService.list());
    return "instances-form";
  }

  /** Handles submission of the edit form. */
  @PostMapping("/{id}")
  public String update(
      @PathVariable String id,
      @ModelAttribute("instanceForm") InstanceForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      entityService.update(id, form.getValues());
      redirectAttributes.addFlashAttribute("message", "Entity updated.");
      return "redirect:/ui/instances";
    } catch (ServiceError e) {
      model.addAttribute("instanceForm", form);
      model.addAttribute("instanceId", id);
      model.addAttribute("entityTypes", entityTypeService.list());
      model.addAttribute("error", e.getMessage());
      return "instances-form";
    }
  }

  /** Handles entity deletion. */
  @PostMapping("/{id}/delete")
  public String delete(@PathVariable String id, RedirectAttributes redirectAttributes) {
    try {
      entityService.delete(id);
      redirectAttributes.addFlashAttribute("message", "Entity deleted.");
    } catch (ServiceError e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui/instances";
  }

  /** Renders the entity detail view. */
  @GetMapping("/{id}")
  public String view(@PathVariable String id, Model model, RedirectAttributes redirectAttributes) {
    try {
      Entity entity = entityService.read(id);
      model.addAttribute("entity", entity);
      model.addAttribute("valuesJson", entity.getValues().toPrettyString());
      return "instances-view";
    } catch (ServiceError e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui/instances";
    }
  }
}
