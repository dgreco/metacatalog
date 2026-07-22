package it.davidgreco.metacatalog.sparql;

import it.unibz.inf.ontop.rdf4j.repository.OntopRepositoryConnection;
import it.unibz.inf.ontop.rdf4j.repository.impl.OntopVirtualRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.Query;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.resultio.sparqljson.SPARQLResultsJSONWriter;
import org.eclipse.rdf4j.query.resultio.sparqlxml.SPARQLResultsXMLWriter;
import org.eclipse.rdf4j.query.resultio.text.csv.SPARQLResultsCSVWriter;
import org.eclipse.rdf4j.query.resultio.text.tsv.SPARQLResultsTSVWriter;
import org.eclipse.rdf4j.rio.ntriples.NTriplesWriter;
import org.eclipse.rdf4j.rio.rdfxml.RDFXMLWriter;
import org.eclipse.rdf4j.rio.turtle.TurtleWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPARQL Protocol endpoint over the Ontop virtual knowledge graph.
 *
 * <p>Implements a subset of the <a href="https://www.w3.org/TR/sparql11-protocol/">SPARQL 1.1
 * Protocol</a> — SELECT, ASK, CONSTRUCT, DESCRIBE — backed by the embedded {@link
 * OntopVirtualRepository}.
 *
 * <p>The protocol endpoint is mounted at {@code /sparql/query} (the browser-rendered query UI lives
 * at {@code /sparql}; see {@link SparqlUiController}). The three query transmission forms defined
 * by the protocol are supported:
 *
 * <ol>
 *   <li>{@code GET /sparql/query?query=...} — query in a URL-encoded query parameter.
 *   <li>{@code POST /sparql/query} with {@code application/x-www-form-urlencoded} body containing a
 *       {@code query=...} field.
 *   <li>{@code POST /sparql/query} with {@code application/sparql-query} body — the raw query is
 *       the body.
 * </ol>
 *
 * <p>Response content type is driven by the {@code Accept} header (SPARQL Results JSON / XML / CSV
 * / TSV for SELECT, ASK; Turtle / RDF/XML / N-Triples for CONSTRUCT / DESCRIBE).
 */
@RestController
public class SparqlEndpointController {

  private static final Logger LOG = LoggerFactory.getLogger(SparqlEndpointController.class);

  private final OntopVirtualRepository repository;

  @Autowired
  public SparqlEndpointController(OntopVirtualRepository repository) {
    this.repository = repository;
  }

  /** Form 1: GET with the query in a {@code query} parameter. */
  @GetMapping(value = "/sparql/query")
  public void queryGet(
      @RequestParam(value = "query", required = false) String query,
      HttpServletRequest request,
      HttpServletResponse response)
      throws java.io.IOException {
    if (query == null || query.isBlank()) {
      respondError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing 'query' parameter");
      return;
    }
    execute(query, request, response);
  }

