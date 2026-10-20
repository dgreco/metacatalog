package it.davidgreco.metacatalog.sparql;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end integration test for the SPARQL module: boots the Spring context with the real Ontop
 * repository bean against a Testcontainers Postgres running the metacatalog Flyway migrations,
 * inserts a row, and exercises the {@code /sparql/query} endpoint over SELECT and ASK.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SparqlEndpointIntegrationTest {

  // Reused singleton container (started once, cleaned on JVM shutdown).
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

  @BeforeAll
  static void beforeAll() {
    if (!postgres.isRunning()) {
      postgres.start();
    }
    var flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .cleanDisabled(false)
            .locations("classpath:db/migration")
            .load();
    flyway.clean();
    flyway.migrate();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.flyway.enabled", () -> "false"); // migrated manually above
    registry.add(
        "application.sparql.default-query",
        () -> "PREFIX mt: <http://metacatalog/>\nSELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 50");
  }

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void selectReturnsTraits() throws Exception {
    jdbcTemplate.update(
        "INSERT INTO trait (id, name, base_schema, derived_schema, version, version_group_id) "
            + "VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?)",
        java.util.UUID.randomUUID().toString(),
        "TestTrait",
        "{}",
        "{}",
        1,
        java.util.UUID.randomUUID().toString());

    String sparql =
        "PREFIX mt: <http://metacatalog/> "
            + "SELECT ?name WHERE { ?s a mt:Trait ; mt:name ?name . }";

    MvcResult result =
        mockMvc
            .perform(
                get("/sparql/query")
                    .param("query", sparql)
                    .accept("application/sparql-results+json"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/sparql-results+json"))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assert body.contains("TestTrait") : "Expected 'TestTrait' in SPARQL results, got: " + body;
  }

  /**
   * Entity history is exposed as {@code mt:EntityVersion}: each version points at its entity
   * ({@code mt:versionOf}), at the type version it was validated against ({@code
   * mt:pinnedToVersion}) and at its predecessor ({@code mt:hasPreviousVersion}), and the entity
   * carries its current version number.
   */
  @Test
  void selectWalksEntityVersionHistory() throws Exception {
    var typeId = java.util.UUID.randomUUID().toString();
    var typeVersionId = java.util.UUID.randomUUID().toString();
    var entityId = java.util.UUID.randomUUID().toString();
    var firstId = java.util.UUID.randomUUID().toString();
    var secondId = java.util.UUID.randomUUID().toString();
    var group = java.util.UUID.randomUUID().toString();
    jdbcTemplate.update(
        "INSERT INTO entity_type (id, name, base_schema, version, version_group_id) "
            + "VALUES (?, 'SparqlVersioned', '{}'::jsonb, 1, ?)",
        typeId,
        group);
    jdbcTemplate.update(
        "INSERT INTO entity_type_version (id, version_group_id, version, name, base_schema, traits,"
            + " created_at) VALUES (?, ?, 1, 'SparqlVersioned', '{}'::jsonb, '[]'::jsonb, now())",
        typeVersionId,
        group);
    jdbcTemplate.update(
        "INSERT INTO entity (id, values, entity_type_id, entity_type_version_id, version) "
            + "VALUES (?, '{\"name\":\"second\"}'::jsonb, ?, ?, 2)",
        entityId,
        typeId,
        typeVersionId);
    var insertVersion =
        "INSERT INTO entity_version (id, entity_id, version, values, entity_type_version_id,"
            + " previous_version_id, created_at) VALUES (?, ?, ?, ?::jsonb, ?, ?, now())";
    jdbcTemplate.update(
        insertVersion, firstId, entityId, 1, "{\"name\":\"first\"}", typeVersionId, null);
    jdbcTemplate.update(
        insertVersion, secondId, entityId, 2, "{\"name\":\"second\"}", typeVersionId, firstId);

    String sparql =
        "PREFIX mt: <http://metacatalog/> "
            + "SELECT ?current ?values ?previousValues WHERE { "
            + "  ?v a mt:EntityVersion ; mt:versionOf ?e ; mt:values ?values ; "
            + "     mt:pinnedToVersion ?tv ; mt:hasPreviousVersion ?p . "
            + "  ?p mt:values ?previousValues . "
            + "  ?e mt:id \""
            + entityId
            + "\" ; mt:version ?current . "
            + "  ?tv a mt:EntityTypeVersion . }";

    String body =
        mockMvc
            .perform(
                get("/sparql/query")
                    .param("query", sparql)
                    .accept("application/sparql-results+json"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assert body.contains("second") && body.contains("first") && body.contains("\"2\"")
        : "Expected the second version, its predecessor and the current version, got: " + body;
  }

  @Test
  void askViaPostSparqlQueryBody() throws Exception {
    jdbcTemplate.update(
        "INSERT INTO trait (id, name, base_schema, derived_schema, version, version_group_id) "
            + "VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?)",
        java.util.UUID.randomUUID().toString(),
        "AskTrait",
        "{}",
        "{}",
        1,
        java.util.UUID.randomUUID().toString());

    String ask = "PREFIX mt: <http://metacatalog/> ASK { ?s a mt:Trait }";

    mockMvc
        .perform(
            post("/sparql/query")
                .contentType("application/sparql-query")
                .content(ask)
                .accept("application/sparql-results+json"))
        .andExpect(status().isOk());
  }

  @Test
  void missingQueryParameterReturns400() throws Exception {
    mockMvc
        .perform(get("/sparql/query"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Missing 'query'")));
  }

  @Test
  void malformedQueryReturns400() throws Exception {
    mockMvc
        .perform(get("/sparql/query").param("query", "this is not sparql"))
        .andExpect(status().isBadRequest())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Malformed SPARQL query")));
  }

  @Test
  void emptyPostBodyReturns400() throws Exception {
    mockMvc
        .perform(post("/sparql/query").contentType("application/sparql-query").content(""))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Empty SPARQL query")));
  }
}
