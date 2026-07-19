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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * End-to-end integration test for the SPARQL module: boots the Spring context with the real Ontop
 * repository bean against a Testcontainers Postgres running the metacatalog Flyway migrations,
 * inserts a row, and exercises the {@code /sparql/query} endpoint over SELECT and ASK.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SparqlEndpointIntegrationTest {

  // Reused singleton container (started once, cleaned on JVM shutdown).
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.1");

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
}
