package it.witboost.dataplatformshaper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import it.witboost.dataplatformshaper.entity.TypedEntity;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import it.witboost.dataplatformshaper.repository.TypedEntityRepository;
import it.witboost.dataplatformshaper.service.EntityService;
import it.witboost.dataplatformshaper.service.EntityTypeService;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@EnableTransactionManagement
class ApplicationTests {

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
    TypedEntityRepository typedEntityRepository;

    @Test
    void testDatabase1() throws JsonProcessingException {
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository);

        final EntityService entityService = new EntityService(entityTypeRepository, typedEntityRepository);

        String jsonString1 = "{\"k1\":\"v1\",\"k2\":\"v2\"}";

        String jsonString2 = "{\"k1\":\"v1\",\"k2\":\"v2\"}";

        entityTypeService.create("test1", jsonString1, Optional.empty());

        entityTypeService.create("test2", jsonString1, Optional.of("test1"));

        entityTypeService.create("test3", jsonString1, Optional.of("test2"));

        var entityType3 = entityTypeService.read("test3");

        TypedEntity typedEntity = entityService.create("test3", jsonString2);

        System.out.println(typedEntity);
    }
}
