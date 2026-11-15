package it.davidgreco.metacatalog.ui;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Aggregate;
import it.davidgreco.metacatalog.openapi.model.Asset;
import it.davidgreco.metacatalog.openapi.model.AssetDependencyRule;
import it.davidgreco.metacatalog.openapi.model.AssetDependencyTarget;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import it.davidgreco.metacatalog.openapi.model.LinkEntityRequest;
import it.davidgreco.metacatalog.openapi.model.Mapping;
import it.davidgreco.metacatalog.openapi.model.ProcedureStatus;
import it.davidgreco.metacatalog.openapi.model.TraitRelationship;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Route / model / API-interaction tests for {@link AssetUiController} using a standalone MockMvc
 * setup with a mocked {@link MetacatalogApiDelegate} (no Spring context or database required).
 *
 * <p>The catalog every test starts from: two tables and a view, which are assets, and a {@code
 * sales} domain, which is a plain entity. The {@code orders} table depends on the domain, and the
 * model lets a table depend on a domain or on another table — and nothing else on anything.
 */
class AssetUiControllerTest {

  private static final String TABLE_YAML =
      """
      ---
      id: "table-1"
      entityType: "TableType"
      values:
        name: "orders"
        provisioningStatus: "PROVISIONED"
      dependencies:
      - "domain-1"
      parts:
      - id: "col-1"
        entityType: "ColumnType"
        values:
          name: "id"
        dependencies: []
      """;

  private final MetacatalogApiDelegate api = mock(MetacatalogApiDelegate.class);
  private final ObjectMapper jsonMapper = new ObjectMapper();
  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
  private MockMvc mockMvc;

  private static Asset asset(String id, String type, String values, String... dependencies) {
    var asset = new Asset();
    asset.setId(id);
    asset.setEntityType(type);
    asset.setValues(values);
    asset.setDependencies(List.of(dependencies));
    return asset;
  }

  private static EntityType entityType(String name) {
    var type = new EntityType();
    type.setName(name);
    return type;
  }

  private static Aggregate node(String id, String type, String values, Aggregate... parts) {
    var aggregate = new Aggregate();
    aggregate.setId(Optional.of(id));
    aggregate.setEntityType(type);
    aggregate.setValues(values);
    aggregate.setParts(List.of(parts));
    return aggregate;
  }

  private static AssetDependencyRule rule(
      String target, AssetDependencyRule.TargetKindEnum kind, String targetTrait) {
    var sanction = new TraitRelationship();
    sanction.setSourceTrait(Optional.of("Table"));
    sanction.setRelationType(Optional.of("DEPENDS_ON"));
    sanction.setTargetTrait(Optional.of(targetTrait));
    var rule = new AssetDependencyRule();
    rule.setSourceEntityType("TableType");
    rule.setTargetEntityType(target);
    rule.setTargetKind(kind);
    rule.setSanctionedBy(List.of(sanction));
    return rule;
  }

