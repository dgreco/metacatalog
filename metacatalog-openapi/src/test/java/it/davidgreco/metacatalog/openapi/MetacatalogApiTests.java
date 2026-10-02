package it.davidgreco.metacatalog.openapi;

import static org.awaitility.Awaitility.await;

import it.davidgreco.metacatalog.Application;
import it.davidgreco.metacatalog.openapi.client.*;
import it.davidgreco.metacatalog.openapi.common.FileHttpMessageConverter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.awaitility.Durations;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@RequiredArgsConstructor
class MetacatalogApiTests {
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  static ConfigurableApplicationContext context;

  private MetaCatalogManagerApi getMetaCatalogManagerApi() {
    var restTemplate = new RestTemplate();
    var msgConverters = restTemplate.getMessageConverters();
    msgConverters.add(new FileHttpMessageConverter());
    restTemplate.setMessageConverters(msgConverters);
    var apiClient = new ApiClient(restTemplate);
    apiClient.setBasePath("http://localhost:" + boundPort());
    return new MetaCatalogManagerApi(apiClient);
  }

  /**
   * The port the application under test actually bound. Configured as 0, so it is only known once
   * the server is up — asking the configuration for it would just give back the 0.
   */
  private static int boundPort() {
    return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
  }

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

    context =
        SpringApplication.run(
            Application.class,
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword());
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

    var ex1 =
        Assertions.assertThrows(HttpClientErrorException.class, () -> api.getTrait("TestTrait"));

    Assertions.assertEquals(
        new ValidationError().errors(List.of("Trait TestTrait not found")),
        ex1.getResponseBodyAs(ValidationError.class));

    var ex2 =
        Assertions.assertThrows(HttpClientErrorException.class, () -> api.existsTrait("TestTrait"));

