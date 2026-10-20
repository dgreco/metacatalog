package it.davidgreco.metacatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.bootstrap.ImmutableModelInstaller;
import java.io.IOException;
import java.io.UncheckedIOException;
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

@Getter
@RequiredArgsConstructor
class CommonServiceTestingSupport {

  // Reused singleton container across all test classes. Started once and intentionally never
  // stopped per class: stopping it in @AfterAll would kill the DB while Spring's cached
  // ApplicationContext (and its Hikari pool) still points at the old mapped port, breaking
  // subsequent test classes. The container is cleaned up on JVM shutdown.
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

  @BeforeAll
  static void beforeAll() throws Exception {
    if (!postgres.isRunning()) {
      postgres.start();
    }
    // Reset schema/data before each test class (container stays up; only the DB is cleaned).
    // The clean takes an ACCESS EXCLUSIVE lock on every table, and a previous class's cached
    // Spring context is still alive: its scheduled mapping updater holds table locks from an
    // outer transaction while waiting, in the JVM, on REQUIRES_NEW inner transactions — a wait
    // PostgreSQL's deadlock detector cannot see, so an unbounded clean can hang behind it until
    // the connection dies. A lock_timeout on the clean's session breaks the cycle instead:
    // giving up releases the clean's own exclusive locks, the updater's inner transaction
    // proceeds, its outer one commits, and the retry finds the tables free.
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
   * Restores what {@link #beforeAll} wiped, for a Spring context that outlives the wipe.
   *
   * <p>{@code flyway.clean()} empties the database, but the {@link ApplicationContext} is cached
   * and shared across test classes, so the built-in traits are gone and {@link
   * ImmutableModelInstaller} is an {@code ApplicationRunner}: it ran once when the context was
   * built and will not run again on its own.
   *
   * <p>Runs per test method rather than per class because it needs the injected context, which does
   * not exist yet in the static {@code @BeforeAll}. That is cheap: after the first call the
   * installer is five existence checks that find everything already in place.
   */
  @BeforeEach
  void reinstallBuiltInModel() {
    applicationContext.getBean(ImmutableModelInstaller.class).run(null);
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private final ApplicationContext applicationContext;

  /**
   * Reads a JSON document from the test classpath, such as a schema under {@code jsons/}.
   *
   * @param path the resource path
   * @return the parsed document
   */
  static JsonNode schemaResource(String path) {
    try (var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
