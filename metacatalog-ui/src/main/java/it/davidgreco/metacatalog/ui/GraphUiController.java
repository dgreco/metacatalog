package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Dashboard and interactive graph pages. The dashboard lists traits, entity types, trait
 * relationships, and mappings via the REST API; the graph page delegates to {@link
 * CatalogGraphService} (which still uses core services directly — TODO: move to REST API once the
 * spec has list-all endpoints).
 */
@Controller
@RequestMapping("/ui")
public class GraphUiController {

  private final MetacatalogApiDelegate api;
  private final CatalogGraphService catalogGraphService;

  public GraphUiController(MetacatalogApiDelegate api, CatalogGraphService catalogGraphService) {
    this.api = api;
    this.catalogGraphService = catalogGraphService;
  }

  @GetMapping({"", "/"})
  public String index(Model model) {
    model.addAttribute("traits", TraitRowView.listFrom(api.listTraits().getBody()));
    model.addAttribute("entityTypes", EntityTypeRowView.listFrom(api.listEntityTypes().getBody()));
    model.addAttribute(
        "traitLinks", TraitLinkView.listFrom(api.listTraitRelationships().getBody()));
    model.addAttribute("mappings", MappingView.listFrom(api.listMappings().getBody()));
    return "index";
  }

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

  @GetMapping("/graph/data")
  @ResponseBody
  public GraphModel graphData(
      @RequestParam(defaultValue = "true") boolean showInverses,
      @RequestParam(defaultValue = "false") boolean showEntities) {
    return catalogGraphService.buildGraphModel(showInverses, showEntities);
  }
}
