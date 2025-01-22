package it.davidgreco.metacatalog.openapi;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import it.davidgreco.metacatalog.Application;
import it.davidgreco.metacatalog.openapi.client.*;
import it.davidgreco.metacatalog.openapi.common.FileHttpMessageConverter;
import java.io.File;
import java.util.List;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public class MetacatalogApiTests {
    static final int POSTGRESQL_PORT = 5433;

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(new HostConfig()
                    .withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(POSTGRESQL_PORT), new ExposedPort(5432)))));

    static ConfigurableApplicationContext context;

    @Autowired
    private ServerProperties serverProperties;

    private MetaCatalogManagerApi getMetaCatalogManagerApi() {
        var restTemplate = new RestTemplate();
        var msgConverters = restTemplate.getMessageConverters();
        msgConverters.add(new FileHttpMessageConverter());
        restTemplate.setMessageConverters(msgConverters);
        var apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath("http://localhost:" + serverProperties.getPort());
        return new MetaCatalogManagerApi(apiClient);
    }

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
    }

    @AfterAll
    static void afterAll() {
        postgres.stop();
    }

    @Test
    void testCreateReadExistsDeleteTrait() {
        var api = getMetaCatalogManagerApi();
        var trait = new Trait();
        trait.setName("TestTrait");
        api.createTrait(trait);

        var retrievedTrait = api.getTrait("TestTrait");
        Assertions.assertEquals("TestTrait", retrievedTrait.getName());

        api.existsTrait("TestTrait");

        api.deleteTrait("TestTrait");

        var ex1 = Assertions.assertThrows(HttpClientErrorException.class, () -> api.getTrait("TestTrait"));

        Assertions.assertEquals(
                new ValidationError().errors(List.of("Trait TestTrait not found")),
                ex1.getResponseBodyAs(ValidationError.class));

        var ex2 = Assertions.assertThrows(HttpClientErrorException.class, () -> api.existsTrait("TestTrait"));

        Assertions.assertEquals(404, ex2.getStatusCode().value());
    }

    @Test
    void testLinkUnlinkTraitGetLink() {
        var api = getMetaCatalogManagerApi();
        var trait1 = new Trait();
        trait1.setName("TestTrait1");
        api.createTrait(trait1);

        var trait2 = new Trait();
        trait2.setName("TestTrait2");
        api.createTrait(trait2);

        var linkSpec = new LinkTraitRequest();
        linkSpec.sourceTrait("TestTrait1");
        linkSpec.targetTrait("TestTrait2");
        linkSpec.relationshipTypeName("DEPENDS_ON");
        api.linkTrait(linkSpec);

        api.existsLinkTrait("TestTrait1", "DEPENDS_ON", "TestTrait2");

        var traits1 = api.linkedTrait("TestTrait1", "DEPENDS_ON");
        Assertions.assertEquals(1, traits1.size());

        api.unlinkTrait("TestTrait1", "DEPENDS_ON", "TestTrait2");
        var traits2 = api.linkedTrait("TestTrait1", "DEPENDS_ON");
        Assertions.assertEquals(0, traits2.size());

        var ex = Assertions.assertThrows(
                HttpClientErrorException.class, () -> api.existsLinkTrait("TestTrait1", "DEPENDS_ON", "TestTrait2"));
        Assertions.assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void testCreateReadExistsDeleteType() {
        var api = getMetaCatalogManagerApi();
        var entityType = new EntityType();
        entityType.setName("TestType");
        entityType.setSchema("""
                { "type": "object", "properties": { } }""");
        api.createEntityType(entityType);

        var ex = Assertions.assertThrows(
                HttpClientErrorException.BadRequest.class, () -> api.createEntityType(entityType));

        var retrievedType = api.getEntityType("TestType");
        Assertions.assertEquals("TestType", retrievedType.getName());

        api.existsEntityType("TestType");

        api.deleteEntityType("TestType");

        var ex1 = Assertions.assertThrows(HttpClientErrorException.class, () -> api.getEntityType("TestType"));

        Assertions.assertEquals(
                new ValidationError().errors(List.of("EntityType TestType not found")),
                ex1.getResponseBodyAs(ValidationError.class));

        var ex2 = Assertions.assertThrows(HttpClientErrorException.class, () -> api.existsEntityType("TestType"));

        Assertions.assertEquals(404, ex2.getStatusCode().value());
    }

    @Test
    void testCreateReadExistsDeleteEntity() {
        var api = getMetaCatalogManagerApi();
        var entityType = new EntityType();
        entityType.setName("AnotherTestType");
        entityType.setSchema(
                """
                {
                  "type": "object",
                  "properties": {
                    "a": { "type": "string" }
                  }
                }""");
        api.createEntityType(entityType);

        var entity = new Entity();
        entity.setEntityType("AnotherTestType");
        entity.setValues("""
                {
                   "a": "b"
                }
                """);

        var id = api.createEntity(entity);

        var retrievedEntity = api.getEntity(id);

        Assertions.assertEquals("AnotherTestType", retrievedEntity.getEntityType());

        api.existsEntity(id);

        api.deleteEntity(id);

        var ex1 = Assertions.assertThrows(HttpClientErrorException.class, () -> api.getEntity(id));
        Assertions.assertTrue(Objects.requireNonNull(ex1.getResponseBodyAs(ValidationError.class))
                .getErrors()
                .getFirst()
                .contains("not found"));
    }

    @Test
    void bulkCreation() {
        var api = getMetaCatalogManagerApi();
        api.bulkCreation(new File("src/test/resources/bulk/bulk1.yaml"));
    }
}
