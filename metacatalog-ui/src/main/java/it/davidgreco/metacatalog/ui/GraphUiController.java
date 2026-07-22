package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.MappingService;
import it.davidgreco.metacatalog.service.TraitService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Dashboard and interactive graph pages. The dashboard lists traits, entity types, trait
 * relationships, and mappings; the graph page renders the full catalog as an interactive graph.
 */
@Controller
@RequestMapping("/ui")
public class GraphUiController {

  private final TraitService traitService;
  private final EntityTypeService entityTypeService;
  private final MappingService mappingService;
  private final CatalogGraphService catalogGraphService;
  private final ObjectMapper jsonMapper;

  public GraphUiController(
      TraitService traitService,
      EntityTypeService entityTypeService,
      MappingService mappingService,
      CatalogGraphService catalogGraphService,
      ObjectMapper jsonMapper) {
    this.traitService = traitService;
    this.entityTypeService = entityTypeService;
    this.mappingService = mappingService;
    this.catalogGraphService = catalogGraphService;
    this.jsonMapper = jsonMapper;
  }

  /** Dashboard listing the existing traits, entity types, trait relationships, and mappings. */
  @GetMapping({"", "/"})
  public String index(Model model) {
    model.addAttribute("traits", traitService.list());
    model.addAttribute("entityTypes", entityTypeService.list());
    model.addAttribute("traitLinks", catalogGraphService.traitLinks());
    model.addAttribute("mappings", MappingView.listFrom(mappingService, jsonMapper));
    return "index";
  }

  /** Renders the whole catalog as an interactive graph in a separate page. */
  @GetMapping("/graph")
  public String graph(
      @RequestParam(defaultValue = "true") boolean showInverses,
      @RequestParam(defaultValue = "false") boolean showEntities,
      Model model) {
    model.addAttribute("graphJson", catalogGraphService.graphJson(showInverses, showEntities));
    model.addAttribute("showInverses", showInverses);
    model.addAttribute("showEntities", showEntities);
    return "graph";
  }

  /**
   * Returns the catalog graph as JSON, so the page can re-fetch it when the user toggles the "show
   * inverses" or "show entities" option without a full page reload.
   */
  @GetMapping("/graph/data")
  @ResponseBody
  public GraphModel graphData(
      @RequestParam(defaultValue = "true") boolean showInverses,
      @RequestParam(defaultValue = "false") boolean showEntities) {
    return catalogGraphService.buildGraphModel(showInverses, showEntities);
  }
}
