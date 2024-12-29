package it.witboost.dataplatformshaper.service;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import it.witboost.dataplatformshaper.repository.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

class CommonServiceTests {

    static final int POSTGRESQL_PORT = 5433;

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(new HostConfig()
                    .withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(POSTGRESQL_PORT), new ExposedPort(5432)))));
    ;

    @BeforeAll
    static void beforeAll() {
        postgres.start();

        var flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    static void afterAll() throws InterruptedException {
        // Thread.sleep(200000);
        postgres.stop();
    }

    @Autowired
    EntityTypeRepository entityTypeRepository;

    @Autowired
    EntityRepository entityRepository;

    @Autowired
    TraitRepository traitRepository;

    @Autowired
    TraitRelationshipRepository traitRelationshipRepository;

    @Autowired
    EntityRelationshipRepository entityRelationshipRepository;

    @Autowired
    MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

    @Autowired
    MappingEntityRelationshipRepository mappingEntityRelationshipRepository;
}
