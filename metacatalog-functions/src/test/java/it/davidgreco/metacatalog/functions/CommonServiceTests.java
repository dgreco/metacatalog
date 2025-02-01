package it.davidgreco.metacatalog.functions;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import it.davidgreco.metacatalog.repository.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

@EnableCaching
public class CommonServiceTests {

  static final int POSTGRESQL_PORT = 5433;

  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
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

  @Autowired public ApplicationContext applicationContext;

  @Autowired public EntityTypeRepository entityTypeRepository;

  @Autowired public EntityRepository entityRepository;

  @Autowired public TraitRepository traitRepository;

  @Autowired public TraitRelationshipRepository traitRelationshipRepository;

  @Autowired public EntityRelationshipRepository entityRelationshipRepository;

  @Autowired public MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  @Autowired public MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  @Autowired public EntityLifeCycleEventRepository entityLifeCycleEventRepository;

  @Autowired public PlatformTransactionManager transactionManager;

  @Autowired public CacheManager cacheManager;

  @Test
  void dummyTest() {
    /* Just a dummy test */
    Assertions.assertTrue(true);
  }
}
