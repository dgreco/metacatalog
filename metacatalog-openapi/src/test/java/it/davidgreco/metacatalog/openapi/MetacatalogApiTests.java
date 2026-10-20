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
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@RequiredArgsConstructor
class MetacatalogApiTests {
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

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

  /**
   * The version history of a Versionable entity is readable through the API, a never-versioned
   * entity has an empty history rather than a missing one, and unknown entities or versions are
   * 404s.
   */
  @Test
  void testEntityVersionHistoryEndpoints() throws IOException {
    var api = getMetaCatalogManagerApi();
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var schema = "{ \"type\": \"object\", \"properties\": { \"name\": { \"type\": \"string\" } } }";

    var versionedType = new EntityType();
    versionedType.setName("ApiVersioned");
    versionedType.setSchema(schema);
    versionedType.setTraits(List.of("Versionable"));
    api.createEntityType(versionedType);
    var plainType = new EntityType();
    plainType.setName("ApiUnversioned");
    plainType.setSchema(schema);
    api.createEntityType(plainType);

    var versioned = new Entity();
    versioned.setEntityType("ApiVersioned");
    versioned.setValues("{\"name\":\"one\"}");
    var id = api.createEntity(versioned);
    var update = new Entity();
    update.setEntityType("ApiVersioned");
    update.setValues("{\"name\":\"two\"}");
    api.updateEntity(id, update);

    Assertions.assertEquals(2, api.getEntity(id).getVersion());
    var versions = api.listEntityVersions(id);
    Assertions.assertEquals(
        List.of(1, 2), versions.stream().map(EntityVersion::getVersion).toList());
    Assertions.assertEquals(id, versions.getFirst().getEntityId());
    Assertions.assertNull(versions.getFirst().getPreviousVersionId());
    Assertions.assertEquals(versions.getFirst().getId(), versions.getLast().getPreviousVersionId());
    Assertions.assertNotNull(versions.getFirst().getCreatedAt());
    Assertions.assertNull(
        versions.getFirst().getAggregate(), "an entity outside an aggregate records no aggregate");
    Assertions.assertEquals(
        json.readTree("{\"name\":\"one\"}"),
        json.readTree(api.getEntityVersion(id, 1).getValues()));
    Assertions.assertTrue(
        api.listAllEntityVersions().stream().anyMatch(v -> id.equals(v.getEntityId())));

    var missingVersion =
        Assertions.assertThrows(HttpClientErrorException.class, () -> api.getEntityVersion(id, 3));
    Assertions.assertEquals(404, missingVersion.getStatusCode().value());
    var missingEntity =
        Assertions.assertThrows(
            HttpClientErrorException.class, () -> api.listEntityVersions("no-such-entity"));
    Assertions.assertEquals(404, missingEntity.getStatusCode().value());

    var plain = new Entity();
    plain.setEntityType("ApiUnversioned");
    plain.setValues("{\"name\":\"one\"}");
    var plainId = api.createEntity(plain);
    Assertions.assertNull(api.getEntity(plainId).getVersion());
    Assertions.assertTrue(api.listEntityVersions(plainId).isEmpty());

    api.deleteEntity(id);
    api.deleteEntity(plainId);
    api.deleteEntityType("ApiVersioned");
    api.deleteEntityType("ApiUnversioned");
  }