    Assertions.assertEquals(404, ex2.getStatusCode().value());
  }

  /**
   * Round-trips a mapping through the API: the read side exposes the type versions the mapping was
   * defined against (stamped by the service, absent from the create payload), and the
   * create-or-replace contract keeps a single mapping per (source, target) pair with a stable id.
   */
  @Test
  void testMappingRoundTripExposesVersionStampsAndReplaces() {
    var api = getMetaCatalogManagerApi();

    var src = new EntityType();
    src.setName("ApiMapSrc");
    src.setSchema("{ \"type\": \"object\", \"properties\": { \"a\": { \"type\": \"integer\" } } }");
    api.createEntityType(src);

    var dst = new EntityType();
    dst.setName("ApiMapDst");
    dst.setSchema("{ \"type\": \"object\", \"properties\": { \"b\": { \"type\": \"integer\" } } }");
    api.createEntityType(dst);

    var mapping = new Mapping();
    mapping.setSourceEntityType("ApiMapSrc");
    mapping.setTargetEntityType("ApiMapDst");
    mapping.setMappingValues("{\"b\": \"#source.getValue('$.a').intValue()\"}");
    mapping.setEntityPathReferences("[]");
    api.createMapping(mapping);

    var mine =
        api.listMappings().stream()
            .filter(m -> "ApiMapSrc".equals(m.getSourceEntityType()))
            .toList();
    Assertions.assertEquals(1, mine.size());
    var created = mine.getFirst();
    Assertions.assertEquals(1, created.getSourceEntityTypeVersion());
    Assertions.assertEquals(1, created.getTargetEntityTypeVersion());

    // Creating the pair again replaces the mapping in place — same id, still exactly one.
    api.createMapping(mapping);
    var afterReplace =
        api.listMappings().stream()
            .filter(m -> "ApiMapSrc".equals(m.getSourceEntityType()))
            .toList();
    Assertions.assertEquals(1, afterReplace.size());
    Assertions.assertEquals(created.getId(), afterReplace.getFirst().getId());

    api.deleteMapping(created.getId());
    api.deleteEntityType("ApiMapSrc");
    api.deleteEntityType("ApiMapDst");
  }

  @Test
  void testListTraits() {
    var api = getMetaCatalogManagerApi();

    var trait1 = new Trait();
    trait1.setName("ListTestTrait1");
    api.createTrait(trait1);

    var trait2 = new Trait();
    trait2.setName("ListTestTrait2");
    api.createTrait(trait2);

    var trait3 = new Trait();
    trait3.setName("ListTestTrait3");
    api.createTrait(trait3);

    var allTraits = api.listTraits();

    Assertions.assertNotNull(allTraits);
    Assertions.assertTrue(allTraits.size() >= 3);

    var traitNames = allTraits.stream().map(Trait::getName).toList();

    Assertions.assertTrue(traitNames.contains("ListTestTrait1"));
    Assertions.assertTrue(traitNames.contains("ListTestTrait2"));
    Assertions.assertTrue(traitNames.contains("ListTestTrait3"));

    api.deleteTrait("ListTestTrait1");
    api.deleteTrait("ListTestTrait2");
    api.deleteTrait("ListTestTrait3");
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

    var ex =
        Assertions.assertThrows(
            HttpClientErrorException.class,
            () -> api.existsLinkTrait("TestTrait1", "DEPENDS_ON", "TestTrait2"));
    Assertions.assertEquals(404, ex.getStatusCode().value());
  }

  /**
   * Over HTTP, removing a trait relationship that an existing instance link relies on is refused
   * with a 400 naming the link, and succeeds once the link is gone. The service-level rule and its
   * exactness are covered by {@code TraitServiceTests}; what this pins down is the status code and
   * that the message reaches the client.
   */
  @Test
  void testUnlinkTraitIsRefusedWhileAnInstanceLinkReliesOnIt() {
    var api = getMetaCatalogManagerApi();

    var upstream = new Trait();
    upstream.setName("ApiRelyUpstream");
    api.createTrait(upstream);
    var downstream = new Trait();
    downstream.setName("ApiRelyDownstream");
    api.createTrait(downstream);

    var linkSpec = new LinkTraitRequest();
    linkSpec.sourceTrait("ApiRelyUpstream");
    linkSpec.targetTrait("ApiRelyDownstream");
    linkSpec.relationshipTypeName("DEPENDS_ON");
    api.linkTrait(linkSpec);

    var emptySchema =
        """
        { "type": "object", "properties": { } }""";
    var sourceType = new EntityType();
    sourceType.setName("ApiRelySourceType");
    sourceType.setSchema(emptySchema);
    sourceType.setTraits(List.of("ApiRelyUpstream"));
    api.createEntityType(sourceType);
    var targetType = new EntityType();
    targetType.setName("ApiRelyTargetType");
    targetType.setSchema(emptySchema);
    targetType.setTraits(List.of("ApiRelyDownstream"));
    api.createEntityType(targetType);

    var source = new Entity();
    source.setEntityType("ApiRelySourceType");
    source.setValues("{}");
    var sourceId = api.createEntity(source);
    var target = new Entity();
    target.setEntityType("ApiRelyTargetType");
    target.setValues("{}");
    var targetId = api.createEntity(target);

    var entityLink = new LinkEntityRequest();
    entityLink.sourceEntityId(sourceId);
    entityLink.relationshipTypeName("DEPENDS_ON");
    entityLink.targetEntityId(targetId);
    api.linkEntity(entityLink);

    var ex =
        Assertions.assertThrows(
            HttpClientErrorException.class,
            () -> api.unlinkTrait("ApiRelyUpstream", "DEPENDS_ON", "ApiRelyDownstream"));
    Assertions.assertEquals(400, ex.getStatusCode().value());
    var error = Objects.requireNonNull(ex.getResponseBodyAs(ValidationError.class));
    Assertions.assertTrue(
        error.toString().contains(sourceId) && error.toString().contains(targetId),
        "the 400 body must name the link that relies on the relationship, got: " + error);

    // The relationship is still there — the refusal did not half-apply.
    api.existsLinkTrait("ApiRelyUpstream", "DEPENDS_ON", "ApiRelyDownstream");

    api.unlinkEntity(sourceId, "DEPENDS_ON", targetId);
    api.unlinkTrait("ApiRelyUpstream", "DEPENDS_ON", "ApiRelyDownstream");

    api.deleteEntity(sourceId);
    api.deleteEntity(targetId);
    api.deleteEntityType("ApiRelySourceType");
    api.deleteEntityType("ApiRelyTargetType");
    api.deleteTrait("ApiRelyUpstream");
    api.deleteTrait("ApiRelyDownstream");
  }

  @Test
  void testCreateReadExistsDeleteType() {
    var api = getMetaCatalogManagerApi();
    var entityType = new EntityType();
    entityType.setName("TestType");
    entityType.setSchema(
        """
                { "type": "object", "properties": { } }""");
    api.createEntityType(entityType);

    Assertions.assertThrows(
        HttpClientErrorException.BadRequest.class, () -> api.createEntityType(entityType));

    var retrievedType = api.getEntityType("TestType");
    Assertions.assertEquals("TestType", retrievedType.getName());

    api.existsEntityType("TestType");

    api.deleteEntityType("TestType");

    var ex1 =
        Assertions.assertThrows(
            HttpClientErrorException.class, () -> api.getEntityType("TestType"));

    Assertions.assertEquals(
        new ValidationError().errors(List.of("EntityType TestType not found")),
        ex1.getResponseBodyAs(ValidationError.class));

    var ex2 =
        Assertions.assertThrows(
            HttpClientErrorException.class, () -> api.existsEntityType("TestType"));

    Assertions.assertEquals(404, ex2.getStatusCode().value());
  }

  @Test
  void testListEntityTypes() {
    var api = getMetaCatalogManagerApi();

    var entityType1 = new EntityType();
    entityType1.setName("ListTestType1");
    entityType1.setSchema(
        """
                { "type": "object", "properties": { } }""");
    api.createEntityType(entityType1);

    var entityType2 = new EntityType();
    entityType2.setName("ListTestType2");
    entityType2.setSchema(
        """
                { "type": "object", "properties": { } }""");
    api.createEntityType(entityType2);

    var entityType3 = new EntityType();
    entityType3.setName("ListTestType3");
    entityType3.setSchema(
        """
                { "type": "object", "properties": { } }""");
    api.createEntityType(entityType3);

    var allEntityTypes = api.listEntityTypes();

    Assertions.assertNotNull(allEntityTypes);
    Assertions.assertTrue(allEntityTypes.size() >= 3);

    var entityTypeNames = allEntityTypes.stream().map(EntityType::getName).toList();

    Assertions.assertTrue(entityTypeNames.contains("ListTestType1"));
    Assertions.assertTrue(entityTypeNames.contains("ListTestType2"));
    Assertions.assertTrue(entityTypeNames.contains("ListTestType3"));

    api.deleteEntityType("ListTestType1");
    api.deleteEntityType("ListTestType2");
    api.deleteEntityType("ListTestType3");
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
    entity.setValues(
        """
                {
                   "a": "b"
                }
                """);

    var id = api.createEntity(entity);

    var retrievedEntity = api.getEntity(id);

    Assertions.assertEquals("AnotherTestType", retrievedEntity.getEntityType());
    Assertions.assertNotNull(
        retrievedEntity.getEntityTypeVersionId(), "entityTypeVersionId must be populated on read");
    Assertions.assertFalse(
        retrievedEntity.getEntityTypeVersionId().isBlank(),
        "entityTypeVersionId must be a non-empty id");

    api.existsEntity(id);

    api.deleteEntity(id);

    var ex1 = Assertions.assertThrows(HttpClientErrorException.class, () -> api.getEntity(id));
    Assertions.assertTrue(
        Objects.requireNonNull(ex1.getResponseBodyAs(ValidationError.class))
            .getErrors()
            .getFirst()
            .contains("not found"));
  }

  @Test
  void testUpdateEntity() {
    var api = getMetaCatalogManagerApi();
    var entityType = new EntityType();
    entityType.setName("UpdateTestType");
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
    entity.setEntityType("UpdateTestType");
    entity.setValues(
        """
                {
                   "a": "b"
                }
                """);

    var id = api.createEntity(entity);

    var update = new Entity();
    update.setEntityType("UpdateTestType");
    update.setValues(
        """
                {
                   "a": "c"
                }
                """);

    api.updateEntity(id, update);

    var retrieved = api.getEntity(id);
    Assertions.assertEquals("UpdateTestType", retrieved.getEntityType());
    Assertions.assertTrue(
        retrieved.getValues().contains("\"c\""), "values must reflect the update");

    api.deleteEntity(id);
    api.deleteEntityType("UpdateTestType");
  }

  /**
   * Verifies the entity-pinning contract end-to-end through the REST API: an entity created against
   * v1 of a type stays pinned to v1's snapshot (returned as {@code entityTypeVersionId}) even after
   * a new version of the type is created, and updates still validate against the v1 schema.
   */
  @Test
  void testEntityPinningEndToEnd() {
    var api = getMetaCatalogManagerApi();

    var typeV1 = new EntityType();
    typeV1.setName("PinnedEndpointType");
    typeV1.setSchema(
        """
                {
                  "type": "object",
                  "properties": { "name": { "type": "string" } },
                  "required": ["name"],
                  "additionalProperties": false
                }
                """);
    api.createEntityType(typeV1);

    var entity = new Entity();
    entity.setEntityType("PinnedEndpointType");
    entity.setValues(
        """
                { "name": "alpha" }
                """);
    var id = api.createEntity(entity);

    var created = api.getEntity(id);
    Assertions.assertNotNull(created.getEntityTypeVersionId(), "v1 entity must be pinned");
    var pinnedV1 = created.getEntityTypeVersionId();

    var typeV2 = new EntityType();
    typeV2.setName("PinnedEndpointType");
    typeV2.setSchema(
        """
                {
                  "type": "object",
                  "properties": { "name": { "type": "string" }, "value": { "type": "number" } },
                  "required": ["name", "value"],
                  "additionalProperties": false
                }
                """);
    api.createEntityTypeVersion("PinnedEndpointType", typeV2);

    var stillPinned = api.getEntity(id);
    Assertions.assertEquals(
        pinnedV1, stillPinned.getEntityTypeVersionId(), "entity must stay pinned to v1");

    var v2Entity = new Entity();
    v2Entity.setEntityType("PinnedEndpointType");
    v2Entity.setValues(
        """
                { "name": "beta", "value": 1 }
                """);
    var v2Id = api.createEntity(v2Entity);
    var v2Created = api.getEntity(v2Id);
    Assertions.assertNotNull(v2Created.getEntityTypeVersionId());
    Assertions.assertNotEquals(
        pinnedV1, v2Created.getEntityTypeVersionId(), "v2 entity must pin to a different snapshot");

    api.deleteEntity(id);
    api.deleteEntity(v2Id);
    api.deleteEntityType("PinnedEndpointType");
  }

  @Test
  void bulkCreation() {
    var api = getMetaCatalogManagerApi();
    api.bulkCreation(new File("src/test/resources/bulk/bulk1.yaml"));
    Assertions.assertTrue(true);
  }

  @Test
  void bulkCreationAndAggregateRead() throws IOException {

    var api = getMetaCatalogManagerApi();
    api.bulkCreation(new File("src/test/resources/bulk/bulk2.yaml"));
    api.createAggregateAsYaml(new File("src/test/resources/bulk/bulk3.yaml"));

    await().pollDelay(Durations.TWO_SECONDS).until(() -> true);

    var dp1 = api.getEntities("DataProductType", "$ ? (@.name == \"dp1\")").getFirst();

    var op1 = api.getEntities("FileBasedOutputPortType", "$ ? (@.name == \"op1\")").getFirst();

    var op2 = api.getEntities("TableBasedOutputPortType", "$ ? (@.name == \"op2\")").getFirst();

    var linkedToOp2 = api.linkedEntity(op2.getId(), "DEPENDS_ON").getFirst();

    Assertions.assertEquals(op1.getId(), linkedToOp2.getId());

    var dp2 = api.getEntities("DataProductType", "$ ? (@.name == \"dp2\")").getFirst();

    var op3 = api.getEntities("FileBasedOutputPortType", "$ ? (@.name == \"op3\")").getFirst();

    var op4 = api.getEntities("TableBasedOutputPortType", "$ ? (@.name == \"op4\")").getFirst();

    var linkedToOp3 = api.linkedEntity(op4.getId(), "DEPENDS_ON").getFirst();

    Assertions.assertEquals(op3.getId(), linkedToOp3.getId());

    {
      var aggYamlFile = api.getAggregateAsYaml(dp1.getId(), Boolean.TRUE).toPath();

      String contents = Files.readString(aggYamlFile);

      System.out.println(contents);
    }

    {
      var aggYamlFile = api.getAggregateAsYaml(dp2.getId(), Boolean.TRUE).toPath();

      String contents = Files.readString(aggYamlFile);

      System.out.println(contents);
    }

    assertAggregateAccess(api, dp1, op1);
  }

  /**
   * The access endpoint reports both halves of the answer: what the root declares, and what each of
   * its {@code AuthorizableResource}s is holding.
   *
   * <p>Asserted from inside the round-trip test rather than as one of its own, because this class
   * resets the database once for the whole class: a second test loading the same model would die on
   * the first name it already holds.
   *
   * <p>Nothing has been authorized here — this module registers no task factories — so every
   * resource comes back with an empty grant list. That is the assertion worth making: the endpoint
   * reports what is <em>in force</em>, and a policy that has never been applied is in force
   * nowhere. Reporting the policy resolved against each resource instead would have shown two
   * confident grants that nothing had ever acted on.
   */
  private void assertAggregateAccess(MetaCatalogManagerApi api, Entity dp1, Entity op1) {
    var access = api.getAggregateAccess(dp1.getId());

    Assertions.assertEquals(dp1.getId(), access.getAggregateId());
    Assertions.assertEquals("DataProductType", access.getEntityTypeName());
    Assertions.assertNull(access.getAuthorizationStatus(), "never authorized");
    Assertions.assertEquals(
        List.of("analysts", "dp1-loader"),
        access.getPrincipals().stream().map(AccessPrincipal::getId).toList());
    Assertions.assertEquals(
        AccessPrincipal.TypeEnum.SERVICE, access.getPrincipals().get(1).getType());
    Assertions.assertEquals(
        List.of("READ", "WRITE"),
        access.getPermissions().stream().map(AccessPermission::getName).toList());
    Assertions.assertEquals(2, access.getGrants().size());
    // The selector survives the round trip; without it the second grant would read as reaching
    // everything, which is a different policy.
    Assertions.assertEquals(List.of(), access.getGrants().getFirst().getEntityTypes());
    Assertions.assertEquals(List.of("S3FolderType"), access.getGrants().get(1).getEntityTypes());

    // One resource per output port, derived by the mapping engine, both AuthorizableResource and
    // both listed with nothing in force.
    Assertions.assertEquals(2, access.getResources().size());
    for (var resource : access.getResources()) {
      Assertions.assertEquals(List.of(), resource.getGrants());
      Assertions.assertNull(resource.getAuthorizationStatus());
    }

    // An output port is a legitimate aggregate in its own right but carries no Authorizable, so it
    // has no access question to answer — refused naming the trait, rather than answered with an
    // empty policy that would read as "nobody has access".
    var failure =
        Assertions.assertThrows(
            HttpClientErrorException.class, () -> api.getAggregateAccess(op1.getId()));
    Assertions.assertEquals(400, failure.getStatusCode().value());
    Assertions.assertTrue(
        failure.getResponseBodyAsString().contains("Authorizable"),
        failure.getResponseBodyAsString());
  }
}