  @BeforeEach
  void setUp() {
    var domain = new AssetDependencyTarget();
    domain.setId("domain-1");
    domain.setEntityType("DomainType");
    domain.setValues("{\"name\":\"sales\"}");
    domain.setKind(AssetDependencyTarget.KindEnum.ENTITY);
    var domainType = entityType("DomainType");
    domainType.setSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");

    // What the orders table materialises as: derived from its column by a mapping, and unnamed.
    var derived = new it.davidgreco.metacatalog.openapi.model.AssetMappedEntity();
    derived.setId("res-1");
    derived.setEntityType("AthenaTableType");
    derived.setValues("{\"table\":\"orders\",\"provisioningStatus\":\"PROVISIONED\"}");
    derived.setSourceEntityId("col-1");
    derived.setSourceEntityType("ColumnType");
    derived.setSourceValues(Optional.of("{\"name\":\"id\"}"));

    // The orders table's inside: two columns, the second depending on the first.
    var id = new it.davidgreco.metacatalog.openapi.model.AssetPart();
    id.setId("col-1");
    id.setEntityType("ColumnType");
    id.setValues("{\"name\":\"id\"}");
    id.setParentId("table-1");
    var total = new it.davidgreco.metacatalog.openapi.model.AssetPart();
    total.setId("col-2");
    total.setEntityType("ColumnType");
    total.setValues("{}");
    total.setParentId("table-1");
    total.setDependencies(List.of("col-1"));

    given(api.listAssets())
        .willReturn(
            ResponseEntity.ok(
                List.of(
                    asset(
                            "table-1",
                            "TableType",
                            "{\"name\":\"orders\",\"provisioningStatus\":\"PROVISIONED\"}",
                            "domain-1")
                        .parts(List.of(id, total))
                        .mapped(List.of(derived)),
                    asset("table-2", "TableType", "{}", "table-1"),
                    asset("view-1", "ViewType", "{\"name\":\"report\"}"))));
    given(api.listAssetDependencyTargets()).willReturn(ResponseEntity.ok(List.of(domain)));
    given(api.listAssetDependencyRules())
        .willReturn(
            ResponseEntity.ok(
                List.of(
                    rule("DomainType", AssetDependencyRule.TargetKindEnum.ENTITY, "Domain"),
                    rule("TableType", AssetDependencyRule.TargetKindEnum.ASSET, "Table"))));
    given(api.listAssetTypes())
        .willReturn(ResponseEntity.ok(List.of(entityType("TableType"), entityType("ViewType"))));
    given(api.listEntityTypes())
        .willReturn(
            ResponseEntity.ok(
                List.of(domainType, entityType("TableType"), entityType("ViewType"))));
    given(api.listMappings()).willReturn(ResponseEntity.ok(List.of()));
    given(api.getAggregateAsYaml(eq("table-1"), any()))
        .willReturn(
            ResponseEntity.ok(new ByteArrayResource(TABLE_YAML.getBytes(StandardCharsets.UTF_8))));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new RootController(),
                new AssetUiController(
                    api, jsonMapper, yamlMapper, new HtmlSafeJsonSerializer(jsonMapper)))
            .setViewResolvers(
                new org.springframework.web.servlet.view.InternalResourceViewResolver(
                    "/WEB-INF/views/", ".jsp"))
            .build();
  }

  /** The UI opens on the asset catalog: {@code /ui} is the address everything else points at. */
  @Test
  void theUiStartsOnTheAssetCatalog() throws Exception {
    mockMvc
        .perform(get("/ui"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets"));
  }

  /** The pages that moved under {@code /ui/admin} still answer at the address they had. */
  @Test
  void addressesFromBeforeTheSplitAreForwarded() throws Exception {
    mockMvc
        .perform(get("/ui/instances/ent-1/edit"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/admin/instances/ent-1/edit"));
    mockMvc
        .perform(get("/ui/graph").queryParam("showEntities", "true"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/admin/graph?showEntities=true"));
    mockMvc
        .perform(get("/ui/aggregates"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/admin/aggregates"));
  }

  /**
   * The catalog lists assets only, names each, reads its statuses out of its values and says what
   * stands on both sides of its dependencies — an asset or not. What an asset may depend on without
   * being one is kept apart, for the graph.
   */
  @Test
  void catalogListsAssetsAndKeepsWhatTheyDependOnApart() throws Exception {
    var result =
        mockMvc
            .perform(get("/ui/assets"))
            .andExpect(status().isOk())
            .andExpect(view().name("assets-catalog"))
            .andExpect(model().attribute("view", "list"))
            .andReturn();

    var catalog = (AssetCatalogView) result.getModelAndView().getModel().get("catalog");
    Assertions.assertEquals(List.of("TableType", "ViewType"), catalog.types());
    Assertions.assertEquals(
        List.of("table-1", "table-2", "view-1"),
        catalog.assets().stream().map(AssetCatalogView.Row::id).toList());

    var sales =
        new AssetCatalogView.Ref(
            "domain-1", "sales", "DomainType", "entity", "/ui/admin/instances/domain-1");
    Assertions.assertEquals(List.of(sales), catalog.targets());
    Assertions.assertNull(catalog.row("domain-1"), "a domain is not an asset");
    Assertions.assertEquals(sales, catalog.node("domain-1"));

    var table = catalog.row("table-1");
    Assertions.assertEquals("orders", table.name());
    Assertions.assertEquals("PROVISIONED", table.provisioningStatus());
    Assertions.assertNull(table.authorizationStatus());
    Assertions.assertEquals(List.of(sales), table.dependsOn());
    // An asset with no name of its own is shown under its id rather than blank.
    Assertions.assertEquals(
        List.of(
            new AssetCatalogView.Ref(
                "table-2", "table-2", "TableType", "asset", "/ui/assets/table-2")),
        table.requiredBy());

    // The entities mapped from the asset's members come with it: named by their type when they
    // have no name, with the member they came from and the way into the mapping that made them.
    Assertions.assertEquals(
        List.of(
            new AssetCatalogView.Mapped(
                "res-1",
                "AthenaTableType",
                "AthenaTableType",
                "PROVISIONED",
                null,
                "col-1",
                "id",
                "ColumnType",
                "/ui/admin/instances/res-1",
                "/ui/admin/mappings/new?source=ColumnType&target=AthenaTableType")),
        table.mapped());
    Assertions.assertTrue(catalog.row("table-2").mapped().isEmpty());

    // So does the asset's inside: each part with what contains it and what it depends on, named
    // by its type when it has no name — which is what the graph draws below the asset.
    Assertions.assertEquals(
        List.of(
            new AssetCatalogView.Part(
                "col-1", "id", "ColumnType", "table-1", List.of(), "/ui/admin/instances/col-1"),
            new AssetCatalogView.Part(
                "col-2",
                "ColumnType",
                "ColumnType",
                "table-1",
                List.of("col-1"),
                "/ui/admin/instances/col-2")),
        table.parts());
    Assertions.assertTrue(catalog.row("table-2").parts().isEmpty());

    Assertions.assertTrue(catalog.allows("TableType", "DomainType"));
    Assertions.assertTrue(catalog.allows("TableType", "TableType"));
    Assertions.assertFalse(catalog.allows("TableType", "ViewType"));
    Assertions.assertFalse(catalog.allows("ViewType", "TableType"));
    Assertions.assertEquals("entity", catalog.rules().getFirst().targetKind());
    Assertions.assertEquals(
        List.of("Table DEPENDS_ON Domain"), catalog.rules().getFirst().sanctionedBy());

    // A new node may be an asset of any asset type, or anything some asset may depend on.
    Assertions.assertEquals(
        List.of("TableType:asset", "ViewType:asset", "DomainType:entity"),
        catalog.creatable().stream().map(c -> c.type() + ":" + c.kind()).toList());
    Assertions.assertTrue(
        catalog.creatable().getLast().schema().contains("\"name\""),
        "the dialog builds its fields from the type's schema");
  }

  @Test
  void catalogCanOpenOnTheGraph() throws Exception {
    mockMvc
        .perform(get("/ui/assets").param("view", "graph").param("type", "TableType"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("view", "graph"))
        .andExpect(model().attribute("selectedType", "TableType"));
  }

  /**
   * A drop the model allows becomes a DEPENDS_ON link — here onto a plain entity — and the answer
   * is the refreshed catalog.
   */
  @Test
  void droppingAnAssetOnSomethingAllowedLinksThem() throws Exception {
    mockMvc
        .perform(
            post("/ui/assets/dependencies").param("source", "table-2").param("target", "domain-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.catalog.assets.length()").value(3));

    var request = ArgumentCaptor.forClass(LinkEntityRequest.class);
    verify(api).linkEntity(request.capture());
    Assertions.assertEquals("table-2", request.getValue().getSourceEntityId());
    Assertions.assertEquals("DEPENDS_ON", request.getValue().getRelationshipTypeName());
    Assertions.assertEquals("domain-1", request.getValue().getTargetEntityId());
  }

  /**
   * A drop the model does not allow is refused before the API is asked, with a reason an asset
   * manager can act on — the API's own answer is "Relationship is not legit". So is one that does
   * not start at an asset: an entity or an aggregate is depended on, it does not depend.
   */
  @Test
  void aDropTheModelForbidsIsRefusedWithTheReason() throws Exception {
    mockMvc
        .perform(
            post("/ui/assets/dependencies").param("source", "table-2").param("target", "view-1"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value(
                    org.hamcrest.Matchers.containsString(
                        "A TableType asset cannot depend on a ViewType asset")));
    mockMvc
        .perform(
            post("/ui/assets/dependencies").param("source", "domain-1").param("target", "table-1"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value(
                    org.hamcrest.Matchers.containsString("Only an asset can be made to depend")));
    mockMvc
        .perform(
            post("/ui/assets/dependencies").param("source", "table-2").param("target", "nowhere"))
        .andExpect(status().isBadRequest());

    verify(api, never()).linkEntity(any());
  }

  /**
   * The board's dialog creates the root of a new node: an asset, or something an asset may depend
   * on. Any other type is refused — the board is not a second instance editor.
   */
  @Test
  void aNodeIsCreatedFromTheBoardOnlyForTypesThatBelongOnIt() throws Exception {
    mockMvc
        .perform(
            post("/ui/assets/entities")
                .param("type", "DomainType")
                .param("values", "{\"name\":\"hr\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.catalog.targets.length()").value(1));

    var created = ArgumentCaptor.forClass(it.davidgreco.metacatalog.openapi.model.Entity.class);
    verify(api).createEntity(created.capture());
    Assertions.assertEquals("DomainType", created.getValue().getEntityType());
    Assertions.assertEquals("{\"name\":\"hr\"}", created.getValue().getValues());

    mockMvc
        .perform(post("/ui/assets/entities").param("type", "ColumnType").param("values", "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value(org.hamcrest.Matchers.containsString("neither an asset type nor a type")));
    verify(api, org.mockito.Mockito.times(1)).createEntity(any());
  }

  /** What the API refuses — a cycle, say — is reported as it says it. */
  @Test
  void aLinkTheApiRefusesIsReported() throws Exception {
    doThrow(new RuntimeException("Loops are not allowed")).when(api).linkEntity(any());

    mockMvc
        .perform(
            post("/ui/assets/dependencies").param("source", "table-2").param("target", "domain-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Loops are not allowed"));
  }

  @Test
  void removingADependencyUnlinksTheTwoRoots() throws Exception {
    mockMvc
        .perform(
            post("/ui/assets/dependencies/delete")
                .param("source", "table-1")
                .param("target", "domain-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.catalog").exists());

    verify(api).unlinkEntity("table-1", "DEPENDS_ON", "domain-1");
  }

  /**
   * The asset page tells parts from the entities mappings derived, which the API lists together,
   * and offers as new dependencies only what the model allows and is not there yet.
   */
  @Test
  void assetPageSeparatesPartsFromDerivedResources() throws Exception {
    var column = node("col-1", "ColumnType", "{\"name\":\"id\"}");
    var resource = node("res-1", "AthenaTableType", "{\"provisioningStatus\":\"PROVISIONED\"}");
    given(api.getAggregate("table-1", Boolean.TRUE))
        .willReturn(
            ResponseEntity.ok(
                node("table-1", "TableType", "{\"name\":\"orders\"}", column, resource)));
    given(api.getAggregate("table-1", Boolean.FALSE))
        .willReturn(
            ResponseEntity.ok(node("table-1", "TableType", "{\"name\":\"orders\"}", column)));
    var mapping = new Mapping();
    mapping.setSourceEntityType("TableType");
    mapping.setTargetEntityType("AthenaTableType");
    mapping.setMappingValues("{}");
    mapping.setEntityPathReferences("[{\"alias\":\"d\",\"referencePath\":\"IS_REQUIRED_BY{$}\"}]");
    var unrelated = new Mapping();
    unrelated.setSourceEntityType("OtherType");
    unrelated.setTargetEntityType("AthenaTableType");
    unrelated.setMappingValues("{}");
    unrelated.setEntityPathReferences("[]");
    given(api.listMappings()).willReturn(ResponseEntity.ok(List.of(mapping, unrelated)));

    var result =
        mockMvc
            .perform(get("/ui/assets/table-1"))
            .andExpect(status().isOk())
            .andExpect(view().name("asset-view"))
            .andReturn();

    var detail = (AssetDetailView) result.getModelAndView().getModel().get("detail");
    Assertions.assertEquals("orders", detail.asset().name());
    Assertions.assertEquals(
        List.of("id"), detail.root().parts().stream().map(AssetDetailView.Node::name).toList());
    Assertions.assertEquals(
        List.of("AthenaTableType"),
        detail.root().derived().stream().map(AssetDetailView.Node::entityType).toList());
    Assertions.assertEquals("PROVISIONED", detail.root().derived().getFirst().provisioningStatus());
    Assertions.assertEquals(2, detail.root().descendants());

    // What it depends on is named whatever it is, and linked to the page that shows it.
    Assertions.assertEquals(
        List.of("/ui/admin/instances/domain-1"),
        detail.asset().dependsOn().stream().map(AssetCatalogView.Ref::href).toList());
    // It already depends on the domain, so what is left to offer is the other table — not the
    // view, which the model does not let a table depend on.
    Assertions.assertEquals(
        List.of("table-2"), detail.candidates().stream().map(AssetCatalogView.Ref::id).toList());

    Assertions.assertEquals(1, detail.mappings().size());
    Assertions.assertEquals("TableType", detail.mappings().getFirst().source());
    Assertions.assertTrue(detail.mappings().getFirst().walksDependencies());

    Assertions.assertEquals(5, detail.actions().size());
    Assertions.assertTrue(
        ((String) result.getModelAndView().getModel().get("yaml")).startsWith("id: \"table-1\""));
  }

  /** These pages are for assets: an id that is not one is sent back rather than shown. */
  @Test
  void anIdThatIsNotAnAssetIsNotShown() throws Exception {
    mockMvc
        .perform(get("/ui/assets/not-an-asset"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets"))
        .andExpect(flash().attributeExists("error"));
  }

  /**
   * The builder edits {@code dependsOn}; the served document lists {@code dependencies}. The edit
   * form moves one to the other and keeps every id, which is what tells the update which member is
   * which.
   */
  @Test
  void editFormStartsFromTheAssetWithItsIdsAndDependencies() throws Exception {
    given(api.getAggregateSchema("TableType")).willReturn(ResponseEntity.ok(Map.of()));

    var result =
        mockMvc
            .perform(get("/ui/assets/table-1/edit"))
            .andExpect(status().isOk())
            .andExpect(view().name("asset-form"))
            .andExpect(model().attributeExists("asset", "aggregateSchema"))
            .andReturn();

    var form = (AggregateForm) result.getModelAndView().getModel().get("aggregateForm");
    Assertions.assertEquals("TableType", form.getRootType());
    var document = jsonMapper.readTree(form.getDocument());
    Assertions.assertEquals("table-1", document.get("id").asText());
    Assertions.assertEquals("domain-1", document.get("dependsOn").get(0).asText());
    Assertions.assertFalse(document.has("dependencies"));
    var part = document.get("parts").get(0);
    Assertions.assertEquals("col-1", part.get("id").asText());
    Assertions.assertTrue(part.get("dependsOn").isEmpty());
  }

  /** Saving sends the whole document to the update endpoint, as YAML. */
  @Test
  void savingUpdatesTheAssetAsAWhole() throws Exception {
    mockMvc
        .perform(
            post("/ui/assets/table-1")
                .param("rootType", "TableType")
                .param(
                    "document",
                    "{\"id\":\"table-1\",\"entityType\":\"TableType\",\"values\":{\"name\":\"renamed\"}}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets/table-1"))
        .andExpect(flash().attribute("message", "Asset updated."));

    var body = ArgumentCaptor.forClass(Resource.class);
    verify(api).updateAggregateAsYaml(eq("table-1"), body.capture());
    var sent = yamlMapper.readTree(body.getValue().getInputStream());
    Assertions.assertEquals("renamed", sent.at("/values/name").asText());
    Assertions.assertEquals("table-1", sent.get("id").asText());
  }

  /** A refused save comes back to the form with what was entered, not to a blank one. */
  @Test
  void aRefusedSaveKeepsTheDocument() throws Exception {
    doThrow(new RuntimeException("an update cannot move a part"))
        .when(api)
        .updateAggregateAsYaml(eq("table-1"), any());
    var document = "{\"id\":\"table-1\",\"entityType\":\"TableType\",\"values\":{}}";

    var result =
        mockMvc
            .perform(
                post("/ui/assets/table-1")
                    .param("rootType", "TableType")
                    .param("document", document))
            .andExpect(status().isOk())
            .andExpect(view().name("asset-form"))
            .andExpect(model().attribute("error", "an update cannot move a part"))
            .andReturn();

    var form = (AggregateForm) result.getModelAndView().getModel().get("aggregateForm");
    Assertions.assertEquals(document, form.getDocument());
  }

  @Test
  void creatingSendsTheDocumentAsYaml() throws Exception {
    mockMvc
        .perform(
            post("/ui/assets")
                .param("rootType", "ViewType")
                .param("document", "{\"entityType\":\"ViewType\",\"values\":{\"name\":\"hr\"}}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets"));

    var body = ArgumentCaptor.forClass(Resource.class);
    verify(api).createAggregateAsYaml(body.capture());
    Assertions.assertEquals(
        "hr", yamlMapper.readTree(body.getValue().getInputStream()).at("/values/name").asText());
  }

  /** The builder offers asset types, not every aggregate root type. */
  @Test
  void theNewAssetFormOffersAssetTypesOnly() throws Exception {
    mockMvc
        .perform(get("/ui/assets/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("asset-form"))
        .andExpect(model().attribute("rootTypes", List.of("TableType", "ViewType")));

    verify(api, never()).listAggregateRootTypes();
  }

  /** The download is the document the API serves, as a file named after the asset. */
  @Test
  void downloadServesTheYamlAsAFile() throws Exception {
    mockMvc
        .perform(get("/ui/assets/table-1/yaml"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string("Content-Disposition", org.hamcrest.Matchers.containsString("orders.yaml")))
        .andExpect(content().string(TABLE_YAML));

    verify(api).getAggregateAsYaml("table-1", false);

    mockMvc.perform(get("/ui/assets/not-an-asset/yaml")).andExpect(status().isNotFound());
  }

  /** An uploaded file creates assets; uploaded onto an asset, it updates that asset. */
  @Test
  void uploadedYamlCreatesOrUpdates() throws Exception {
    var file =
        new MockMultipartFile(
            "file", "orders.yaml", "application/yaml", TABLE_YAML.getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(multipart("/ui/assets/upload").file(file))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets"));
    verify(api).createAggregateAsYaml(any());

    mockMvc
        .perform(multipart("/ui/assets/table-1/yaml").file(file))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets/table-1"))
        .andExpect(flash().attribute("message", "Asset updated from the uploaded YAML."));
    verify(api).updateAggregateAsYaml(eq("table-1"), any());
  }

  @Test
  void anUploadWithNothingInItIsRefused() throws Exception {
    mockMvc
        .perform(multipart("/ui/assets/upload").param("yamlText", " "))
        .andExpect(status().isOk())
        .andExpect(view().name("asset-upload"))
        .andExpect(model().attributeExists("error"));

    verify(api, never()).createAggregateAsYaml(any());
  }

  /** A procedure is launched asynchronously and its schedule id handed to the asset's page. */
  @Test
  void provisioningComesBackToTheAssetWithTheRunToFollow() throws Exception {
    given(api.provisionAggregate("table-1", Optional.of(Boolean.TRUE)))
        .willReturn(
            ResponseEntity.accepted()
                .body(
                    new ProcedureStatus()
                        .scheduleId("run-1")
                        .status(ProcedureStatus.StatusEnum.RUNNING)));

    mockMvc
        .perform(post("/ui/assets/table-1/procedure/provision"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets/table-1"))
        .andExpect(flash().attribute("procedureScheduleId", "run-1"))
        .andExpect(flash().attribute("procedureLabel", "Provisioning"));
  }

  /** Only the four procedures are reachable through the procedure route. */
  @Test
  void anUnknownProcedureIsRefused() throws Exception {
    mockMvc
        .perform(post("/ui/assets/table-1/procedure/delete-aggregate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets/table-1"))
        .andExpect(flash().attributeExists("error"));

    verify(api, never()).deleteAggregate(any());
  }

  /** A delete goes back to the catalog; a refused one stays on the asset, with the reason. */
  @Test
  void deletingReturnsToTheCatalogUnlessRefused() throws Exception {
    mockMvc
        .perform(post("/ui/assets/table-1/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets"))
        .andExpect(flash().attribute("message", "Asset deleted."));

    doThrow(new RuntimeException("is linked to entity with id: x outside the aggregate"))
        .when(api)
        .deleteAggregate("view-1");
    mockMvc
        .perform(post("/ui/assets/view-1/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets/view-1"))
        .andExpect(flash().attributeExists("error"));
  }

  /** The picker on an asset's page adds a dependency the same way a drop does. */
  @Test
  void theAssetPagePickerAddsADependency() throws Exception {
    mockMvc
        .perform(post("/ui/assets/table-2/dependencies").param("target", "domain-1"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets/table-2"))
        .andExpect(flash().attribute("message", "Dependency added."));
    verify(api).linkEntity(any());

    mockMvc
        .perform(post("/ui/assets/table-1/dependencies/delete").param("target", "domain-1"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/assets/table-1"));
    verify(api).unlinkEntity("table-1", "DEPENDS_ON", "domain-1");
  }
}
