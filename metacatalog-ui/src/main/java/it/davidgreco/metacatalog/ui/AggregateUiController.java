package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Entity;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Aggregate authoring: pick an aggregate root type, fill in the tree the combined JSON Schema
 * describes, and create the whole aggregate in one submit.
 *
 * <p>The root types and the combined schema come from the REST API ({@code GET
 * /aggregate/root-type} and {@code .../{name}/schema}), which derives the schema from the
 * type-level {@code HAS_PART} composition graph. The browser builds the document against that
 * schema and posts it as JSON; this controller converts it to YAML — the format {@code POST
 * /metacatalog/v1/aggregate/yaml} consumes — and hands it back to the API.
 */
@Controller
@RequestMapping("/ui/aggregates")
public class AggregateUiController {

  private final MetacatalogApiDelegate api;
  private final ObjectMapper jsonMapper;
  private final ObjectMapper yamlMapper;
  private final HtmlSafeJsonSerializer jsonSerializer;

  public AggregateUiController(
      MetacatalogApiDelegate api,
      ObjectMapper jsonMapper,
      @Qualifier("yamlMapper") ObjectMapper yamlMapper,
      HtmlSafeJsonSerializer jsonSerializer) {
    this.api = api;
    this.jsonMapper = jsonMapper;
    this.yamlMapper = yamlMapper;
    this.jsonSerializer = jsonSerializer;
  }

  /**
   * Lists every aggregate instance — the entities whose type is an aggregate root type — with the
   * type each one actually belongs to. Reuses {@link InstanceRowView} for the human-readable name
   * derivation; the aggregate-root, provisionable and authorizable distinctions are all irrelevant
   * here, which is what the no-flags {@link InstanceRowView#listFrom(List)} says.
   */
  @GetMapping
  public String list(Model model) {
    var rows = new ArrayList<InstanceRowView>();
    for (var rootType :
        api.listAggregateRootTypes().getBody().stream().map(EntityType::getName).toList()) {
      List<Entity> entities = api.getEntities(Optional.of(rootType), Optional.empty()).getBody();
      rows.addAll(InstanceRowView.listFrom(entities));
    }
    model.addAttribute("aggregates", rows);
    return "aggregates-list";
  }

  /**
   * Shows one whole aggregate — root, parts, dependencies and values — as pretty-printed JSON or
   * YAML.
   *
   * <p>Both renderings derive from the single canonical document {@code GET /aggregate/{id}/yaml}
   * serves (the same format {@code POST /aggregate/yaml} consumes, with each node's {@code values}
   * inlined as a real object rather than an embedded JSON string): the YAML tab shows it verbatim
   * minus the document marker, the JSON tab re-serializes the same tree.
   */
  @GetMapping("/{id}")
  public String view(
      @org.springframework.web.bind.annotation.PathVariable String id,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      var resource =
          (org.springframework.core.io.Resource) api.getAggregateAsYaml(id, true).getBody();
      byte[] yamlBytes;
      try (var in = resource.getInputStream()) {
        yamlBytes = in.readAllBytes();
      }
      var tree = yamlMapper.readTree(yamlBytes);
      model.addAttribute("aggregateId", id);
      model.addAttribute("rootType", tree.path("entityType").asText(null));
      model.addAttribute(
          "aggregateJson", jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
      var yaml = new String(yamlBytes, StandardCharsets.UTF_8);
      model.addAttribute(
          "aggregateYaml", yaml.startsWith("---") ? yaml.substring(3).stripLeading() : yaml);
      return "aggregate-view";
    } catch (RuntimeException | java.io.IOException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/ui/aggregates";
    }
  }

  @GetMapping("/new")
  public String newForm(@RequestParam(required = false) String rootType, Model model) {
    if (!model.containsAttribute("aggregateForm")) {
      var form = new AggregateForm();
      form.setRootType(rootType);
      model.addAttribute("aggregateForm", form);
    }
    populate(model, rootType);
    return "aggregate-form";
  }

  @PostMapping
  public String create(
      @ModelAttribute("aggregateForm") AggregateForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      api.createAggregateAsYaml(new ByteArrayResource(toYaml(form.getDocument())));
      redirectAttributes.addFlashAttribute("message", "Aggregate created.");
      return "redirect:/ui/instances";
    } catch (RuntimeException | java.io.IOException e) {
      model.addAttribute("aggregateForm", form);
      model.addAttribute("error", e.getMessage());
      populate(model, form.getRootType());
      return "aggregate-form";
    }
  }

  /**
   * Converts the JSON document the page submits into the YAML the aggregate endpoint consumes.
   * Parsing through Jackson also rejects a malformed document up-front, so the user gets a form
   * error rather than an opaque failure from the loader.
   */
  private byte[] toYaml(String document) throws java.io.IOException {
    if (document == null || document.isBlank()) {
      throw new IllegalArgumentException("The aggregate document is empty.");
    }
    var tree = jsonMapper.readTree(document);
    var bos = new ByteArrayOutputStream();
    yamlMapper.writeValue(bos, tree);
    return bos.toByteArray();
  }

  /**
   * Adds the root-type choices and, when one is selected, its combined schema. A schema that cannot
   * be built (e.g. the type stopped being a root) surfaces as a form error rather than a 500.
   *
   * <p>Only the name is projected: the page's fields come from the combined aggregate schema, which
   * already carries each type's derived schema under its {@code values} node.
   */
  private void populate(Model model, String rootType) {
    List<String> rootTypes =
        api.listAggregateRootTypes().getBody().stream().map(EntityType::getName).toList();
    model.addAttribute("rootTypes", rootTypes);
    model.addAttribute("selectedRootType", rootType);

    if (rootType != null && !rootType.isBlank()) {
      try {
        model.addAttribute(
            "aggregateSchema", jsonSerializer.write(api.getAggregateSchema(rootType).getBody()));
      } catch (RuntimeException e) {
        model.addAttribute("error", e.getMessage());
      }
    }
  }

  /**
   * Renders the JSON document the builder currently holds as YAML, for the read-only YAML tab of
   * the authoring page. The conversion is the same one {@link #create} applies before handing the
   * document to {@code POST /aggregate/yaml}, so the preview is exactly what would be submitted.
   */
  @PostMapping(value = "/yaml-preview", produces = "text/plain")
  @ResponseBody
  public ResponseEntity<String> yamlPreview(
      @org.springframework.web.bind.annotation.RequestBody String document) {
    try {
      var yaml = new String(toYaml(document), StandardCharsets.UTF_8);
      return ResponseEntity.ok(yaml.startsWith("---") ? yaml.substring(3).stripLeading() : yaml);
    } catch (RuntimeException | java.io.IOException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    }
  }

  /** Serves the combined schema so the page can switch root type without a full reload. */
  @GetMapping("/schema")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> schema(@RequestParam String rootType) {
    return api.getAggregateSchema(rootType);
  }
}
