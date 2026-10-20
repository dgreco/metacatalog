package it.davidgreco.metacatalog.hive;

import it.davidgreco.metacatalog.bootstrap.ImmutableModelInstaller;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Mirrors the same-named class in core, the functions modules and the Iceberg catalog. */
@Getter
@RequiredArgsConstructor
public class CommonServiceTestingSupport {

  // Reused singleton container across all test classes. Started once and intentionally never
  // stopped per class: stopping it in @AfterAll would kill the DB while Spring's cached
  // ApplicationContext (and its Hikari pool) still points at the old mapped port, breaking
  // subsequent test classes. The container is cleaned up on JVM shutdown.
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

  /** Claimed once, before any context starts, so every test class in the module shares it. */
  static final int freePort = freePort();

  private static int freePort() {
    try (var socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("no free port for the test Thrift server", e);
    }
  }

  @BeforeAll
  static void beforeAll() throws Exception {
    if (!postgres.isRunning()) {
      postgres.start();
    }
    // A lock_timeout on the clean's session: the clean takes ACCESS EXCLUSIVE on every table, and
    // a previous class's cached context can hold table locks from an outer transaction while
    // waiting on REQUIRES_NEW inner ones — a wait PostgreSQL's deadlock detector cannot see.
    var url = postgres.getJdbcUrl();
    var lockTimeoutUrl = url + (url.contains("?") ? "&" : "?") + "options=-c%20lock_timeout%3D5000";
    var flyway =
        Flyway.configure()
            .dataSource(lockTimeoutUrl, postgres.getUsername(), postgres.getPassword())
            .cleanDisabled(false)
            .load();
    var sequences = lastSequenceValues();
    for (var attempt = 1; ; attempt++) {
      try {
        flyway.clean();
        break;
      } catch (FlywayException e) {
        if (attempt == 5) throw e;
        Thread.sleep(500);
      }
    }
    flyway.migrate();
    restoreSequenceValues(sequences);
  }

  /**
   * The last value of every sequence that has handed one out, read before the clean resets them.
   *
   * <p>Each cached Spring context keeps a Hibernate pooled optimizer that holds a block of ids it
   * fetched from a sequence, and keeps drawing on it across the clean. A sequence restarted at 1
   * hands the same ids out again — to the current class's context, and to any other context still
   * alive (one built for a class with its own configuration, whose scheduled mapping updater keeps
   * inserting lifecycle events into this database) — and two inserts collide on the primary key.
   */
  private static java.util.Map<String, Long> lastSequenceValues() throws Exception {
    var values = new java.util.LinkedHashMap<String, Long>();
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT sequencename, last_value FROM pg_sequences"
                        + " WHERE schemaname = current_schema() AND last_value IS NOT NULL")) {
      while (rows.next()) values.put(rows.getString(1), rows.getLong(2));
    }
    return values;
  }

  /**
   * Moves each re-created sequence past where its predecessor stopped, so every id it hands out
   * from now on is above any id a context's optimizer may still hold from before the clean.
   */
  private static void restoreSequenceValues(java.util.Map<String, Long> values) throws Exception {
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var setval = connection.prepareStatement("SELECT setval(?::regclass, ?)")) {
      for (var entry : values.entrySet()) {
        setval.setString(1, entry.getKey());
        setval.setLong(2, entry.getValue());
        setval.execute();
      }
    }
  }

  /**
   * Restores what {@link #beforeAll} wiped. The context is cached across test classes and {@link
   * ImmutableModelInstaller} is an {@code ApplicationRunner}, so it would not run again on its own.
   */
  @BeforeEach
  void reinstallModel() {
    applicationContext.getBean(ImmutableModelInstaller.class).run(null);
  }

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    // A free Thrift port. These tests drive the registry directly and never connect over Thrift,
    // but the server is a lifecycle bean and starts with the context — on the default 9083 it
    // fights anything already listening there, which a developer running `make up-hive-d`
    // certainly is, and the failure is a context-load error that says nothing about the test.
    // A concrete port rather than 0: HiveMetastoreProperties reads a non-positive port as "unset"
    // and substitutes the default, so 0 would come straight back as 9083.
    registry.add("application.config.hive.thrift-port", () -> freePort);
  }

  private final ApplicationContext applicationContext;
}