  /**
   * A Versionable aggregate root records its whole aggregate: one version for the creation, holding
   * the root, its parts and their dependencies. A versionable part type is refused.
   */
  @Test
  void testVersionableAggregateRecordsTheWholeTree() throws IOException {
    var api = getMetaCatalogManagerApi();
    var schema = "{ \"type\": \"object\", \"properties\": { \"name\": { \"type\": \"string\" } } }";

    var partTrait = new Trait();
    partTrait.setName("ApiAvPart");
    partTrait.setInheritsFrom("AggregateElement");
    api.createTrait(partTrait);
    var sanction = new LinkTraitRequest();
    sanction.sourceTrait("ApiAvPart");
    sanction.relationshipTypeName("DEPENDS_ON");
    sanction.targetTrait("ApiAvPart");
    api.linkTrait(sanction);
    var rootType = new EntityType();
    rootType.setName("ApiAvRoot");
    rootType.setSchema(schema);
    rootType.setTraits(List.of("Aggregate", "Versionable"));
    api.createEntityType(rootType);
    var leafType = new EntityType();
    leafType.setName("ApiAvLeaf");
    leafType.setSchema(schema);
    leafType.setTraits(List.of("ApiAvPart"));
    api.createEntityType(leafType);
    var derivedType = new EntityType();
    derivedType.setName("ApiAvDerived");
    derivedType.setSchema(
        "{ \"type\": \"object\", \"properties\": { \"label\": { \"type\": \"string\" } } }");
    api.createEntityType(derivedType);
    var mapping = new Mapping();
    mapping.setSourceEntityType("ApiAvLeaf");
    mapping.setTargetEntityType("ApiAvDerived");
    mapping.setMappingValues("{\"label\": \"#source.getValue('$.name').asText() + '-derived'\"}");
    mapping.setEntityPathReferences("[]");
    api.createMapping(mapping);

    var badLeaf = new EntityType();
    badLeaf.setName("ApiAvBadLeaf");
    badLeaf.setSchema(schema);
    badLeaf.setTraits(List.of("ApiAvPart", "Versionable"));
    var refused =
        Assertions.assertThrows(
            HttpClientErrorException.class, () -> api.createEntityType(badLeaf));
    Assertions.assertEquals(400, refused.getStatusCode().value());
    Assertions.assertTrue(
        refused.getResponseBodyAsString().contains("only the root can be versionable"),
        refused.getResponseBodyAsString());

    var document = Files.createTempFile("aggregate", ".yaml");
    Files.writeString(
        document,
        """
        entityType: "ApiAvRoot"
        values:
          name: "api-root"
        parts:
        - ref: "a"
          entityType: "ApiAvLeaf"
          values:
            name: "a"
        - ref: "b"
          entityType: "ApiAvLeaf"
          values:
            name: "b"
          dependsOn:
            - "a"
        """);
    api.createAggregateAsYaml(document.toFile());

    var rootId = api.getEntities("ApiAvRoot", null).getFirst().getId();
    var versions = api.listEntityVersions(rootId);
    Assertions.assertEquals(1, versions.size(), "the creation is one version");
    var tree = versions.getFirst().getAggregate();
    Assertions.assertEquals(rootId, tree.getId());
    Assertions.assertEquals("ApiAvRoot", tree.getEntityType());
    var parts = tree.getParts();
    Assertions.assertEquals(2, parts.size());
    var a = parts.stream().filter(p -> p.getValues().contains("\"a\"")).findFirst().orElseThrow();
    var b = parts.stream().filter(p -> p.getValues().contains("\"b\"")).findFirst().orElseThrow();
    Assertions.assertEquals("ApiAvLeaf", a.getEntityType());
    Assertions.assertEquals(List.of(a.getId()), b.getDependencies());
    Assertions.assertTrue(a.getMapped().isEmpty(), "v1 is recorded before anything is derived");

    // The mapping updater derives one entity per part afterwards; each derivation is a version of
    // the aggregate, and the latest records the derived entities under their sources.
    await()
        .atMost(Durations.TEN_SECONDS)
        .until(
            () ->
                api.listEntityVersions(rootId).getLast().getAggregate().getParts().stream()
                    .allMatch(part -> part.getMapped().size() == 1));
    var latest = api.listEntityVersions(rootId).getLast();
    Assertions.assertTrue(latest.getVersion() > 1);
    var derivedLabels =
        latest.getAggregate().getParts().stream()
            .map(part -> part.getMapped().getFirst().getValues())
            .map(values -> values.replaceAll("\\s", ""))
            .sorted()
            .toList();
    Assertions.assertEquals(
        List.of("{\"label\":\"a-derived\"}", "{\"label\":\"b-derived\"}"), derivedLabels);
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
