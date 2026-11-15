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

    var notCurrent =
        Assertions.assertThrows(
            HttpClientErrorException.class, () -> api.deleteEntityVersion(id, 2));
    Assertions.assertEquals(400, notCurrent.getStatusCode().value());
    api.deleteAllEntityVersions(id);
    Assertions.assertEquals(
        List.of(2), api.listEntityVersions(id).stream().map(EntityVersion::getVersion).toList());
    Assertions.assertNull(api.listEntityVersions(id).getFirst().getPreviousVersionId());

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
   * the root, its parts and their dependencies. A versionable part type is refused. The version a
   * new one supersedes is frozen into a copy that the API exposes, hides from listings, refuses to
   * change, and deletes with its version.
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

    // The mapping updater derives one entity per part afterwards. That records no version of its
    // own; the next version taken for another reason records the derived entities under their
    // sources.
    await()
        .atMost(Durations.TEN_SECONDS)
        .until(() -> api.getEntities("ApiAvDerived", null).size() == 2);
    Assertions.assertEquals(1, api.listEntityVersions(rootId).size(), "deriving is not a version");
    var rename = new Entity();
    rename.setEntityType("ApiAvRoot");
    rename.setValues("{\"name\":\"api-root-renamed\"}");
    api.updateEntity(rootId, rename);
    var latest = api.listEntityVersions(rootId).getLast();
    Assertions.assertEquals(2, latest.getVersion());
    var derivedLabels =
        latest.getAggregate().getParts().stream()
            .map(part -> part.getMapped().getFirst().getValues())
            .map(values -> values.replaceAll("\\s", ""))
            .sorted()
            .toList();
    Assertions.assertEquals(
        List.of("{\"label\":\"a-derived\"}", "{\"label\":\"b-derived\"}"), derivedLabels);

    var copyRootId = api.listEntityVersions(rootId).getFirst().getFrozenRootId();
    Assertions.assertNotNull(copyRootId, "v1 is frozen once v2 supersedes it");
    Assertions.assertNull(latest.getFrozenRootId(), "the current version is the live aggregate");
    var copyRoot = api.getEntity(copyRootId);
    Assertions.assertEquals(rootId, copyRoot.getFrozenVersionOf());
    Assertions.assertEquals(1, copyRoot.getFrozenVersion());
    Assertions.assertNull(api.getEntity(rootId).getFrozenVersionOf());
    Assertions.assertEquals(
        List.of(rootId),
        api.getEntities("ApiAvRoot", null).stream().map(Entity::getId).toList(),
        "a copy is reached through its version, not listed");
    var copy = api.getAggregate(copyRootId, true);
    Assertions.assertEquals(2, copy.getParts().size(), "the copy has v1's two parts");
    var refusedUpdate =
        Assertions.assertThrows(
            HttpClientErrorException.class, () -> api.updateEntity(copyRootId, rename));
    Assertions.assertEquals(400, refusedUpdate.getStatusCode().value());
    Assertions.assertTrue(
        refusedUpdate.getResponseBodyAsString().contains("version 1 of aggregate"),
        refusedUpdate.getResponseBodyAsString());

    api.deleteEntityVersion(rootId, 1);
    Assertions.assertEquals(
        List.of(2),
        api.listEntityVersions(rootId).stream().map(EntityVersion::getVersion).toList());
    var gone =
        Assertions.assertThrows(HttpClientErrorException.class, () -> api.getEntity(copyRootId));
    Assertions.assertEquals(404, gone.getStatusCode().value());
  }

  /**
   * The asset surface end to end, through the generated client: a trait mixing traits in, the asset
   * types, the catalog and the dependencies the model allows, then an asset downloaded as YAML,
   * edited and sent back.
   */
  @Test
  void testAssetCatalogDependencyRulesAndWholeAggregateUpdate() throws IOException {
    var api = getMetaCatalogManagerApi();
    var schema = "{ \"type\": \"object\", \"properties\": { \"name\": { \"type\": \"string\" } } }";

    for (var name : List.of("ApiAstDomain", "ApiAstTable")) {
      var trait = new Trait();
      trait.setName(name);
      api.createTrait(trait);
    }
    // A trait with two parents: a father and a mixed-in trait.
    var governed = new Trait();
    governed.setName("ApiAstGovernedTable");
    governed.setInheritsFrom("ApiAstTable");
    governed.setTraits(List.of("Versionable"));
    api.createTrait(governed);
    var readGoverned = api.getTrait("ApiAstGovernedTable");
    Assertions.assertEquals("ApiAstTable", readGoverned.getInheritsFrom());
    Assertions.assertEquals(List.of("Versionable"), readGoverned.getTraits());

    // Asset is Provisionable + Authorizable; naming Aggregate on top of it repeats what it says.
    var redundant = new Trait();
    redundant.setName("ApiAstRedundant");
    redundant.setInheritsFrom("Asset");
    redundant.setTraits(List.of("Aggregate"));
    var refused =
        Assertions.assertThrows(HttpClientErrorException.class, () -> api.createTrait(redundant));
    Assertions.assertEquals(400, refused.getStatusCode().value());
    Assertions.assertTrue(
        refused.getResponseBodyAsString().contains("Aggregate is already inherited through Asset"),
        refused.getResponseBodyAsString());

    var asset = api.getTrait("Asset");
    Assertions.assertEquals("Provisionable", asset.getInheritsFrom());
    Assertions.assertEquals(List.of("Authorizable"), asset.getTraits());

    var sanction = new LinkTraitRequest();
    sanction.sourceTrait("ApiAstTable");
    sanction.relationshipTypeName("DEPENDS_ON");
    sanction.targetTrait("ApiAstDomain");
    api.linkTrait(sanction);

    var columnType = new EntityType();
    columnType.setName("ApiAstColumnType");
    columnType.setSchema(schema);
    columnType.setTraits(List.of("AggregateElement"));
    api.createEntityType(columnType);
    // A plain type: a domain is not provisioned, it is only something a table belongs to.
    var domainType = new EntityType();
    domainType.setName("ApiAstDomainType");
    domainType.setSchema(schema);
    domainType.setTraits(List.of("ApiAstDomain"));
    api.createEntityType(domainType);
    var tableType = new EntityType();
    tableType.setName("ApiAstTableType");
    tableType.setSchema(schema);
    tableType.setTraits(List.of("Asset", "ApiAstGovernedTable"));
    api.createEntityType(tableType);

    var assetTypes = api.listAssetTypes().stream().map(EntityType::getName).toList();
    Assertions.assertTrue(assetTypes.contains("ApiAstTableType"), assetTypes.toString());
    Assertions.assertFalse(assetTypes.contains("ApiAstDomainType"));
    Assertions.assertFalse(assetTypes.contains("ApiAstColumnType"));
    var capabilities = api.getTypeCapabilities();
    Assertions.assertEquals(assetTypes, capabilities.getAsset());
    Assertions.assertTrue(capabilities.getProvisionable().containsAll(assetTypes));
    Assertions.assertTrue(capabilities.getAuthorizable().containsAll(assetTypes));

    var rules =
        api.listAssetDependencyRules().stream()
            .filter(rule -> rule.getSourceEntityType().startsWith("ApiAst"))
            .toList();
    Assertions.assertEquals(1, rules.size(), rules.toString());
    Assertions.assertEquals("ApiAstTableType", rules.getFirst().getSourceEntityType());
    Assertions.assertEquals("ApiAstDomainType", rules.getFirst().getTargetEntityType());
    Assertions.assertEquals(
        AssetDependencyRule.TargetKindEnum.ENTITY, rules.getFirst().getTargetKind());
    Assertions.assertEquals(
        "ApiAstTable", rules.getFirst().getSanctionedBy().getFirst().getSourceTrait());

    var documents = Files.createTempFile("assets", ".yaml");
    var domainEntity = new Entity();
    domainEntity.setEntityType("ApiAstDomainType");
    domainEntity.setValues("{\"name\": \"sales\"}");
    var domainId = api.createEntity(domainEntity);
    Files.writeString(
        documents,
        """
        entityType: "ApiAstTableType"
        values:
          name: "orders"
        parts:
        - entityType: "ApiAstColumnType"
          values:
            name: "id"
        """);
    api.createAggregateAsYaml(documents.toFile());
    var tableId = api.getEntities("ApiAstTableType", null).getFirst().getId();

    // The illegal direction is refused; the legal one is a plain entity link.
    var backwards = new LinkEntityRequest();
    backwards.sourceEntityId(domainId);
    backwards.relationshipTypeName("DEPENDS_ON");
    backwards.targetEntityId(tableId);
    Assertions.assertThrows(HttpClientErrorException.class, () -> api.linkEntity(backwards));
    var dependency = new LinkEntityRequest();
    dependency.sourceEntityId(tableId);
    dependency.relationshipTypeName("DEPENDS_ON");
    dependency.targetEntityId(domainId);
    api.linkEntity(dependency);

    var catalog = api.listAssets();
    var table = catalog.stream().filter(a -> a.getId().equals(tableId)).findFirst().orElseThrow();
    Assertions.assertEquals("ApiAstTableType", table.getEntityType());
    Assertions.assertEquals(List.of(domainId), table.getDependencies());
    // Its inside comes with it: the column, under the table's root, depending on nothing.
    Assertions.assertEquals(1, table.getParts().size(), table.getParts().toString());
    var column = table.getParts().getFirst();
    Assertions.assertEquals("ApiAstColumnType", column.getEntityType());
    Assertions.assertEquals(tableId, column.getParentId());
    Assertions.assertEquals(List.of(), column.getDependencies());
    Assertions.assertTrue(column.getValues().contains("\"id\""), column.getValues());
    // Versionable through the trait its type mixes in: created at 1, and the dependency just
    // linked is part of what a version of an aggregate records.
    Assertions.assertEquals(2, table.getVersion());
    // The domain is what the table depends on, not an asset: it is listed among the targets.
    Assertions.assertTrue(catalog.stream().noneMatch(a -> a.getId().equals(domainId)));
    var target =
        api.listAssetDependencyTargets().stream()
            .filter(t -> t.getId().equals(domainId))
            .findFirst()
            .orElseThrow();
    Assertions.assertEquals(AssetDependencyTarget.KindEnum.ENTITY, target.getKind());
    Assertions.assertEquals("ApiAstDomainType", target.getEntityType());

    // Download, edit, upload: rename the table, rename its column and add a second one. The part
    // is added by appending a list item to the document as it was served — the root's `parts`
    // come last, their items at column 0. docker/bulk/load-bulk.sh gives the demo its second
    // version the same way, so a change in how an aggregate is serialized breaks it too.
    var downloaded = Files.readString(api.getAggregateAsYaml(tableId, Boolean.FALSE).toPath());
    Assertions.assertTrue(downloaded.contains(tableId), downloaded);
    var edited =
        downloaded
                .replace("\"orders\"", "\"orders_v2\"")
                .replace("name: \"id\"", "name: \"order_id\"")
            + """
            - entityType: "ApiAstColumnType"
              values:
                name: "amount"
            """
                .stripIndent()
                .indent(0);
    var upload = Files.createTempFile("asset-update", ".yaml");
    Files.writeString(upload, edited);
    api.updateAggregateAsYaml(tableId, upload.toFile());

    var updated = api.getAggregate(tableId, Boolean.FALSE);
    Assertions.assertTrue(updated.getValues().contains("orders_v2"), updated.getValues());
    var columns = updated.getParts().stream().map(Aggregate::getValues).toList();
    Assertions.assertEquals(2, columns.size(), columns.toString());
    Assertions.assertTrue(columns.stream().anyMatch(values -> values.contains("order_id")));
    Assertions.assertTrue(columns.stream().anyMatch(values -> values.contains("amount")));
    // The document did list the dependency it was downloaded with, so the table still has it.
    Assertions.assertEquals(List.of(domainId), updated.getDependencies());
    Assertions.assertEquals(3, api.listEntityVersions(tableId).size(), "one update is one version");

    // An entity that is not an aggregate is refused, and so is one that is not found.
    var wrong = Files.createTempFile("asset-wrong", ".yaml");
    Files.writeString(wrong, downloaded);
    var mismatch =
        Assertions.assertThrows(
            HttpClientErrorException.class,
            () -> api.updateAggregateAsYaml(domainId, wrong.toFile()));
    Assertions.assertEquals(400, mismatch.getStatusCode().value());
    var missing =
        Assertions.assertThrows(
            HttpClientErrorException.class,
            () -> api.updateAggregateAsYaml("no-such-aggregate", wrong.toFile()));
    Assertions.assertEquals(404, missing.getStatusCode().value());

    // Deleting the table takes its dependency on the domain along; the domain is then free to go.
    api.deleteAggregate(tableId);
    api.deleteEntity(domainId);
    Assertions.assertTrue(api.listAssets().stream().noneMatch(a -> a.getId().equals(tableId)));
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
