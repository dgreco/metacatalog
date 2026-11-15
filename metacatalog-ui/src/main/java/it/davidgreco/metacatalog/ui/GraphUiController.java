package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Dashboard and interactive graph pages. The dashboard lists traits, entity types, trait
 * relationships, and mappings via the REST API; the graph page delegates to {@link
 * CatalogGraphService}, which assembles the graph from the same API.
 */
@Controller
@RequestMapping("/ui/admin")
public class GraphUiController {

  /**
   * The traits the platform installs itself and keys off by name. Spelled out here because the UI
   * may only reach the REST contract, and these names are part of it: the API does not report which
   * traits are immutable, only refuses to change them.
   */
  static final List<String> BUILT_IN_TRAITS =
      List.of(
          "Aggregate",
          "AggregateElement",
          "Provisionable",
          "ProvisionableResource",
          "Authorizable",
          "AuthorizableResource",
          "Asset",
          "Versionable");

  private final MetacatalogApiDelegate api;
  private final CatalogGraphService catalogGraphService;
  private final HtmlSafeJsonSerializer jsonSerializer;

  public GraphUiController(
      MetacatalogApiDelegate api,
      CatalogGraphService catalogGraphService,
      HtmlSafeJsonSerializer jsonSerializer) {
    this.api = api;
    this.catalogGraphService = catalogGraphService;
    this.jsonSerializer = jsonSerializer;
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
    model.addAttribute("rolesJson", jsonSerializer.write(roles(), "{}"));
    model.addAttribute("showInverses", showInverses);
    model.addAttribute("showEntities", showEntities);
    return "graph";
  }

  /**
   * What the graph badges its nodes with: which traits are built in, and which types are asset
   * types, aggregate roots, provisionable or authorizable. The classifications are the API's — each
   * needs the type and trait ancestries to decide — so they are asked for, not derived from the
   * edges the page is about to draw.
   */
  private Map<String, List<String>> roles() {
    var capabilities = api.getTypeCapabilities().getBody();
    var roles = new LinkedHashMap<String, List<String>>();
    roles.put("builtInTraits", BUILT_IN_TRAITS);
    roles.put("assetTypes", orEmpty(capabilities == null ? null : capabilities.getAsset()));
    roles.put("rootTypes", orEmpty(capabilities == null ? null : capabilities.getAggregateRoot()));
    roles.put(
        "provisionableTypes",
        orEmpty(capabilities == null ? null : capabilities.getProvisionable()));
    roles.put(
        "authorizableTypes", orEmpty(capabilities == null ? null : capabilities.getAuthorizable()));
    return roles;
  }

  private static List<String> orEmpty(List<String> names) {
    return names == null ? List.of() : names;
  }

  @GetMapping("/graph/data")
  @ResponseBody
  public GraphModel graphData(
      @RequestParam(defaultValue = "true") boolean showInverses,
      @RequestParam(defaultValue = "false") boolean showEntities) {
    return catalogGraphService.buildGraphModel(showInverses, showEntities);
  }
}
