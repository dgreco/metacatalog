package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.Entity;
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

/** Entity instance CRUD UI (list / new / create / edit / update / delete / view). */
@Controller
@RequestMapping("/ui/instances")
class EntityInstanceController {

  private final EntityService entityService;
  private final EntityTypeService entityTypeService;
  private final ObjectMapper jsonMapper;

  EntityInstanceController(
      EntityService entityService, EntityTypeService entityTypeService, ObjectMapper jsonMapper) {
    this.entityService = entityService;
    this.entityTypeService = entityTypeService;
    this.jsonMapper = jsonMapper;
  }

  /** Redirects to the entities list as the default instances page. */
  @GetMapping
  public String index() {
    return "redirect:/ui/instances/entities";
  }

  // --- entity list ----------------------------------------------------------------

  /** Renders the entity instance list with optional type filter. */
  @GetMapping("/entities")
  public String listEntities(@RequestParam(required = false) String type, Model model) {
    List<Entity> entities;
    if (type != null && !type.isBlank()) {
      entities = entityService.list(type, "");
    } else {
      entities = entityService.listAll();
    }
    model.addAttribute("instances", EntityInstanceView.listFrom(entities, jsonMapper));
    model.addAttribute("selectedType", type);
    model.addAttribute("entityTypes", entityTypeService.list());
    return "entity-instance-list";
  }

  // --- entity create --------------------------------------------------------------

  /** Renders the entity creation form. */
  @GetMapping("/entities/new")
  public String newEntity(Model model) {
    if (!model.containsAttribute("entityForm")) {
      model.addAttribute("entityForm", new EntityInstanceForm());
    }
    model.addAttribute("entityTypes", entityTypeService.list());
    return "entity-instance-form";
  }

  /** Handles submission of the entity creation form. */
  @PostMapping("/entities")
  public String createEntity(
      @ModelAttribute("entityForm") EntityInstanceForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      entityService.create(form.getEntityType(), form.getValues());
      redirectAttributes.addFlashAttribute("message", "Entity created.");
      return "redirect:/ui/instances/entities";
    } catch (ServiceError e) {
      model.addAttribute("entityForm", form);
      model.addAttribute("entityTypes", entityTypeService.list());
      model.addAttribute("error", e.getMessage());
      return "entity-instance-form";
    }
  }

  // --- entity edit ----------------------------------------------------------------

  /** Renders the entity edit form. */
  @GetMapping("/entities/{id}/edit")
  public String editEntity(@PathVariable String id, Model model) {
    Entity entity = entityService.read(id);
    EntityInstanceForm form = new EntityInstanceForm();
    form.setEntityType(entity.getEntityType().getName());
    form.setValues(entity.getValues().toPrettyString());
    model.addAttribute("entityForm", form);
    model.addAttribute("entityId", id);
    model.addAttribute("entityTypes", entityTypeService.list());
    return "entity-instance-form";
  }

  /** Handles submission of the entity edit form. */
  @PostMapping("/entities/{id}")
  public String updateEntity(
      @PathVariable String id,
      @ModelAttribute("entityForm") EntityInstanceForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      entityService.update(id, form.getValues());
      redirectAttributes.addFlashAttribute("message", "Entity updated.");
      return "redirect:/ui/instances/entities";
    } catch (ServiceError e) {
      model.addAttribute("entityForm", form);
      model.addAttribute("entityId", id);
      model.addAttribute("entityTypes", entityTypeService.list());
      model.addAttribute("error", e.getMessage());
      return "entity-instance-form";
    }
  }

  // --- entity delete --------------------------------------------------------------

  /** Handles entity deletion. */
  @PostMapping("/entities/{id}/delete")
  public String deleteEntity(@PathVariable String id, RedirectAttributes redirectAttributes) {
    try {
      entityService.delete(id);
      redirectAttributes.addFlashAttribute("message", "Entity deleted.");
    } catch (ServiceError e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui/instances/entities";
  }

  // --- entity view ----------------------------------------------------------------

  /** Renders the entity detail view. */
  @GetMapping("/entities/{id}")
  public String viewEntity(@PathVariable String id, Model model) {
    Entity entity = entityService.read(id);
    model.addAttribute("entity", entity);
    model.addAttribute("valuesJson", entity.getValues().toPrettyString());
    return "entity-instance-view";
  }
}
