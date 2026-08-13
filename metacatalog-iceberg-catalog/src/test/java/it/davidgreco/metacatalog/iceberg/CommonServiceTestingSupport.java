package it.davidgreco.metacatalog.iceberg;

import it.davidgreco.metacatalog.bootstrap.ImmutableModelInstaller;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@EnableCaching
@Getter
@RequiredArgsConstructor
public class CommonServiceTestingSupport {

  // Reused singleton container across all test classes. Started once and intentionally never
  // stopped per class: stopping it in @AfterAll would kill the DB while Spring's cached
  // ApplicationContext (and its Hikari pool) still points at the old mapped port, breaking
  // subsequent test classes. The container is cleaned up on JVM shutdown.
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4");

  @BeforeAll
  static void beforeAll() {
    if (!postgres.isRunning()) {
      postgres.start();
    }
    // Reset schema/data before each test class (container stays up; only the DB is cleaned).
    var flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();
  }

  /**
   * Restores what {@link #beforeAll} wiped — the built-in traits plus this module's Iceberg model —
   * and clears the caches still holding their pre-wipe ids. Mirrors the support class of the same
   * name in core and the functions modules.
   */
  @BeforeEach
  void reinstallBuiltInModel() {
    var cacheManager = applicationContext.getBean(CacheManager.class);
    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
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
