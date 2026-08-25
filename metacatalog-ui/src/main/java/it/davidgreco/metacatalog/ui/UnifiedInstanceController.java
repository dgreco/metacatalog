package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Entity;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import it.davidgreco.metacatalog.openapi.model.LinkEntityRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private final ObjectMapper jsonMapper;
  private final ObjectMapper yamlMapper;

  UnifiedInstanceController(
      MetacatalogApiDelegate api,
      ObjectMapper jsonMapper,
      @org.springframework.beans.factory.annotation.Qualifier("yamlMapper")
          ObjectMapper yamlMapper) {
    this.api = api;
    this.jsonMapper = jsonMapper;
    this.yamlMapper = yamlMapper;
  }

  @GetMapping
  public String list(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String query,
      Model model) {
    List<EntityType> types = api.listEntityTypes().getBody();
    // An absent type lists every type's entities; the query path filters within whatever the
    // type narrows the list to. An invalid jsonpath expression is a user typo, not a system
    // failure, so it renders as an error on the page with an empty result list.
    List<Entity> entities;
    try {
      entities =
          api.getEntities(
                  Optional.ofNullable(type).filter(t -> !t.isBlank()),
                  Optional.ofNullable(query).filter(q -> !q.isBlank()))
              .getBody();
    } catch (RuntimeException e) {
      entities = List.of();
      model.addAttribute("error", e.getMessage());
    }
    model.addAttribute("instances", InstanceRowView.listFrom(entities, typeCapabilities()));
    model.addAttribute("selectedType", type);
    model.addAttribute("query", query);
    model.addAttribute("entityTypes", types);
    // The query builder enumerates a type's fields client-side, so the page carries every type's
    // effective schema the same way the instance editor does.
    model.addAttribute("typeSchemas", schemaMap(types));
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
    addInstanceSchemaModel(types, entity, model);
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
      try {
        addInstanceSchemaModel(types, (Entity) api.getEntity(id).getBody(), model);
      } catch (RuntimeException lookupFailure) {
        model.addAttribute("typeSchemas", schemaMap(types));
      }
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

  /**
   * Runs one of the four aggregate procedures, asynchronously: the API returns a schedule id right
   * after plan-building (so a missing aggregate or an illegitimate root still surfaces here as a
   * flash error), and the instances page polls {@code GET /metacatalog/v1/procedure/{id}} until the
   * run completes, then reloads showing the finished statuses.
   *
   * <p>One mapping rather than four, keyed by the {@link AggregateAction} whose {@code path} the
   * form posted. The four differ only in which delegate call they make and which label they flash,
   * and both of those are already on the action. An unknown or non-procedure segment is refused
   * rather than falling through to another handler — the buttons only ever offer the four, but a
   * hand-crafted POST must not reach past them.
   */
  @PostMapping("/{id}/procedure/{action}")
  public String runProcedure(
      @PathVariable String id, @PathVariable String action, RedirectAttributes redirectAttributes) {
    var procedure =
        java.util.Arrays.stream(AggregateAction.values())
            .filter(a -> a.isProcedure() && a.getPath().equals(action))
            .findFirst();
    if (procedure.isEmpty()) {
      redirectAttributes.addFlashAttribute("error", "Unknown aggregate procedure: " + action);
      return "redirect:/ui/instances";
    }
    launchProcedure(redirectAttributes, procedure.get(), id);
    return "redirect:/ui/instances";
  }

  private static final java.util.Optional<Boolean> ASYNC = java.util.Optional.of(Boolean.TRUE);

  private void launchProcedure(
      RedirectAttributes redirectAttributes, AggregateAction action, String entityId) {
    var label = action.getProcedureLabel();
    try {
      var status = callProcedure(action, entityId).getBody();
      if (status != null && status.getScheduleId() != null) {
        redirectAttributes.addFlashAttribute("procedureScheduleId", status.getScheduleId());
        redirectAttributes.addFlashAttribute("procedureLabel", label);
        redirectAttributes.addFlashAttribute("procedureEntityId", entityId);
        redirectAttributes.addFlashAttribute("procedureEntityType", entityTypeOf(entityId));
      } else {
        // No schedule id to poll (should not happen): fall back to a plain confirmation.
        redirectAttributes.addFlashAttribute("message", label + " started.");
      }
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
  }

  /**
   * The delegate call each procedure action makes. The generated delegate has one typed method per
   * operation, so this is where the action becomes a call; everything either side of it is shared.
   */
  private org.springframework.http.ResponseEntity<
          it.davidgreco.metacatalog.openapi.model.ProcedureStatus>
      callProcedure(AggregateAction action, String entityId) {
    return switch (action) {
      case PROVISION -> api.provisionAggregate(entityId, ASYNC);
      case UNPROVISION -> api.unprovisionAggregate(entityId, ASYNC);
      case AUTHORIZE -> api.authorizeAggregate(entityId, ASYNC);
      case REJECT -> api.rejectAggregate(entityId, ASYNC);
      case DELETE ->
          throw new IllegalArgumentException("DELETE is not a procedure: " + action.getPath());
    };
  }

  /** The aggregate's entity type name, for the progress popup; best-effort, empty if unknown. */
  private String entityTypeOf(String entityId) {
    try {
      var entity = (Entity) api.getEntity(entityId).getBody();
      return entity != null && entity.getEntityType() != null ? entity.getEntityType() : "";
    } catch (RuntimeException e) {
      return "";
    }
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
      addInstanceSchemaModel(types, entity, model);
      addValueRenderings(entity.getValues(), model);
      model.addAttribute("readOnly", true);
      return "instances-form";
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui/instances";
    }
  }

  /**
   * Which types are aggregate roots, which can be provisioned and which can be authorized.
   *
   * <p>Asked of the API rather than worked out here: deciding any of them needs the type and trait
   * inheritance chains, which this module has no access to by design. Asked in <em>one</em> call
   * because answering any of them means walking the whole catalog and every inheritance chain in
   * it, and this page needs all three — the three still come back as three separate answers, since
   * the capabilities are declared by independent pairs of traits and none implies another.
   */
  private InstanceRowView.TypeCapabilities typeCapabilities() {
    var capabilities = api.getTypeCapabilities().getBody();
    return new InstanceRowView.TypeCapabilities(
        Set.copyOf(capabilities.getAggregateRoot()),
        Set.copyOf(capabilities.getProvisionable()),
        Set.copyOf(capabilities.getAuthorizable()));
  }

  private Map<String, String> schemaMap(List<EntityType> types) {
    var map = new LinkedHashMap<String, String>();
    for (var t : types) {
      map.put(t.getName(), t.getSchema());
    }
    return map;
  }

  /**
   * Adds the schema map for a page showing one existing instance, replacing the instance's own type
   * entry with the schema of the version snapshot the instance is pinned to. The editor then
   * renders — and the user edits against — the same schema the API validates an update with, rather
   * than the latest version's.
   *
   * <p>Snapshots are matched by id: {@code GET /entity-type/{name}/versions} returns the historical
   * snapshots (carrying their snapshot ids) plus the live row, whose DTO id is the entity-type row
   * id. An instance pinned to the current live version therefore matches nothing and keeps the live
   * schema, which is the correct one. When an older snapshot matches, its version number is exposed
   * as {@code pinnedVersion} so the page can say so.
   */
  @SuppressWarnings("unchecked")
  private void addInstanceSchemaModel(List<EntityType> types, Entity entity, Model model) {
    var map = schemaMap(types);
    List<EntityType> versions =
        (List<EntityType>) api.listEntityTypeVersions(entity.getEntityType()).getBody();
    for (var version : versions) {
      if (version.getId().map(id -> id.equals(entity.getEntityTypeVersionId())).orElse(false)) {
        map.put(entity.getEntityType(), version.getSchema());
        version.getVersion().ifPresent(v -> model.addAttribute("pinnedVersion", v));
        break;
      }
    }
    model.addAttribute("typeSchemas", map);
  }

  /**
   * Adds pretty-printed JSON and YAML renderings of the stored values, backing the JSON / YAML tabs
   * of the read-only view page. Values that fail to parse (which the API should never return) fall
   * back to the raw string under the JSON tab, and the YAML tab is not offered.
   */
  private void addValueRenderings(String values, Model model) {
    try {
      var tree = jsonMapper.readTree(values);
      model.addAttribute(
          "valuesJson", jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
      var yaml = yamlMapper.writeValueAsString(tree);
      model.addAttribute(
          "valuesYaml", yaml.startsWith("---") ? yaml.substring(3).stripLeading() : yaml);
    } catch (JsonProcessingException e) {
      model.addAttribute("valuesJson", values);
    }
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
    return InstanceRowView.listFrom(api.getEntities(Optional.empty(), Optional.empty()).getBody());
  }
}