  /** Form 2: POST {@code application/x-www-form-urlencoded} with a {@code query} field. */
  @PostMapping(value = "/sparql/query", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public void queryPostForm(
      @RequestParam(value = "query", required = false) String query,
      HttpServletRequest request,
      HttpServletResponse response)
      throws java.io.IOException {
    if (query == null || query.isBlank()) {
      respondError(
          response, HttpServletResponse.SC_BAD_REQUEST, "Missing 'query' field in form body");
      return;
    }
    execute(query, request, response);
  }

  /** Form 3: POST {@code application/sparql-query} — the raw query is the request body. */
  @PostMapping(value = "/sparql/query", consumes = "application/sparql-query")
  public void queryPostDirect(
      @RequestBody(required = false) String query,
      HttpServletRequest request,
      HttpServletResponse response)
      throws java.io.IOException {
    if (query == null || query.isBlank()) {
      respondError(response, HttpServletResponse.SC_BAD_REQUEST, "Empty SPARQL query body");
      return;
    }
    execute(query, request, response);
  }

  private void execute(String query, HttpServletRequest request, HttpServletResponse response)
      throws java.io.IOException {
    String accept = request.getHeader("Accept");
    if (accept == null || accept.isBlank()) {
      accept = "*/*";
    }
    long start = System.currentTimeMillis();
    try (OntopRepositoryConnection conn = repository.getConnection()) {
      var out = response.getOutputStream();
      // Ontop's 2-arg prepareQuery(QueryLanguage, String) is implemented as a blind delegate to
      // prepareTupleQuery, so ASK and CONSTRUCT queries blow up with a ClassCastException at
      // evaluation time. Parse the query ourselves, inspect the ParsedQuery subtype, and call the
      // matching typed prepare method (which DO dispatch correctly in Ontop).
      org.eclipse.rdf4j.query.parser.ParsedQuery parsed =
          org.eclipse.rdf4j.query.parser.QueryParserUtil.parseQuery(
              QueryLanguage.SPARQL, query, null);
      Query q;
      if (parsed instanceof org.eclipse.rdf4j.query.parser.ParsedTupleQuery) {
        q = conn.prepareTupleQuery(QueryLanguage.SPARQL, query);
      } else if (parsed instanceof org.eclipse.rdf4j.query.parser.ParsedBooleanQuery) {
        q = conn.prepareBooleanQuery(QueryLanguage.SPARQL, query);
      } else if (parsed instanceof org.eclipse.rdf4j.query.parser.ParsedGraphQuery) {
        q = conn.prepareGraphQuery(QueryLanguage.SPARQL, query);
      } else {
        respondError(
            response,
            HttpServletResponse.SC_BAD_REQUEST,
            "Unsupported query type: " + parsed.getClass().getName());
        return;
      }
      if (q instanceof TupleQuery tq) {
        writeTuple(tq, accept, response, out);
      } else if (q instanceof GraphQuery gq) {
        writeGraph(gq, accept, response, out);
      } else if (q instanceof BooleanQuery bq) {
        writeBoolean(bq, accept, response, out);
      } else {
        respondError(
            response,
            HttpServletResponse.SC_BAD_REQUEST,
            "Unsupported query type: " + q.getClass().getName());
      }
      out.flush();
    } catch (MalformedQueryException e) {
      respondError(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          "Malformed SPARQL query: " + e.getMessage());
    } catch (Exception e) {
      // Log the full exception server-side, but do not leak internal details (Ontop/JDBC messages
      // routinely embed SQL fragments, table/column names and connection details) to the client.
      LOG.error("SPARQL query execution failed", e);
      respondError(
          response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Query execution failed");
    }
    LOG.debug(
        "SPARQL query executed in {}ms: {}",
        System.currentTimeMillis() - start,
        abbreviate(query, 200));
  }

  private static final MediaType SPARQL_RESULTS_JSON =
      MediaType.parseMediaType("application/sparql-results+json");
  private static final MediaType SPARQL_RESULTS_XML =
      MediaType.parseMediaType("application/sparql-results+xml");
  private static final MediaType SPARQL_RESULTS_CSV = MediaType.parseMediaType("text/csv");
  private static final MediaType SPARQL_RESULTS_TSV =
      MediaType.parseMediaType("text/tab-separated-values");
  private static final MediaType RDF_XML = MediaType.parseMediaType("application/rdf+xml");
  private static final MediaType N_TRIPLES = MediaType.parseMediaType("application/n-triples");
  private static final MediaType TURTLE = MediaType.parseMediaType("text/turtle");

  private static List<MediaType> parseAccept(String accept) {
    if (accept == null || accept.isBlank() || "*/*".equals(accept.trim())) {
      return List.of(MediaType.ALL);
    }
    return MediaType.parseMediaTypes(accept);
  }

  private static boolean accepts(String accept, MediaType target) {
    return parseAccept(accept).stream().anyMatch(m -> m.isCompatibleWith(target));
  }

  private static void writeTuple(
      TupleQuery tq, String accept, HttpServletResponse response, java.io.OutputStream out)
      throws Exception {
    if (accepts(accept, SPARQL_RESULTS_XML)) {
      response.setContentType(SPARQL_RESULTS_XML + ";charset=UTF-8");
      tq.evaluate(new SPARQLResultsXMLWriter(out));
    } else if (accepts(accept, SPARQL_RESULTS_CSV)) {
      response.setContentType(SPARQL_RESULTS_CSV + ";charset=UTF-8");
      tq.evaluate(new SPARQLResultsCSVWriter(out));
    } else if (accepts(accept, SPARQL_RESULTS_TSV)) {
      response.setContentType(SPARQL_RESULTS_TSV + ";charset=UTF-8");
      tq.evaluate(new SPARQLResultsTSVWriter(out));
    } else {
      response.setContentType(SPARQL_RESULTS_JSON + ";charset=UTF-8");
      tq.evaluate(new SPARQLResultsJSONWriter(out));
    }
  }

  private static void writeGraph(
      GraphQuery gq, String accept, HttpServletResponse response, java.io.OutputStream out)
      throws Exception {
    if (accepts(accept, RDF_XML)) {
      response.setContentType(RDF_XML + ";charset=UTF-8");
      gq.evaluate(new RDFXMLWriter(out));
    } else if (accepts(accept, N_TRIPLES)) {
      response.setContentType(N_TRIPLES + ";charset=UTF-8");
      gq.evaluate(new NTriplesWriter(out));
    } else {
      response.setContentType(TURTLE + ";charset=UTF-8");
      gq.evaluate(new TurtleWriter(out));
    }
  }

  private static void writeBoolean(
      BooleanQuery bq, String accept, HttpServletResponse response, java.io.OutputStream out)
      throws Exception {
    boolean value = bq.evaluate();
    if (accepts(accept, SPARQL_RESULTS_XML)) {
      response.setContentType(SPARQL_RESULTS_XML + ";charset=UTF-8");
      new SPARQLResultsXMLWriter(out).handleBoolean(value);
    } else {
      response.setContentType(SPARQL_RESULTS_JSON + ";charset=UTF-8");
      new SPARQLResultsJSONWriter(out).handleBoolean(value);
    }
  }

  private static void respondError(HttpServletResponse response, int status, String message)
      throws java.io.IOException {
    if (response.isCommitted()) {
      LOG.warn(
          "SPARQL response already committed; cannot report error ({} {}): {}", status, message);
      return;
    }
    response.reset();
    response.setStatus(status);
    response.setContentType("text/plain;charset=UTF-8");
    response.getOutputStream().write(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static String abbreviate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }
}
