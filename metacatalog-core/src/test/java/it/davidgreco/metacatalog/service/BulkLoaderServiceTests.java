package it.davidgreco.metacatalog.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.entity.RelationType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.awaitility.Durations;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class BulkLoaderServiceTests extends CommonServiceTestingSupport {

  public BulkLoaderServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testBulkLoad() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

    var bulkFileStream1 =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk1.yaml");

    bulkLoaderService.bulkModelCreation(bulkFileStream1);

    var bulkFileStream2 =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("bulk/bulk2.yaml");

    var ids = bulkLoaderService.bulkAggregateCreation(bulkFileStream2);

    await().pollDelay(Durations.TWO_SECONDS).until(() -> true);

    System.out.println(aggregateService.read(ids.getFirst(), true));
  }

  /**
   * Verifies that a bulk aggregate YAML referencing an undeclared name in its {@code dependsOn}
   * list is rejected with a {@link ServiceError} containing a descriptive message.
   *
   * <p>The bulk loader resolves each entry in {@code dependsOn} against the names declared earlier
   * in the same YAML document. A missing reference must surface as a clear {@link ServiceError}
   * rather than a {@link NullPointerException} from a null lookup, so that callers get actionable
   * feedback about which name was undeclared.
   */
  @Test
  void bulkAggregateCreationRejectsMissingRef() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);

    // The aggregate YAML references an entity type; it must exist before we can test the ref check.
    entityTypeService.create(
        "MissingRefType",
        java.util.List.of(),
        java.util.Optional.empty(),
        "{ \"type\": \"object\", \"properties\": { \"name\": { \"type\": \"string\" } } }");

    var yaml =
        """
        entityType: "MissingRefType"
        values:
          name: "dp1"
        dependsOn: ["never-declared"]
        """;
    var ex =
        assertThrows(
            ServiceError.class,
            () ->
                bulkLoaderService.bulkAggregateCreation(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    assertTrue(
        ex.getMessage().contains("never declared"),
        "error must mention the undeclared ref; got: " + ex.getMessage());
  }

  /**
   * A node depending on several others gets a {@code DEPENDS_ON} link to every one of them. The
   * pending links were once collected in a map keyed by the depending entity, so each new entry
   * overwrote the previous one and only the last name in {@code dependsOn} was ever linked — with
   * no error, which left lineage silently incomplete.
   */
  @Test
  void bulkAggregateCreationLinksEveryDependsOnEntry() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var model =
        """
        Traits:
          - name: MultiDepComponent
        ---
        Relationships:
          - sourceTrait: MultiDepComponent
            relationshipType: IS_REQUIRED_BY
            targetTrait: MultiDepComponent
        ---
        EntityTypes:
          - name: MultiDepRootType
            schema: { type: object, properties: { name: { type: string } } }
            traits: [Aggregate]
          - name: MultiDepPartType
            schema: { type: object, properties: { name: { type: string } } }
            traits: [AggregateElement, MultiDepComponent]
        """;
    bulkLoaderService.bulkModelCreation(
        new ByteArrayInputStream(model.getBytes(StandardCharsets.UTF_8)));

    var instances =
        """
        entityType: "MultiDepRootType"
        values: { name: "root" }
        parts:
          - { ref: "a", entityType: "MultiDepPartType", values: { name: "a" } }
          - { ref: "b", entityType: "MultiDepPartType", values: { name: "b" } }
          - { ref: "c", entityType: "MultiDepPartType", values: { name: "c" }, dependsOn: ["a", "b"] }
        """;
    bulkLoaderService.bulkAggregateCreation(
        new ByteArrayInputStream(instances.getBytes(StandardCharsets.UTF_8)));

    var c =
        entityService.list("MultiDepPartType", "").stream()
            .filter(e -> "c".equals(e.getValues().get("name").asText()))
            .findFirst()
            .orElseThrow();
    var dependencies =
        entityService.linked(c.getId(), RelationType.DEPENDS_ON).stream()
            .map(e -> e.getValues().get("name").asText())
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(java.util.Set.of("a", "b"), dependencies);
  }

  /**
   * Loads the medallion lakehouse under {@code examples/medallion} the way its README does. The
   * example is walked through in published material, so it has to keep loading as the model
   * evolves: the model and the aggregate must be accepted, the mapping engine must derive every
   * zone and physical table, and each gold table must keep both of its lineage links.
   */
  @Test
  void medallionExampleLoadsAndDerivesItsResources() throws java.io.IOException {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var exampleDir = java.nio.file.Path.of("..", "examples", "medallion");
    try (var model =
        java.nio.file.Files.newInputStream(exampleDir.resolve("medallion-model.yaml"))) {
      bulkLoaderService.bulkModelCreation(model);
    }
    try (var instances =
        java.nio.file.Files.newInputStream(exampleDir.resolve("medallion-instances.yaml"))) {
      bulkLoaderService.bulkAggregateCreation(instances);
    }

    await()
        .atMost(java.time.Duration.ofSeconds(30))
        .until(
            () ->
                entityService.list("LakeZoneType", "").size() == 3
                    && entityService.list("LakeTableType", "").size() == 8);

    var goldTables = entityService.list("GoldTableType", "");
    assertEquals(2, goldTables.size());
    for (var gold : goldTables) {
      assertEquals(
          2,
          entityService.linked(gold.getId(), RelationType.DEPENDS_ON).size(),
          gold.getValues().get("name").asText() + " must depend on both of its silver tables");
    }
  }

  /**
   * Replays the docker demo flow against the real files under {@code docker/bulk}: load the model,
   * load the aggregate, let the mapping engine derive the resources, delete the aggregate from the
   * catalog (as the UI does), and load the aggregate file again. The reload is what the compose
   * bulk-loader does after a restart when the demo aggregate was deleted, and must succeed.
   */
  @Test
  void testDockerDemoFilesReloadAfterAggregateDeletion() throws java.io.IOException {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var bulkDir = java.nio.file.Path.of("..", "docker", "bulk");
    loadDemoModelIfMissing();
    var s3Before = entityService.list("S3FolderType", "").size();
    var athenaBefore = entityService.list("AthenaTableType", "").size();
    java.util.List<String> ids;
    try (var instances =
        java.nio.file.Files.newInputStream(bulkDir.resolve("bulk-instances.yaml"))) {
      ids = bulkLoaderService.bulkAggregateCreation(instances);
    }
    // The demo's data product is Versionable: the load is its v1, holding both output ports. Later
    // versions follow asynchronously as the mapping updater derives the resources, so only the
    // first one is pinned here.
    var first = entityService.listVersions(ids.getFirst()).getFirst();
    assertEquals(1, first.getVersion());
    assertEquals(2, first.getAggregate().get("parts").size());

    // The scheduled mapping engine derives the S3 folder and the Athena table asynchronously;
    // the aggregate delete below must see them to remove them with the tree. Counted as deltas:
    // other tests' aggregates may already have derived instances of the same types.
    await()
        .atMost(java.time.Duration.ofSeconds(30))
        .until(
            () ->
                entityService.list("S3FolderType", "").size() == s3Before + 1
                    && entityService.list("AthenaTableType", "").size() == athenaBefore + 1);

    var domainId = ids.get(1);
    aggregateService.delete(ids.getFirst());
    // Deleting the data product takes its own dependency with it and nothing else: the domain is
    // not part of the aggregate, and other data products may belong to it.
    assertTrue(entityService.exists(domainId), "the domain must outlive the data product");

    // Mimic the compose bulk-loader's read probe before reloading: list entities of the root type,
    // then reload the aggregate. The domain is still there, so — as load-bulk.sh does — its
    // document is left out and the data product is pointed at the existing one by id; loading the
    // file as it is would leave a second `sales` behind on every reload.
    entityService.list("DataProductType", "");

    var documents =
        java.nio.file.Files.readString(bulkDir.resolve("bulk-instances.yaml")).split("(?m)^---$");
    assertEquals(2, documents.length, "the demo is one data product and the domain it belongs to");
    var dataProductOnly = documents[0].replace("- \"sales\"", "- \"" + domainId + "\"");
    assertTrue(dataProductOnly.contains(domainId), "the demo data product must depend on sales");
    ids =
        bulkLoaderService.bulkAggregateCreation(
            new ByteArrayInputStream(dataProductOnly.getBytes(StandardCharsets.UTF_8)));
    assertEquals(1, ids.size(), "the demo aggregate must load again after being deleted");
    assertEquals(
        java.util.List.of(domainId),
        entityService.linked(ids.getFirst(), RelationType.DEPENDS_ON).stream()
            .map(it.davidgreco.metacatalog.entity.Entity::getId)
            .toList());
  }

  /**
   * The demo starts with a history: the loader records a second version of the data product from
   * {@code bulk-instances-v2.yaml}, which adds an output port. That is one update and one new
   * version, and it leaves the first version as a copy that is still a whole data product — its two
   * ports and the resources derived from them, named after the domain it belonged to — so that
   * there is a past version to open and run a function on.
   */
  @Test
  @org.junit.jupiter.api.Timeout(60)
  void testDockerDemoSecondVersionAddsAPortAndLeavesTheFirstRunnable() throws java.io.IOException {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var yaml =
        new com.fasterxml.jackson.databind.ObjectMapper(
            new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
    var bulkDir = java.nio.file.Path.of("..", "docker", "bulk");
    loadDemoModelIfMissing();

    String rootId;
    try (var instances =
        java.nio.file.Files.newInputStream(bulkDir.resolve("bulk-instances.yaml"))) {
      rootId = bulkLoaderService.bulkAggregateCreation(instances).getFirst();
    }
    var ports = entityService.linked(rootId, RelationType.HAS_PART);
    var op1 =
        ports.stream()
            .filter(port -> port.getEntityType().getName().equals("FileBasedOutputPortType"))
            .findFirst()
            .orElseThrow();

    // What load-bulk.sh does: the file's parts, with the port they refer to named by its id,
    // added to the data product as it stands. The loader builds the document from the YAML the
    // API serves; here it is built from the same members.
    var reference = "{{FileBasedOutputPortType/op1}}";
    var added = java.nio.file.Files.readString(bulkDir.resolve("bulk-instances-v2.yaml"));
    assertTrue(added.contains(reference), "the file names the port its new part depends on");
    var document = yaml.createObjectNode();
    document.put("entityType", "DataProductType");
    document.set("values", entityService.read(rootId).getValues());
    var parts = document.putArray("parts");
    for (var port : ports) {
      var node = parts.addObject();
      node.put("id", port.getId());
      node.put("entityType", port.getEntityType().getName());
      node.set("values", port.getValues());
    }
    yaml.readTree(added.replace(reference, op1.getId())).forEach(parts::add);
    aggregateService.update(rootId, document);

    var versions = entityService.listVersions(rootId);
    assertEquals(2, versions.size(), "one update is one version, whatever it adds");
    assertEquals(2, versions.getFirst().getAggregate().get("parts").size());
    assertEquals(3, versions.getLast().getAggregate().get("parts").size());

    // The first version is now a copy of its own, with resources of its own to run on.
    var copyRootId = versions.getFirst().getFrozenRootId();
    org.junit.jupiter.api.Assertions.assertNotNull(copyRootId, "v1 is frozen once v2 is recorded");
    var derived = new java.util.ArrayList<String>();
    collectDerivedValues(aggregateService.read(copyRootId, true), derived);
    assertTrue(derived.contains("/root/sales/dp1/op1"), derived.toString());
    assertTrue(derived.contains("sales_dp1"), derived.toString());
  }

  /** The folder paths and table databases of the resources in an aggregate read. */
  private static void collectDerivedValues(
      AggregateService.AggregatePart part, java.util.List<String> into) {
    for (var field : java.util.List.of("path", "database")) {
      var value = part.entity().getValues().get(field);
      if (value != null) into.add(value.asText());
    }
    if (part instanceof AggregateService.Aggregate aggregate)
      aggregate.elements().forEach(element -> collectDerivedValues(element, into));
  }

  /**
   * A data product created before it belongs to a domain has nothing to name its folder after, so
   * the folder is not derived — and is, once the product is given its domain. Without that, a
   * product assembled in the UI and dropped onto a domain afterwards would stay without resources
   * until one of its output ports happened to be edited.
   */
  @Test
  @org.junit.jupiter.api.Timeout(60)
  void testADependencyAddedLaterDerivesWhatCouldNotBeDerivedWithoutIt() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var events =
        getApplicationContext()
            .getBean(it.davidgreco.metacatalog.repository.EntityLifeCycleEventRepository.class);
    loadDemoModelIfMissing();

    var dataProductId =
        bulkLoaderService
            .bulkAggregateCreation(
                new ByteArrayInputStream(
                    """
                    entityType: "DataProductType"
                    values:
                      name: "late"
                    parts:
                      - entityType: "FileBasedOutputPortType"
                        values:
                          name: "files"
                    """
                        .getBytes(StandardCharsets.UTF_8)))
            .getFirst();
    var outputPortId =
        entityService.linked(dataProductId, RelationType.HAS_PART).getFirst().getId();

    // The engine has had its go at the output port, and had no domain to read.
    await()
        .atMost(Durations.TEN_SECONDS)
        .until(
            () ->
                !events.existsByEntityIdAndEventTypeAndEventStatus(
                    outputPortId,
                    it.davidgreco.metacatalog.entity.EntityLifeCycleEvent.ENTITY_SOURCE_CREATED,
                    it.davidgreco.metacatalog.entity.EntityLifeCycleEvent.STATUS_PENDING));
    assertEquals(java.util.List.of(), mappedPaths(aggregateService, dataProductId));

    var domain = entityService.create("DomainType", "{\"name\": \"finance\"}");
    entityService.link(dataProductId, RelationType.DEPENDS_ON, domain.getId());

    await()
        .atMost(Durations.TEN_SECONDS)
        .until(() -> !mappedPaths(aggregateService, dataProductId).isEmpty());
    assertEquals(
        java.util.List.of("/root/finance/late/files"),
        mappedPaths(aggregateService, dataProductId));
  }

  private static java.util.List<String> mappedPaths(
      AggregateService aggregateService, String dataProductId) {
    return aggregateService.assets().stream()
        .filter(asset -> asset.entity().getId().equals(dataProductId))
        .flatMap(asset -> asset.mapped().stream())
        .map(mapped -> mapped.entity().getValues().get("path").asText())
        .toList();
  }

  /**
   * Loads the docker demo's model unless it is there already.
   *
   * <p>The same model also ships as the bulk1.yaml test fixture, so another test in this class may
   * have loaded it — mirror the compose loader and only load it when it is missing.
   *
   * <p>The two files must therefore stay byte-identical in what they declare. They drifted once,
   * when the docker model gained Authorizable / AuthorizableResource and the fixture did not: the
   * probe below found WithName, skipped the load, and a test went on validating the docker demo's
   * aggregate against the <em>fixture's</em> DataProductType — silently, until that aggregate grew
   * a field only the docker model's traits admit. Keep them in sync.
   */
  private void loadDemoModelIfMissing() {
    if (getApplicationContext().getBean(TraitService.class).exists("WithName")) return;
    try (var model =
        java.nio.file.Files.newInputStream(
            java.nio.file.Path.of("..", "docker", "bulk", "bulk-model.yaml"))) {
      getApplicationContext().getBean(BulkLoaderService.class).bulkModelCreation(model);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** Malformed top-level section (non-array) must throw ServiceError, not silently skip. */
  @Test
  void bulkModelCreationRejectsNonArraySection() {
    var bulkLoaderService = getApplicationContext().getBean(BulkLoaderService.class);
    var yaml =
        """
        Traits: "not-an-array"
        """;
    var ex =
        assertThrows(
            ServiceError.class,
            () ->
                bulkLoaderService.bulkModelCreation(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    assertTrue(
        ex.getMessage().contains("must be a YAML array"),
        "error must mention the section shape; got: " + ex.getMessage());
  }
}
