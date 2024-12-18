package it.witboost.dataplatformshaper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
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

    @Test
    void testDatabase2() throws JsonProcessingException {
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository);

        final EntityService entityService = new EntityService(entityTypeRepository, typedEntityRepository);

        var inheritedSchema =
                """
             {"$$id" : "derived_https://example.com/leaf.schema.json",
             "$$schema" : "https://json-schema.org/draft/2019-09/schema#",
             "allOf" : [ {
               "$$id" : "https://example.com/base.schema.json",
               "$$schema" : "https://json-schema.org/draft/2019-09/schema#",
               "type" : "object",
               "properties" : {
                 "street_address" : {
                   "type" : "string"
                 },
                 "city" : {
                   "type" : "string"
                 },
                 "state" : {
                   "type" : "string"
                 }
               },
               "required" : [ "street_address", "city", "state" ]
             }, {
               "$$id" : "https://example.com/middle.schema.json",
               "$$schema" : "https://json-schema.org/draft/2019-09/schema#",
               "type" : "object",
               "properties" : {
                 "type" : {
                   "enum" : [ "residential", "business" ]
                 }
               },
               "required" : [ "type" ]
             }, {
               "$$id" : "https://example.com/leaf.schema.json",
               "$$schema" : "https://json-schema.org/draft/2019-09/schema#",
               "type" : "object",
               "properties" : {
                 "another_property" : {
                   "type" : "string"
                 }
               },
               "required" : [ "another_property" ]
             } ],
             "properties" : {
               "street_address" : true,
               "city" : true,
               "state" : true,
               "type" : true,
               "another_property" : true
             },
             "additionalProperties" : false
           }
           """;

        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4);

        var baseSchema = factory.getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/base_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        var middleSchema = factory.getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/middle_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        var leafSchema = factory.getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/leaf_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        entityTypeService.create("BaseType", baseSchema, Optional.empty());

        entityTypeService.create("MiddleType", middleSchema, Optional.of("BaseType"));

        var leafType = entityTypeService.create("LeafType", leafSchema, Optional.of("MiddleType"));

        System.out.println(entityTypeService.generatedDerivedSchema(leafType).toPrettyString());
    }
}
