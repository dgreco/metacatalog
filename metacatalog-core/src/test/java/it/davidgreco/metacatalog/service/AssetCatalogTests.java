package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.EMPTY_SCHEMA;
import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;

import it.davidgreco.metacatalog.entity.TraitRelationship;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Covers the catalog view of assets: which aggregates {@link AggregateService#assets()} lists and
 * which dependencies between them it reports, and which dependencies {@link
 * AggregateSchemaService#assetDependencyRules()} says the model allows. The database is reset once
 * per test class, so the model is created by whichever test runs first.
 */
@SpringBootTest
class AssetCatalogTests extends CommonServiceTestingSupport {

  public AssetCatalogTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @BeforeEach
  void createModel() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);

    if (traitService.exists("AsNamed")) return;

    traitService.create(
        "AsNamed",
        Optional.of(
            """
            { "type": "object", "properties": { "name": { "type": "string" } } }
            """),
        Optional.empty());
    traitService.create("AsDomain", Optional.empty(), Optional.empty());
    traitService.create("AsTable", Optional.empty(), Optional.empty());
    // The one dependency the model allows between assets: a table belongs to a domain.
    traitService.link("AsTable", DEPENDS_ON, "AsDomain");
    // And one that involves something that is not an asset.
    traitService.create("AsNote", Optional.empty(), Optional.empty());
    traitService.link("AsTable", DEPENDS_ON, "AsNote");

    // What lets one part of an asset depend on another.
    traitService.create("AsColumn", Optional.empty(), Optional.empty());
    traitService.link("AsColumn", DEPENDS_ON, "AsColumn");

    // A part type, so that the types below root an aggregate at all, and one that is both a part
    // and a container, so that an asset can be more than one level deep.
    entityTypeService.create(
        "AsColumnType",
        List.of("AggregateElement", "AsNamed", "AsColumn"),
        Optional.empty(),
        EMPTY_SCHEMA);
    entityTypeService.create(
        "AsGroupType",
        List.of("Aggregate", "AggregateElement", "AsNamed"),
        Optional.empty(),
        EMPTY_SCHEMA);
    entityTypeService.create(
        "AsDomainType", List.of("Asset", "AsNamed", "AsDomain"), Optional.empty(), EMPTY_SCHEMA);
    entityTypeService.create(
        "AsTableType", List.of("Asset", "AsNamed", "AsTable"), Optional.empty(), EMPTY_SCHEMA);
    // An aggregate root that is not an asset, and a plain type outside the aggregate model.
    entityTypeService.create(
        "AsReportType",
        List.of("Aggregate", "AsNamed", "AsDomain"),
        Optional.empty(),
        EMPTY_SCHEMA);
    entityTypeService.create(
        "AsNoteType", List.of("AsNamed", "AsNote"), Optional.empty(), EMPTY_SCHEMA);
    // What a column materialises as: derived by a mapping, never authored.
    entityTypeService.create("AsResourceType", List.of("AsNamed"), Optional.empty(), EMPTY_SCHEMA);
    getApplicationContext()
        .getBean(MappingService.class)
        .create(
            "AsColumnType",
            "AsResourceType",
            """
            { "name": "#source.getValue('$.name').asText() + '-resource'" }""",
            List.of());
  }

  /**
   * The rules are the trait-level {@code DEPENDS_ON} declarations projected onto the types: from an
   * asset type, towards anything that stands on its own — another asset type, an aggregate root
   * type, a plain type — and nothing else. Not the reverse direction, and never from a type that is
   * not an asset.
   */
  @Test
  void testDependencyRulesGoFromAssetTypesToAssetsAggregatesAndPlainTypes() {
    var rules =
        getApplicationContext().getBean(AggregateSchemaService.class).assetDependencyRules();

    Assertions.assertEquals(
        List.of(
            "AsTableType -> AsDomainType ASSET [AsTable DEPENDS_ON AsDomain]",
            "AsTableType -> AsNoteType ENTITY [AsTable DEPENDS_ON AsNote]",
            "AsTableType -> AsReportType AGGREGATE [AsTable DEPENDS_ON AsDomain]"),
        rules.stream()
            .map(
                rule ->
                    rule.source().getName()
                        + " -> "
                        + rule.target().getName()
                        + " "
                        + rule.targetKind()
                        + " "
                        + rule.sanctionedBy().stream().map(AssetCatalogTests::describe).toList())
            .toList());
  }

  /**
   * The catalog lists the roots of asset types, each with everything its root depends on — an
   * asset, an aggregate or a plain entity alike — and, apart from the assets, the entities an asset
   * may be made to depend on.
   */
  @Test
  void testCatalogListsAssetsTheirDependenciesAndWhatTheyMayDependOn() {
    var entityService = getApplicationContext().getBean(EntityService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

    var domain = entityService.create("AsDomainType", named("sales"));
    var table = entityService.create("AsTableType", named("orders"));
    var report = entityService.create("AsReportType", named("weekly"));
    var note = entityService.create("AsNoteType", named("todo"));
    var column = entityService.create("AsColumnType", named("id"));
    entityService.link(table.getId(), DEPENDS_ON, domain.getId());
    entityService.link(table.getId(), DEPENDS_ON, note.getId());
    entityService.link(table.getId(), DEPENDS_ON, report.getId());

    var assets = aggregateService.assets();
    var ids = assets.stream().map(asset -> asset.entity().getId()).toList();
    Assertions.assertTrue(ids.containsAll(List.of(domain.getId(), table.getId())), ids.toString());
    Assertions.assertFalse(ids.contains(report.getId()));
    Assertions.assertFalse(ids.contains(note.getId()));

    var listedTable =
        assets.stream()
            .filter(asset -> asset.entity().getId().equals(table.getId()))
            .findFirst()
            .orElseThrow();
    Assertions.assertEquals(
        java.util.Set.of(domain.getId(), note.getId(), report.getId()),
        java.util.Set.copyOf(listedTable.dependencies()));

    // What is not an asset but may be depended on: the aggregate and the plain entity, each told
    // apart. Not the asset, and not a part, which never stands on its own.
    var targets =
        aggregateService.assetDependencyTargets().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    target -> target.entity().getId(), AggregateService.DependencyTarget::kind));
    Assertions.assertEquals(
        AggregateSchemaService.DependencyTargetKind.AGGREGATE, targets.get(report.getId()));
    Assertions.assertEquals(
        AggregateSchemaService.DependencyTargetKind.ENTITY, targets.get(note.getId()));
    Assertions.assertFalse(targets.containsKey(domain.getId()));
    Assertions.assertFalse(targets.containsKey(column.getId()));

    // What the rules do not list, linking refuses — the two answers come from the same model.
    Assertions.assertThrows(
        ServiceError.class, () -> entityService.link(domain.getId(), DEPENDS_ON, table.getId()));
  }

  /**
   * An asset is listed with the entities mappings derived from its members, each with the member it
   * came from. A part of another asset, and its derived entity, belong to that other asset.
   */
  @Test
  @org.junit.jupiter.api.Timeout(60)
  void testCatalogListsTheEntitiesMappedFromAnAssetsMembers() {
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var bulkLoader = getApplicationContext().getBean(BulkLoaderService.class);
    var ids =
        bulkLoader.bulkAggregateCreation(
            new java.io.ByteArrayInputStream(
                """
                entityType: AsTableType
                values: { name: mapped-orders }
                parts:
                  - entityType: AsColumnType
                    values: { name: amount }
                ---
                entityType: AsTableType
                values: { name: mapped-customers }
                parts:
                  - entityType: AsColumnType
                    values: { name: email }
                """
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    // The mapping updater derives the resources a moment after the load.
    org.awaitility.Awaitility.await()
        .atMost(org.awaitility.Durations.TEN_SECONDS)
        .until(
            () ->
                aggregateService.assets().stream()
                    .filter(asset -> ids.contains(asset.entity().getId()))
                    .allMatch(asset -> asset.mapped().size() == 1));

    var orders =
        aggregateService.assets().stream()
            .filter(asset -> asset.entity().getId().equals(ids.getFirst()))
            .findFirst()
            .orElseThrow();
    var mapped = orders.mapped().getFirst();
    Assertions.assertEquals("AsResourceType", mapped.entity().getEntityType().getName());
    Assertions.assertEquals("amount-resource", mapped.entity().getValues().get("name").asText());
    Assertions.assertEquals("AsColumnType", mapped.source().getEntityType().getName());
    Assertions.assertEquals("amount", mapped.source().getValues().get("name").asText());
  }

  /**
   * An asset is listed with everything it contains, at any depth: each part with the entity
   * containing it and with what it depends on. A dependency between two parts is the parts' own —
   * it is not reported as a dependency of the asset.
   */
  @Test
  void testCatalogListsAnAssetsPartsWithTheirContainerAndTheirDependencies() {
    var aggregateService = getApplicationContext().getBean(AggregateService.class);
    var bulkLoader = getApplicationContext().getBean(BulkLoaderService.class);
    var id =
        bulkLoader
            .bulkAggregateCreation(
                new java.io.ByteArrayInputStream(
                    """
                    entityType: AsTableType
                    values: { name: structured }
                    parts:
                      - ref: key
                        entityType: AsColumnType
                        values: { name: key }
                      - entityType: AsGroupType
                        values: { name: address }
                        parts:
                          - entityType: AsColumnType
                            values: { name: city }
                            dependsOn: [key]
                    """
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .getFirst();

    var asset =
        aggregateService.assets().stream()
            .filter(listed -> listed.entity().getId().equals(id))
            .findFirst()
            .orElseThrow();
    Assertions.assertEquals(List.of(), asset.dependencies());

    var parts = new java.util.HashMap<String, AggregateService.Part>();
    asset.parts().forEach(part -> parts.put(part.entity().getValues().get("name").asText(), part));
    Assertions.assertEquals(java.util.Set.of("key", "address", "city"), parts.keySet());
    Assertions.assertEquals(id, parts.get("key").parentId());
    Assertions.assertEquals(id, parts.get("address").parentId());
    Assertions.assertEquals(
        parts.get("address").entity().getId(),
        parts.get("city").parentId(),
        "a part of a part is listed under the part containing it");
    Assertions.assertEquals(
        List.of(parts.get("key").entity().getId()), parts.get("city").dependencies());
    Assertions.assertEquals(List.of(), parts.get("key").dependencies());
    Assertions.assertEquals("AsGroupType", parts.get("address").entity().getEntityType().getName());
    // A container is listed before what it contains, so a reader can build the tree in one pass.
    var order = asset.parts().stream().map(part -> part.entity().getId()).toList();
    Assertions.assertTrue(
        order.indexOf(parts.get("address").entity().getId())
            < order.indexOf(parts.get("city").entity().getId()));
  }

  private static String named(String name) {
    return "{\"name\": \"" + name + "\"}";
  }

  private static String describe(TraitRelationship relationship) {
    return relationship.getSource().getName()
        + " "
        + relationship.getRelationType()
        + " "
        + relationship.getTarget().getName();
  }
}
