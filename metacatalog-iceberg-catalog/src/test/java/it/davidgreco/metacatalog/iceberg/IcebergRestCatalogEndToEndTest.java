package it.davidgreco.metacatalog.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.IcebergCatalogApplication;
import it.davidgreco.metacatalog.service.EntityService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the real application against a Testcontainers PostgreSQL and drives it with Apache
 * Iceberg's own {@link RESTCatalog} client over HTTP against a temp-dir {@code file://} warehouse
 * served by {@link LocalFileIO} — the proof that the server speaks the actual REST catalog
 * protocol, prefix negotiation, commit semantics and error contract included.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IcebergRestCatalogEndToEndTest {

  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4");
  static ConfigurableApplicationContext context;
  static Path warehouse;
  static RESTCatalog catalog;

  static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()));

  @BeforeAll
  static void beforeAll() throws IOException {
    postgres.start();
    var flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();

    warehouse = Files.createTempDirectory("iceberg-e2e-warehouse");
    context =
        SpringApplication.run(
            IcebergCatalogApplication.class,
            "--server.port=0",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--application.config.iceberg.warehouse=" + warehouse.toUri());

    int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    catalog = new RESTCatalog();
    // The client discovers the prefix and warehouse from GET /v1/config on initialize.
    catalog.initialize(
        "e2e",
        Map.of(
            CatalogProperties.URI,
            "http://localhost:" + port,
            CatalogProperties.FILE_IO_IMPL,
            LocalFileIO.class.getName()));
  }

  @AfterAll
  static void afterAll() throws Exception {
    if (catalog != null) {
      catalog.close();
    }
    if (context != null) {
      context.close();
    }
    postgres.stop();
    if (warehouse != null) {
      try (Stream<Path> paths = Files.walk(warehouse)) {
        paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
      }
    }
  }

  @Test
  @Order(1)
  void namespaces() {
    // A mutable map: the client's request builder probes containsKey(null), which the
    // immutable Map.of collections reject with an NPE.
    catalog.createNamespace(Namespace.of("db"), new java.util.HashMap<>(Map.of("owner", "e2e")));
    assertTrue(catalog.namespaceExists(Namespace.of("db")));
    assertEquals("e2e", catalog.loadNamespaceMetadata(Namespace.of("db")).get("owner"));
    assertThrows(AlreadyExistsException.class, () -> catalog.createNamespace(Namespace.of("db")));

    catalog.createNamespace(Namespace.of("db", "nested"));
    assertEquals(List.of(Namespace.of("db", "nested")), catalog.listNamespaces(Namespace.of("db")));
    assertThrows(
        NoSuchNamespaceException.class, () -> catalog.listNamespaces(Namespace.of("missing")));
  }

  @Test
  @Order(2)
  void createLoadAndCommit() {
    var identifier = TableIdentifier.of("db", "events");
    var table = catalog.createTable(identifier, SCHEMA, PartitionSpec.unpartitioned());
    assertEquals(SCHEMA.asStruct(), table.schema().asStruct());
    assertThrows(
        AlreadyExistsException.class,
        () -> catalog.createTable(identifier, SCHEMA, PartitionSpec.unpartitioned()));

    // A metadata file must have landed in the warehouse.
    assertTrue(table.location().startsWith(warehouse.toUri().toString().replaceAll("/$", "")));
    try (Stream<Path> files = walk(warehouse)) {
      assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".metadata.json")));
    }

    // Two server-side commits through the protocol: properties, then a schema change.
    table.updateProperties().set("purpose", "e2e").commit();
    table.updateSchema().addColumn("category", Types.StringType.get()).commit();

    var reloaded = catalog.loadTable(identifier);
    assertEquals("e2e", reloaded.properties().get("purpose"));
    assertEquals(3, reloaded.schema().columns().size());

    // Both surface in the metacatalog registry: entity values track the current metadata.
    var entityService = context.getBean(EntityService.class);
    var tableEntities = entityService.list(IcebergModel.TABLE_TYPE, "$ ? (@.name == \"events\")");
    assertEquals(1, tableEntities.size());
    var values = tableEntities.getFirst().getValues();
    assertTrue(values.get("metadataLocation").asText().endsWith(".metadata.json"));
    assertEquals("e2e", values.get("metadata").get("properties").get("purpose").asText());
  }

  @Test
  @Order(3)
  void listRenameDrop() {
    var identifier = TableIdentifier.of("db", "events");
    var renamed = TableIdentifier.of("db", "events_v2");
    assertEquals(List.of(identifier), catalog.listTables(Namespace.of("db")));

    catalog.renameTable(identifier, renamed);
    assertFalse(catalog.tableExists(identifier));
    assertTrue(catalog.tableExists(renamed));
    assertThrows(NoSuchTableException.class, () -> catalog.loadTable(identifier));

    assertTrue(catalog.dropTable(renamed, false));
    assertFalse(catalog.tableExists(renamed));
    assertFalse(catalog.dropTable(renamed, false));

    assertTrue(catalog.dropNamespace(Namespace.of("db", "nested")));
    assertTrue(catalog.dropNamespace(Namespace.of("db")));
  }

  private static Stream<Path> walk(Path root) {
    try {
      return Files.walk(root);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
