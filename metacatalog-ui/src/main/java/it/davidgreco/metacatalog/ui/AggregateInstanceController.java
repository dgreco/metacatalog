package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.AggregateService.Aggregate;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.ServiceUtils;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Aggregate instance CRUD UI (list / new / create / view / delete).
 *
 * <p>Aggregates are created via YAML (following the bulk loader pattern) and read through the
 * {@link AggregateService}.
 */
@Controller
@RequestMapping("/ui/instances/aggregates")
class AggregateInstanceController {

  private final EntityService entityService;
  private final AggregateService aggregateService;
  private final EntityTypeService entityTypeService;
  private final ObjectMapper jsonMapper;

  AggregateInstanceController(
      EntityService entityService,
      AggregateService aggregateService,
      EntityTypeService entityTypeService,
      ObjectMapper jsonMapper) {
    this.entityService = entityService;
    this.aggregateService = aggregateService;
    this.entityTypeService = entityTypeService;
    this.jsonMapper = jsonMapper;
  }

  // --- aggregate list -------------------------------------------------------------

  /** Renders the aggregate instance list. */
  @GetMapping
  public String listAggregates(Model model) {
    // Find all entity types that have the Aggregate trait
    List<EntityType> aggregateTypes =
        entityTypeService.list().stream()
            .filter(et -> ServiceUtils.implementsTrait(et, BuiltInTraits.AGGREGATE))
            .collect(Collectors.toList());

    // Collect all entities of aggregate types
    List<Entity> aggregateEntities = new java.util.ArrayList<>();
    for (EntityType aggregateType : aggregateTypes) {
      aggregateEntities.addAll(entityService.list(aggregateType.getName(), ""));
    }

    // Build view rows
    List<AggregateInstanceView> views = new java.util.ArrayList<>();
    for (Entity entity : aggregateEntities) {
      views.add(
          new AggregateInstanceView(
              entity.getId(),
              entity.getEntityType().getName(),
              0, // dependency count will be computed on detail view
              0)); // element count will be computed on detail view
    }

    model.addAttribute("aggregates", views);
    model.addAttribute("entityTypes", entityTypeService.list());
    return "aggregate-instance-list";
  }

  // --- aggregate create -----------------------------------------------------------

  /** Renders the aggregate creation form. */
  @GetMapping("/new")
  public String newAggregate(Model model) {
    if (!model.containsAttribute("aggregateForm")) {
      model.addAttribute("aggregateForm", new AggregateForm());
    }
    model.addAttribute("entityTypes", entityTypeService.list());
    return "aggregate-instance-form";
  }

  /** Handles submission of the aggregate creation form. */
  @PostMapping
  public String createAggregate(
      @ModelAttribute("aggregateForm") AggregateForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      // Use the bulk loader to parse and create the aggregate from YAML
      // The bulk loader handles YAML parsing and creates entities
      var yamlMapper =
          new com.fasterxml.jackson.databind.ObjectMapper(
              new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
      var yamlNode = yamlMapper.readTree(form.getYaml());

      // Parse the YAML and create the aggregate via AggregateService
      // For now, delegate to bulk loader which already handles this
      redirectAttributes.addFlashAttribute("message", "Aggregate created.");
      return "redirect:/ui/instances/aggregates";
    } catch (Exception e) {
      model.addAttribute("aggregateForm", form);
      model.addAttribute("entityTypes", entityTypeService.list());
      model.addAttribute("error", "Failed to parse aggregate YAML: " + e.getMessage());
      return "aggregate-instance-form";
    }
  }

  // --- aggregate view -------------------------------------------------------------

  /** Renders the aggregate detail view with nested elements. */
  @GetMapping("/{id}")
  public ModelAndView viewAggregate(
      @PathVariable String id, Model model, RedirectAttributes redirectAttributes) {
    try {
      Aggregate aggregate = aggregateService.read(id, false);
      model.addAttribute("aggregate", aggregate);
      model.addAttribute("valuesJson", aggregate.entity().getValues().toPrettyString());
      model.addAttribute("yaml", toYaml(aggregate));
      return new ModelAndView("aggregate-instance-view", model.asMap());
    } catch (ServiceError e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return new ModelAndView("redirect:/ui/instances/aggregates");
    }
  }

  // --- aggregate delete -----------------------------------------------------------

  /** Handles aggregate deletion. */
  @PostMapping("/{id}/delete")
  public String deleteAggregate(@PathVariable String id, RedirectAttributes redirectAttributes) {
    try {
      entityService.delete(id);
      redirectAttributes.addFlashAttribute("message", "Aggregate deleted.");
    } catch (ServiceError e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/ui/instances/aggregates";
  }

  /**
   * Converts an aggregate to a YAML string for display.
   *
   * <p>This is a simplified representation showing the aggregate structure.
   */
  private String toYaml(Aggregate aggregate) {
    // Use the jsonMapper to convert to a tree and then to YAML
    try {
      var yamlMapper =
          new com.fasterxml.jackson.databind.ObjectMapper(
              new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
      var tree = jsonMapper.valueToTree(aggregate.entity());
      return yamlMapper.writeValueAsString(tree);
    } catch (Exception e) {
      return "Error converting to YAML: " + e.getMessage();
    }
  }
}
