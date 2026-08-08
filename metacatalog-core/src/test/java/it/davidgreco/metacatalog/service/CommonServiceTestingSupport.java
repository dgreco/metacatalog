package it.davidgreco.metacatalog.service;

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
class CommonServiceTestingSupport {

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
   * Restores what {@link #beforeAll} wiped, for a Spring context that outlives the wipe.
   *
   * <p>{@code flyway.clean()} empties the database, but the {@link ApplicationContext} is cached
   * and shared across test classes, so two things survive the wipe and have to be dealt with here:
   *
   * <ul>
   *   <li>The Caffeine caches behind {@code TraitRepository.findByName} and its entity-type
   *       counterpart still hold rows that no longer exist. Left alone, a cache hit would hand a
   *       later test a trait whose id has been deleted, and the insert into {@code type_traits}
   *       would fail on the foreign key. This did not bite while the built-in traits were seeded by
   *       Flyway at fixed UUIDs — the wipe-and-migrate put the very same ids back — but they are
   *       created at runtime now, with fresh ids each time.
   *   <li>The built-in traits themselves are gone, and {@link ImmutableModelInstaller} is an {@code
   *       ApplicationRunner}: it ran once when the context was built and will not run again.
   * </ul>
   *
   * <p>Runs per test method rather than per class because it needs the injected context, which does
   * not exist yet in the static {@code @BeforeAll}. That is cheap: after the first call the
   * installer is five existence checks that find everything already in place.
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
