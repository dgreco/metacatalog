package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Entity;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import it.davidgreco.metacatalog.openapi.model.LinkEntityRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    // An absent type lists every type's entities.
    List<Entity> entities =
        api.getEntities(Optional.ofNullable(type).filter(t -> !t.isBlank()), Optional.empty())
            .getBody();
    model.addAttribute("instances", InstanceRowView.listFrom(entities, aggregateRootTypeNames()));
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

  @PostMapping("/{id}/delete-aggregate")
  public String deleteAggregate(@PathVariable String id, RedirectAttributes redirectAttributes) {
    try {
      api.deleteAggregate(id);
      redirectAttributes.addFlashAttribute("message", "Aggregate deleted.");
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

  private Set<String> aggregateRootTypeNames() {
    return api.listAggregateRootTypes().getBody().stream()
        .map(EntityType::getName)
        .collect(Collectors.toSet());
  }

  private Map<String, String> schemaMap(List<EntityType> types) {
    var map = new LinkedHashMap<String, String>();
    for (var t : types) {
      map.put(t.getName(), t.getSchema());
    }
    return map;
  }

  // --- entity links ----------------------------------------------------------

  @GetMapping("/{id}/links")
  public String linkForm(@PathVariable String id, Model model) {
    if (!model.containsAttribute("entityLinkForm")) {
      var form = new EntityLinkForm();
      form.setSourceEntityId(id);
      model.addAttribute("entityLinkForm", form);
    }
    populateLinkModel(id, model);
    return "entity-link-form";
  }

  @PostMapping("/{id}/links")
  public String createLink(
      @PathVariable String id,
      @ModelAttribute("entityLinkForm") EntityLinkForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      // The relation type is validated by the API, which rejects an unknown name with a 400. The
      // form only offers the linkable ones, but nothing stops a hand-crafted POST naming a mapping
      // type, which the API would happily store in the entity relationship table.
      var relType = form.getRelationshipType();
      if (UiControllerHelper.MAPPING_RELATION_TYPE_NAMES.contains(relType)) {
        return renderLinkError(
            id,
            model,
            "Mapping relationships are derived from mapping rules and cannot be created by hand.");
      }
      var req = new LinkEntityRequest();
      req.setSourceEntityId(form.getSourceEntityId());
      req.setRelationshipTypeName(relType);
      req.setTargetEntityId(form.getTargetEntityId());
      api.linkEntity(req);
      redirectAttributes.addFlashAttribute(
          "message",
          "Linked '"
              + form.getSourceEntityId()
              + "' "
              + relType
              + " '"
              + form.getTargetEntityId()
              + "' (inverse created too).");
      return "redirect:/ui/instances/" + id + "/links";
    } catch (IllegalArgumentException e) {
      return renderLinkError(id, model, "Invalid relationship type.");
    } catch (RuntimeException e) {
      return renderLinkError(id, model, e.getMessage());
    }
  }

  @PostMapping("/{id}/links/delete")
  public String deleteLink(
      @PathVariable String id,
      @RequestParam String sourceEntityId,
      @RequestParam String relationshipType,
      @RequestParam String targetEntityId,
      RedirectAttributes redirectAttributes) {
    try {
      api.unlinkEntity(sourceEntityId, relationshipType, targetEntityId);
      redirectAttributes.addFlashAttribute(
          "message",
          "Removed relationship between '" + sourceEntityId + "' and '" + targetEntityId + "'.");
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("error", "Invalid relationship type.");
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui/instances/" + id + "/links";
  }

  private String renderLinkError(String id, Model model, String message) {
    model.addAttribute("error", message);
    populateLinkModel(id, model);
    return "entity-link-form";
  }

  /**
   * Fills in everything the entity-link page renders. Mapping relationships get their own attribute
   * because the page shows them without the create/remove controls the entity links have.
   */
  private void populateLinkModel(String id, Model model) {
    var instanceRows = allInstanceRows();
    model.addAttribute("instances", instanceRows);
    model.addAttribute("relationTypes", UiControllerHelper.LINKABLE_RELATION_TYPE_NAMES);
    model.addAttribute(
        "entityLinks",
        EntityLinkView.listFrom(api.listEntityRelationships().getBody(), instanceRows, id));
    model.addAttribute(
        "mappingLinks",
        EntityLinkView.listFromMappings(
            api.listMappingEntityRelationships().getBody(), instanceRows, id));
    model.addAttribute("sourceId", id);
  }

  private List<InstanceRowView> allInstanceRows() {
    return InstanceRowView.listFrom(
        api.getEntities(Optional.empty(), Optional.empty()).getBody(), Set.of());
  }
}
