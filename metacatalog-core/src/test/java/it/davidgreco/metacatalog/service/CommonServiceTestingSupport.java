package it.davidgreco.metacatalog.service;

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
}
