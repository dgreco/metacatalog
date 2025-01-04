package it.davidgreco.metacatalog.api;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import it.davidgreco.metacatalog.Application;
import it.davidgreco.metacatalog.openapi.client.MetaCatalogManagerApi;
import it.davidgreco.metacatalog.openapi.client.Trait;
import it.davidgreco.metacatalog.openapi.client.ValidationError;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.PostgreSQLContainer;

public class MetacatalogApiTests {
    static final int POSTGRESQL_PORT = 5433;

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(new HostConfig()
                    .withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(POSTGRESQL_PORT), new ExposedPort(5432)))));

    static MetaCatalogManagerApi api;

    static ConfigurableApplicationContext context;

    @BeforeAll
    static void beforeAll() {
        postgres.start();

        var flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        context = SpringApplication.run(Application.class);
        api = new MetaCatalogManagerApi();
    }

    @AfterAll
    static void afterAll() throws InterruptedException {
        postgres.stop();
    }

    @Test
    void testCreateReadExistsDeleteTrait() {
        var trait = new Trait();
        trait.setName("TestTrait");
        api.createTrait(trait);

        var retrievedTrait = api.getTrait("TestTrait");
        Assertions.assertEquals("TestTrait", retrievedTrait.getName());

        api.traitExists("TestTrait");

        api.deleteTrait("TestTrait");

        var ex1 = Assertions.assertThrows(HttpClientErrorException.class, () -> api.getTrait("TestTrait"));

        Assertions.assertEquals(
                new ValidationError().errors(List.of("Trait TestTrait not found")),
                ex1.getResponseBodyAs(ValidationError.class));

        var ex2 = Assertions.assertThrows(HttpClientErrorException.class, () -> api.traitExists("TestTrait"));

        Assertions.assertEquals(404, ex2.getStatusCode().value());
    }
}
