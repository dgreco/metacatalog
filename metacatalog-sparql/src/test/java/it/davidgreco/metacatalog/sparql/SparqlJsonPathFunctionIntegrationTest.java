package it.davidgreco.metacatalog.sparql;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.unibz.inf.ontop.rdf4j.repository.OntopRepositoryConnection;
import it.unibz.inf.ontop.rdf4j.repository.impl.OntopVirtualRepository;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration test for the {@code mtfn:jsonPathExists} SPARQL extension function: a SQL/JSON path
 * expression written in the SPARQL query, pushed down by Ontop to PostgreSQL's {@code
 * jsonb_path_exists}. Boots the real Ontop repository (with {@code
 * MetacatalogFunctionSymbolFactory} substituted by {@code OntopRepositoryConfig}) against a
 * Testcontainers Postgres running the metacatalog Flyway baseline.
 *
 * <p>The data models the Iceberg registry shape: table entities containing schema entities via
 * {@code HAS_PART}, the schema's {@code values} holding a {@code columns} array. One schema has a
 * {@code vendor} column, the other does not — so a correct answer requires the path predicate to
 * actually discriminate.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SparqlJsonPathFunctionIntegrationTest {

  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4");

  static String tripsTableId;
  static String zonesTableId;

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
  @Autowired OntopVirtualRepository ontopRepository;

  /** Two tables, each containing one schema entity; only the first schema has a vendor column. */
  private void seedModel() {
    if (tripsTableId != null) {
      return; // the class's tests share one database
    }
    var tableTypeId = insertEntityType("IcebergTable");
    var schemaTypeId = insertEntityType("IcebergTableSchema");
    var tableVersionId = insertEntityTypeVersion(tableTypeId, "IcebergTable");
    var schemaVersionId = insertEntityTypeVersion(schemaTypeId, "IcebergTableSchema");

    tripsTableId =
        insertEntity(
            tableTypeId, tableVersionId, "{\"name\": \"trips\", \"namespaceKey\": \"demo\"}");
    zonesTableId =
        insertEntity(
            tableTypeId, tableVersionId, "{\"name\": \"zones\", \"namespaceKey\": \"demo\"}");
    var tripsSchemaId =
        insertEntity(
            schemaTypeId,
            schemaVersionId,
            "{\"schemaId\": 0, \"columns\": ["
                + "{\"id\": 1, \"name\": \"vendor\", \"type\": \"string\", \"required\": false},"
                + "{\"id\": 2, \"name\": \"amount\", \"type\": \"double\", \"required\": true}]}");
    var zonesSchemaId =
        insertEntity(
            schemaTypeId,
            schemaVersionId,
            "{\"schemaId\": 0, \"columns\": ["
                + "{\"id\": 1, \"name\": \"city\", \"type\": \"string\", \"required\": false}]}");

    insertHasPart(tripsTableId, tripsSchemaId);
    insertHasPart(zonesTableId, zonesSchemaId);
  }

  private String insertEntityType(String name) {
    var id = UUID.randomUUID().toString();
    jdbcTemplate.update(
        "INSERT INTO entity_type (id, name, base_schema, derived_schema, version, version_group_id)"
            + " VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?)",
        id,
        name,
        "{}",
        "{}",
        1,
        UUID.randomUUID().toString());
    return id;
  }

  private String insertEntityTypeVersion(String typeId, String name) {
    var id = UUID.randomUUID().toString();
    var groupId =
        jdbcTemplate.queryForObject(
            "SELECT version_group_id FROM entity_type WHERE id = ?", String.class, typeId);
    jdbcTemplate.update(
        "INSERT INTO entity_type_version (id, version_group_id, version, name, base_schema,"
            + " traits, created_at) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, NOW())",
        id,
        groupId,
        1,
        name,
        "{}",
        "[]");
    return id;
  }

  private String insertEntity(String typeId, String versionId, String valuesJson) {
    var id = UUID.randomUUID().toString();
    jdbcTemplate.update(
        "INSERT INTO entity (id, \"values\", entity_type_id, entity_type_version_id)"
            + " VALUES (?, ?::jsonb, ?, ?)",
        id,
        valuesJson,
        typeId,
        versionId);
    return id;
  }

  private void insertHasPart(String sourceId, String targetId) {
    jdbcTemplate.update(
        "INSERT INTO entity_relationship (id, source_id, target_id, relation_type)"
            + " VALUES (?, ?, ?, 'HAS_PART')",
        UUID.randomUUID().toString(),
        sourceId,
        targetId);
  }

  private static String vendorQuery(String jsonPath) {
    return """
        PREFIX mt: <http://metacatalog/>
        PREFIX mtfn: <http://metacatalog/fn#>
        SELECT ?table WHERE {
          ?table mt:hasEntityType ?tableType .
          ?tableType mt:name "IcebergTable" .
          ?table mt:hasPart ?schema .
          ?schema mt:hasEntityType ?schemaType .
          ?schemaType mt:name "IcebergTableSchema" .
          ?schema mt:values ?v .
          FILTER(mtfn:jsonPathExists(?v, '%s'))
        }
        """
        .formatted(jsonPath);
  }

  private String runQuery(String sparql) throws Exception {
    return mockMvc
        .perform(
            get("/sparql/query").param("query", sparql).accept("application/sparql-results+json"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * The path predicate discriminates by column name: only the table whose schema has a {@code
   * vendor} column is returned. The excluded table proves the function is not a tautology.
   */
  @Test
  void jsonPathExistsFiltersByColumnName() throws Exception {
    seedModel();
    var body = runQuery(vendorQuery("$.columns[*] ? (@.name == \"vendor\")"));
    Assertions.assertTrue(body.contains(tripsTableId), body);
    Assertions.assertFalse(body.contains(zonesTableId), body);
  }

  /**
   * A numeric jsonpath comparison — something no textual regex over the JSON could express — only
   * holds for the schema with a column id greater than 1, proving the predicate is evaluated as a
   * real SQL/JSON path by PostgreSQL, not approximated.
   */
  @Test
  void jsonPathExistsEvaluatesNumericPredicates() throws Exception {
    seedModel();
    var body = runQuery(vendorQuery("$.columns[*] ? (@.id > 1)"));
    Assertions.assertTrue(body.contains(tripsTableId), body);
    Assertions.assertFalse(body.contains(zonesTableId), body);
  }

  /**
   * The pushdown itself, asserted literally: Ontop's reformulation of the SPARQL query must place
   * {@code jsonb_path_exists} with both casts into the native SQL, so the path expression is
   * executed by PostgreSQL rather than anywhere else.
   */
  @Test
  void reformulatedSqlContainsJsonbPathExists() {
    seedModel();
    try (OntopRepositoryConnection connection = ontopRepository.getConnection()) {
      var sql =
          connection.reformulateIntoNativeQuery(
              vendorQuery("$.columns[*] ? (@.name == \"vendor\")"));
      Assertions.assertTrue(sql.contains("jsonb_path_exists(CAST("), sql);
      Assertions.assertTrue(sql.contains("AS jsonpath"), sql);
    }
  }
}
