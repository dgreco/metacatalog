package it.davidgreco.metacatalog.service;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

@EnableCaching
@Getter
@RequiredArgsConstructor
class CommonServiceTestingSupport {

  static final int POSTGRESQL_PORT = 5433;

  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:18.1")
          .withExposedPorts(5432)
          .withCreateContainerCmdModifier(
              cmd ->
                  cmd.withHostConfig(
                      new HostConfig()
                          .withPortBindings(
                              new PortBinding(
                                  Ports.Binding.bindPort(POSTGRESQL_PORT),
                                  new ExposedPort(5432)))));

  @BeforeAll
  static void beforeAll() {
    postgres.start();

    var flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();
  }

  @AfterAll
  static void afterAll() {
    postgres.stop();
  }

  private final ApplicationContext applicationContext;
}
