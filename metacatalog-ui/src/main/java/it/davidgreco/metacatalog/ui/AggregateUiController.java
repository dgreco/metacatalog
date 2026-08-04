package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
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

  /** Serves the combined schema so the page can switch root type without a full reload. */
  @GetMapping("/schema")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> schema(@RequestParam String rootType) {
    return api.getAggregateSchema(rootType);
  }
}
