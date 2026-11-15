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

    // A part type, so that the types below root an aggregate at all.
    entityTypeService.create(
        "AsColumnType", List.of("AggregateElement", "AsNamed"), Optional.empty(), EMPTY_SCHEMA);
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
  }

  /**
   * The rules are the trait-level {@code DEPENDS_ON} declarations projected onto the asset types,
   * and nothing else: not the reverse direction, and not a pair involving a type that is not an
   * asset.
   */
  @Test
  void testDependencyRulesAreTheSanctionedPairsOfAssetTypes() {
    var rules =
        getApplicationContext().getBean(AggregateSchemaService.class).assetDependencyRules();

    Assertions.assertEquals(1, rules.size(), rules.toString());
    var rule = rules.getFirst();
    Assertions.assertEquals("AsTableType", rule.source().getName());
    Assertions.assertEquals("AsDomainType", rule.target().getName());
    Assertions.assertEquals(
        List.of("AsTable DEPENDS_ON AsDomain"),
        rule.sanctionedBy().stream().map(AssetCatalogTests::describe).toList());
  }

  /**
   * The catalog lists the roots of asset types with the assets each depends on. A link to something
   * that is not an asset is a link of the entity, not a dependency between assets.
   */
  @Test
  void testCatalogListsAssetsAndTheDependenciesBetweenThem() {
    var entityService = getApplicationContext().getBean(EntityService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

    var domain = entityService.create("AsDomainType", named("sales"));
    var table = entityService.create("AsTableType", named("orders"));
    var report = entityService.create("AsReportType", named("weekly"));
    var note = entityService.create("AsNoteType", named("todo"));
    entityService.link(table.getId(), DEPENDS_ON, domain.getId());
    entityService.link(table.getId(), DEPENDS_ON, note.getId());

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
    Assertions.assertEquals(List.of(domain.getId()), listedTable.dependencies());

    // What the rules do not list, linking refuses — the two answers come from the same model.
    Assertions.assertThrows(
        ServiceError.class, () -> entityService.link(domain.getId(), DEPENDS_ON, table.getId()));
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
