package it.davidgreco.metacatalog.sparql;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the browser-rendered SPARQL query UI at {@code /sparql}.
 *
 * <p>The page itself is a server-side rendered Thymeleaf template that loads Yasgui (a SPARQL query
 * editor + results visualiser) from a CDN and points it at the protocol endpoint ({@code
 * /sparql/query}) served by {@link SparqlEndpointController}. The ontology IRI prefix and a default
 * query are injected into the model so the template can render them server-side.
 */
@Controller
@ConditionalOnProperty(
    prefix = "application.sparql",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SparqlUiController {
  private final String sparqlEndpoint;
  private final String ontologyIri;
  private final String defaultQuery;

  public SparqlUiController(
      @Value("${application.sparql.endpoint:/sparql/query}") String sparqlEndpoint,
      @Value("${application.sparql.ontology-iri:http://metacatalog/}") String ontologyIri,
      @Value("${application.sparql.default-query}") String defaultQuery) {
    this.sparqlEndpoint = sparqlEndpoint;
    this.ontologyIri = ontologyIri;
    this.defaultQuery = defaultQuery;
  }

  @GetMapping(value = "/sparql")
  public String sparqlUi(Model model) {
    model.addAttribute("endpoint", sparqlEndpoint);
    model.addAttribute("ontologyIri", ontologyIri);
    model.addAttribute("defaultQuery", defaultQuery);
    return "sparql";
  }
}
