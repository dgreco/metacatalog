package it.davidgreco.metacatalog.functions.provisioning.tasks;

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

@Getter
@RequiredArgsConstructor
public class CommonServiceTestingSupport {

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
  }

  /**
   * Restores what {@link #beforeAll} wiped — the built-in traits the provisioning procedure keys
   * off.
   *
   * <p>Mirrors the core module's support class of the same name. With a single test class here the
   * context is built after the wipe and {@link ImmutableModelInstaller} runs on its own, so this is
   * currently a no-op; it is present so that adding a second test class does not silently start
   * from a database missing the built-in model.
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
}
