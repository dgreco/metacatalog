package it.davidgreco.metacatalog.ui;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Entity;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import it.davidgreco.metacatalog.openapi.model.Mapping;
import it.davidgreco.metacatalog.openapi.model.MappingEntityRelationship;
import it.davidgreco.metacatalog.openapi.model.Trait;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Route / model / API-interaction tests for the UI controllers using a standalone MockMvc setup
 * with a mocked {@link MetacatalogApiDelegate} (no Spring context or database required).
 *
 * <p>The delegate is the only collaborator that needs mocking: every UI read and write goes through
 * the REST API, including the catalog graph.
 */
class UiControllerTest {

  private final MetacatalogApiDelegate api = mock(MetacatalogApiDelegate.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    given(api.listTraits()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listEntityTypes()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listMappings()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listTraitRelationships()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listAllTraitVersions()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listAllEntityTypeVersions()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listEntityRelationships()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listMappingEntityRelationships()).willReturn(ResponseEntity.ok(List.of()));
    given(api.getEntities(any(), any())).willReturn(ResponseEntity.ok(List.of()));
    given(api.listAggregateRootTypes()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listProvisionableTypes()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listEntityTypeVersions(any())).willReturn(ResponseEntity.ok(List.of()));
    var catalogGraphService =
        new CatalogGraphService(api, new HtmlSafeJsonSerializer(mapper), mapper);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new GraphUiController(api, catalogGraphService),
                new TraitUiController(api, catalogGraphService),
                new EntityTypeUiController(api),
                new MappingUiController(api, mapper, new HtmlSafeJsonSerializer(mapper)),
                new BulkUiController(api),
                new UnifiedInstanceController(api))
            .setViewResolvers(
                new org.springframework.web.servlet.view.InternalResourceViewResolver(
                    "/WEB-INF/views/", ".jsp"))
            .build();
  }

  private static RuntimeException apiError(String message) {
    return new RuntimeException(message);
  }

  @Test
  void dashboardRenders() throws Exception {
    mockMvc
        .perform(get("/ui"))
        .andExpect(status().isOk())
        .andExpect(view().name("index"))
        .andExpect(model().attributeExists("traits", "entityTypes", "mappings"));
  }

  @Test
  void traitFormRenders() throws Exception {
    mockMvc
        .perform(get("/ui/traits/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("trait-form"))
        .andExpect(model().attributeExists("traitForm", "traits"));
  }

  @Test
  void entityTypeFormRenders() throws Exception {
    mockMvc
        .perform(get("/ui/entity-types/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("entity-type-form"))
        .andExpect(model().attributeExists("entityTypeForm", "entityTypes", "traits"));
  }

  @Test
  void createTraitSubmitsToApiAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/traits")
                .param("name", "Timestamped")
                .param("father", "")
                .param("schema", "{\"type\":\"object\",\"properties\":{}}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    var captor = org.mockito.ArgumentCaptor.forClass(Trait.class);
    verify(api).createTrait(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("Timestamped", captor.getValue().getName());
  }

  @Test
  void createEntityTypeSubmitsToApiAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/entity-types")
                .param("name", "Person")
                .param("father", "")
                .param("traits", "Timestamped")
                .param("schema", "{\"type\":\"object\",\"properties\":{}}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    var captor = org.mockito.ArgumentCaptor.forClass(EntityType.class);
    verify(api).createEntityType(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("Person", captor.getValue().getName());
  }

  @Test
  void graphRenders() throws Exception {
    mockMvc
        .perform(get("/ui/graph"))
        .andExpect(status().isOk())
        .andExpect(view().name("graph"))
        .andExpect(model().attributeExists("graphJson", "showInverses", "showEntities"));
  }

  @Test
  void mappingFormRenders() throws Exception {
    mockMvc
        .perform(get("/ui/mappings/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("mapping-form"))
        .andExpect(
            model().attributeExists("mappingForm", "entityTypes", "mappings", "mappingsJson"));
  }

  @Test
  void mappingFormEmbedsExistingMappingsForProposals() throws Exception {
    var existing = new Mapping();
    existing.setSourceEntityType("Source");
    existing.setTargetEntityType("Target");
    existing.setSourceEntityTypeVersion(java.util.Optional.of(1));
    existing.setTargetEntityTypeVersion(java.util.Optional.of(2));
    existing.setMappingValues("{\"n\":\"#source.n\"}");
    existing.setEntityPathReferences("[{\"alias\":\"a\",\"referencePath\":\"HAS_PART{$}\"}]");
    given(api.listMappings()).willReturn(ResponseEntity.ok(List.of(existing)));

    var result = mockMvc.perform(get("/ui/mappings/new")).andExpect(status().isOk()).andReturn();

    var mappingsJson = (String) result.getModelAndView().getModel().get("mappingsJson");
    org.junit.jupiter.api.Assertions.assertTrue(mappingsJson.contains("\"source\":\"Source\""));
    org.junit.jupiter.api.Assertions.assertTrue(mappingsJson.contains("\"target\":\"Target\""));
    org.junit.jupiter.api.Assertions.assertTrue(mappingsJson.contains("\"sourceVersion\":1"));
    org.junit.jupiter.api.Assertions.assertTrue(mappingsJson.contains("\"targetVersion\":2"));
    org.junit.jupiter.api.Assertions.assertTrue(mappingsJson.contains("#source.n"));
  }

  @Test
  void createMappingSubmitsToApiAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/mappings")
                .param("sourceEntityType", "Source")
                .param("targetEntityType", "Target")
                .param("mappingValues", "{\"n\":\"#source.n\"}")
                .param("aliases", "a")
                .param("referencePaths", "HAS_PART{$}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    var captor = org.mockito.ArgumentCaptor.forClass(Mapping.class);
    verify(api).createMapping(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Source", captor.getValue().getSourceEntityType());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Target", captor.getValue().getTargetEntityType());
  }

  @Test
  void createMappingReRendersFormOnApiError() throws Exception {
    doThrow(apiError("Loops are not allowed")).when(api).createMapping(any());

    mockMvc
        .perform(
            post("/ui/mappings")
                .param("sourceEntityType", "Source")
                .param("targetEntityType", "Target")
                .param("mappingValues", "{}"))
        .andExpect(status().isOk())
        .andExpect(view().name("mapping-form"))
        .andExpect(
            model()
                .attribute("error", org.hamcrest.Matchers.containsString("Loops are not allowed")));
  }

  @Test
  void traitLinkFormRenders() throws Exception {
    mockMvc
        .perform(get("/ui/trait-links/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("trait-link-form"))
        .andExpect(
            model().attributeExists("traitLinkForm", "traits", "relationTypes", "traitLinks"));
  }

  @Test
  void createTraitLinkSubmitsToApiAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/trait-links")
                .param("sourceTrait", "A")
                .param("relationshipType", "DEPENDS_ON")
                .param("targetTrait", "B"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(api).linkTrait(any());
  }

  @Test
  void createTraitLinkReRendersFormOnApiError() throws Exception {
    doThrow(apiError("Loops are not allowed")).when(api).linkTrait(any());

    mockMvc
        .perform(
            post("/ui/trait-links")
                .param("sourceTrait", "A")
                .param("relationshipType", "DEPENDS_ON")
                .param("targetTrait", "B"))
        .andExpect(status().isOk())
        .andExpect(view().name("trait-link-form"))
        .andExpect(
            model()
                .attribute("error", org.hamcrest.Matchers.containsString("Loops are not allowed")));
  }

  @Test
  void deleteTraitLinkSubmitsToApiAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/trait-links/delete")
                .param("sourceTrait", "A")
                .param("relationshipType", "HAS_PART")
                .param("targetTrait", "B"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(api).unlinkTrait("A", "HAS_PART", "B");
  }

  @Test
  void deleteTraitDelegatesToApiAndRedirects() throws Exception {
    mockMvc
        .perform(post("/ui/traits/delete").param("name", "Timestamped"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(api).deleteTrait("Timestamped");
  }

  @Test
  void deleteEntityTypeDelegatesToApiAndRedirects() throws Exception {
    mockMvc
        .perform(post("/ui/entity-types/delete").param("name", "Person"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(api).deleteEntityType("Person");
  }

  @Test
  void deleteMappingDelegatesToApiAndRedirects() throws Exception {
    mockMvc
        .perform(post("/ui/mappings/delete").param("id", "abc-123"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(api).deleteMapping("abc-123");
  }

  @Test
  void deleteMappingInUseShowsFriendlyError() throws Exception {
    doThrow(apiError("violates foreign key")).when(api).deleteMapping("abc-123");

    mockMvc
        .perform(post("/ui/mappings/delete").param("id", "abc-123"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            flash()
                .attribute(
                    "error",
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("still in use"),
                        org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("foreign key")))));
  }

  @Test
  void createTraitReRendersFormOnApiError() throws Exception {
    doThrow(apiError("Trait already exists")).when(api).createTrait(any());

    mockMvc
        .perform(post("/ui/traits").param("name", "Dup").param("schema", "{}"))
        .andExpect(status().isOk())
        .andExpect(view().name("trait-form"))
        .andExpect(
            model()
                .attribute("error", org.hamcrest.Matchers.containsString("Trait already exists")));
  }

  // --- instances ----------------------------------------------------------------

  @Test
  void instancesListRenders() throws Exception {
    mockMvc
        .perform(get("/ui/instances"))
        .andExpect(status().isOk())
        .andExpect(view().name("instances-list"))
        .andExpect(model().attributeExists("instances", "entityTypes"));
  }

  @Test
  void instancesNewFormRenders() throws Exception {
    mockMvc
        .perform(get("/ui/instances/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("instances-form"))
        .andExpect(model().attributeExists("instanceForm", "entityTypes"));
  }

  @Test
  void createEntityInstanceSubmitsAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/instances")
                .param("entityType", "Person")
                .param("values", "{\"name\":\"Alice\"}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances"));

    var captor = org.mockito.ArgumentCaptor.forClass(Entity.class);
    verify(api).createEntity(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("Person", captor.getValue().getEntityType());
  }

  @Test
  void createInstanceReRendersFormOnApiError() throws Exception {
    doThrow(apiError("Invalid schema")).when(api).createEntity(any());

    mockMvc
        .perform(
            post("/ui/instances")
                .param("entityType", "Person")
                .param("values", "{\"name\":\"Alice\"}"))
        .andExpect(status().isOk())
        .andExpect(view().name("instances-form"))
        .andExpect(
            model().attribute("error", org.hamcrest.Matchers.containsString("Invalid schema")));
  }

  @Test
  void editEntityInstanceFormRenders() throws Exception {
    var entity = new Entity();
    entity.setEntityType("Person");
    entity.setValues("{\"name\":\"Alice\"}");
    given(api.getEntity("ent-1")).willReturn(ResponseEntity.ok(entity));

    mockMvc
        .perform(get("/ui/instances/ent-1/edit"))
        .andExpect(status().isOk())
        .andExpect(view().name("instances-form"))
        .andExpect(model().attribute("instanceId", "ent-1"))
        .andExpect(model().attributeExists("instanceForm", "entityTypes"));
  }

  /**
   * An instance pinned to an older version of its type must be edited against that version's
   * schema, not the live one — the API validates the update against the pinned snapshot, so showing
   * the latest schema would let the user author values the update then rejects.
   */
  @Test
  void editUsesPinnedVersionSchemaWhenInstanceIsOnAnOlderVersion() throws Exception {
    var liveType = new EntityType();
    liveType.setName("Person");
    liveType.setSchema("{\"live\":true}");
    given(api.listEntityTypes()).willReturn(ResponseEntity.ok(List.of(liveType)));

    var snapshot = new EntityType();
    snapshot.setId(java.util.Optional.of("snap-1"));
    snapshot.setName("Person");
    snapshot.setSchema("{\"pinned\":true}");
    snapshot.setVersion(java.util.Optional.of(1));
    given(api.listEntityTypeVersions("Person"))
        .willReturn(ResponseEntity.ok(List.of(snapshot, liveType)));

    var entity = new Entity();
    entity.setEntityType("Person");
    entity.setEntityTypeVersionId("snap-1");
    entity.setValues("{\"name\":\"Alice\"}");
    given(api.getEntity("ent-1")).willReturn(ResponseEntity.ok(entity));

    var result =
        mockMvc.perform(get("/ui/instances/ent-1/edit")).andExpect(status().isOk()).andReturn();

    var model = result.getModelAndView().getModel();
    @SuppressWarnings("unchecked")
    var typeSchemas = (java.util.Map<String, String>) model.get("typeSchemas");
    org.junit.jupiter.api.Assertions.assertEquals("{\"pinned\":true}", typeSchemas.get("Person"));
    org.junit.jupiter.api.Assertions.assertEquals(1, model.get("pinnedVersion"));
  }

  /**
   * An instance pinned to the current live version matches no snapshot in the versions list (the
   * list carries the live row, whose id is the type's, not a snapshot's) and keeps the live schema,
   * with no pinned-version notice.
   */
  @Test
  void editKeepsLiveSchemaWhenInstanceIsOnTheCurrentVersion() throws Exception {
    var liveType = new EntityType();
    liveType.setId(java.util.Optional.of("type-row-id"));
    liveType.setName("Person");
    liveType.setSchema("{\"live\":true}");
    given(api.listEntityTypes()).willReturn(ResponseEntity.ok(List.of(liveType)));
    given(api.listEntityTypeVersions("Person")).willReturn(ResponseEntity.ok(List.of(liveType)));

    var entity = new Entity();
    entity.setEntityType("Person");
    entity.setEntityTypeVersionId("snapshot-of-current-version");
    entity.setValues("{\"name\":\"Alice\"}");
    given(api.getEntity("ent-1")).willReturn(ResponseEntity.ok(entity));

    var result =
        mockMvc.perform(get("/ui/instances/ent-1/edit")).andExpect(status().isOk()).andReturn();

    var model = result.getModelAndView().getModel();
    @SuppressWarnings("unchecked")
    var typeSchemas = (java.util.Map<String, String>) model.get("typeSchemas");
    org.junit.jupiter.api.Assertions.assertEquals("{\"live\":true}", typeSchemas.get("Person"));
    org.junit.jupiter.api.Assertions.assertNull(model.get("pinnedVersion"));
  }

  /**
   * The read-only view page must show the pinned version's schema exactly like the edit page — it
   * renders the same values form, so the live schema would misrepresent an instance on an older
   * version.
   */
  @Test
  void viewUsesPinnedVersionSchemaWhenInstanceIsOnAnOlderVersion() throws Exception {
    var liveType = new EntityType();
    liveType.setName("Person");
    liveType.setSchema("{\"live\":true}");
    given(api.listEntityTypes()).willReturn(ResponseEntity.ok(List.of(liveType)));

    var snapshot = new EntityType();
    snapshot.setId(java.util.Optional.of("snap-1"));
    snapshot.setName("Person");
    snapshot.setSchema("{\"pinned\":true}");
    snapshot.setVersion(java.util.Optional.of(1));
    given(api.listEntityTypeVersions("Person"))
        .willReturn(ResponseEntity.ok(List.of(snapshot, liveType)));

    var entity = new Entity();
    entity.setEntityType("Person");
    entity.setEntityTypeVersionId("snap-1");
    entity.setValues("{\"name\":\"Alice\"}");
    given(api.getEntity("ent-1")).willReturn(ResponseEntity.ok(entity));

    var result = mockMvc.perform(get("/ui/instances/ent-1")).andExpect(status().isOk()).andReturn();

    var model = result.getModelAndView().getModel();
    org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, model.get("readOnly"));
    @SuppressWarnings("unchecked")
    var typeSchemas = (java.util.Map<String, String>) model.get("typeSchemas");
    org.junit.jupiter.api.Assertions.assertEquals("{\"pinned\":true}", typeSchemas.get("Person"));
    org.junit.jupiter.api.Assertions.assertEquals(1, model.get("pinnedVersion"));
  }

  @Test
  void updateEntityInstanceSubmitsAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/instances/ent-1")
                .param("entityType", "Person")
                .param("values", "{\"name\":\"Bob\"}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances"));

    verify(api).updateEntity(eq("ent-1"), any());
  }

  @Test
  void deleteEntityInstanceSubmitsAndRedirects() throws Exception {
    mockMvc
        .perform(post("/ui/instances/ent-1/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances"));

    verify(api).deleteEntity("ent-1");
  }

  @Test
  void deleteAggregateSubmitsAndRedirects() throws Exception {
    mockMvc
        .perform(post("/ui/instances/agg-1/delete-aggregate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances"))
        .andExpect(flash().attribute("message", "Aggregate deleted."));

    verify(api).deleteAggregate("agg-1");
  }

  @Test
  void deleteAggregateSurfacesTheApiError() throws Exception {
    doThrow(apiError("is linked to entity with id: other outside the aggregate"))
        .when(api)
        .deleteAggregate("agg-1");

    mockMvc
        .perform(post("/ui/instances/agg-1/delete-aggregate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances"))
        .andExpect(
            flash()
                .attribute("error", org.hamcrest.Matchers.containsString("outside the aggregate")));
  }

  @Test
  void instancesListMarksOnlyAggregateRootsAsDeletableAsAWhole() throws Exception {
    var root = new Entity();
    root.setId(java.util.Optional.of("agg-1"));
    root.setEntityType("ProductType");
    root.setValues("{\"name\":\"product\"}");
    var part = new Entity();
    part.setId(java.util.Optional.of("ent-2"));
    part.setEntityType("OutputPortType");
    part.setValues("{\"name\":\"port\"}");
    given(api.getEntities(any(), any())).willReturn(ResponseEntity.ok(List.of(root, part)));
    var rootType = new EntityType();
    rootType.setName("ProductType");
    given(api.listAggregateRootTypes()).willReturn(ResponseEntity.ok(List.of(rootType)));

    var rows =
        (List<InstanceRowView>)
            mockMvc
                .perform(get("/ui/instances"))
                .andExpect(status().isOk())
                .andReturn()
                .getModelAndView()
                .getModel()
                .get("instances");

    org.junit.jupiter.api.Assertions.assertTrue(rows.get(0).aggregateRoot());
    org.junit.jupiter.api.Assertions.assertFalse(rows.get(1).aggregateRoot());
  }

  @Test
  void provisionAggregateSubmitsAndRedirects() throws Exception {
    mockMvc
        .perform(post("/ui/instances/agg-1/provision"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances"))
        .andExpect(flash().attribute("message", "Aggregate provisioned."));

    verify(api).provisionAggregate("agg-1");
  }

  @Test
  void unprovisionAggregateSubmitsAndRedirects() throws Exception {
    mockMvc
        .perform(post("/ui/instances/agg-1/unprovision"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances"))
        .andExpect(flash().attribute("message", "Aggregate unprovisioned."));

    verify(api).unprovisionAggregate("agg-1");
  }

  @Test
  void provisionAggregateSurfacesTheApiError() throws Exception {
    doThrow(apiError("No factory for name: S3FolderType")).when(api).provisionAggregate("agg-1");

    mockMvc
        .perform(post("/ui/instances/agg-1/provision"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances"))
        .andExpect(
            flash()
                .attribute("error", org.hamcrest.Matchers.containsString("No factory for name")));
  }

  @Test
  void instancesListOffersProvisioningOnlyForProvisionableTypes() throws Exception {
    var product = new Entity();
    product.setId(java.util.Optional.of("agg-1"));
    product.setEntityType("ProductType");
    product.setValues("{\"name\":\"product\"}");
    var port = new Entity();
    port.setId(java.util.Optional.of("ent-2"));
    port.setEntityType("OutputPortType");
    port.setValues("{\"name\":\"port\"}");
    given(api.getEntities(any(), any())).willReturn(ResponseEntity.ok(List.of(product, port)));
    var provisionable = new EntityType();
    provisionable.setName("ProductType");
    given(api.listProvisionableTypes()).willReturn(ResponseEntity.ok(List.of(provisionable)));

    var rows =
        (List<InstanceRowView>)
            mockMvc
                .perform(get("/ui/instances"))
                .andExpect(status().isOk())
                .andReturn()
                .getModelAndView()
                .getModel()
                .get("instances");

    // Which rows get the buttons comes from the API, not from a guess at the trait chain here.
    org.junit.jupiter.api.Assertions.assertTrue(rows.get(0).provisionable());
    org.junit.jupiter.api.Assertions.assertFalse(rows.get(1).provisionable());
  }

  @Test
  void entityInstanceViewRenders() throws Exception {
    var entity = new Entity();
    entity.setEntityType("Person");
    entity.setValues("{\"name\":\"Alice\"}");
    given(api.getEntity("ent-1")).willReturn(ResponseEntity.ok(entity));

    mockMvc
        .perform(get("/ui/instances/ent-1"))
        .andExpect(status().isOk())
        .andExpect(view().name("instances-form"))
        .andExpect(model().attribute("readOnly", true))
        .andExpect(model().attributeExists("instanceForm", "entityTypes", "typeSchemas"));
  }

  // --- entity links -----------------------------------------------------------

  @Test
  void entityLinkFormRenders() throws Exception {
    mockMvc
        .perform(get("/ui/instances/ent-1/links"))
        .andExpect(status().isOk())
        .andExpect(view().name("entity-link-form"))
        .andExpect(
            model()
                .attributeExists(
                    "entityLinkForm",
                    "instances",
                    "relationTypes",
                    "entityLinks",
                    "mappingLinks",
                    "sourceId"));
  }

  @Test
  void entityLinkFormDoesNotOfferMappingRelationTypes() throws Exception {
    var relationTypes =
        (List<String>)
            mockMvc
                .perform(get("/ui/instances/ent-1/links"))
                .andExpect(status().isOk())
                .andReturn()
                .getModelAndView()
                .getModel()
                .get("relationTypes");

    org.junit.jupiter.api.Assertions.assertEquals(List.of("DEPENDS_ON", "HAS_PART"), relationTypes);
  }

  @Test
  void traitLinkFormDoesNotOfferMappingRelationTypes() throws Exception {
    var relationTypes =
        (List<String>)
            mockMvc
                .perform(get("/ui/trait-links/new"))
                .andExpect(status().isOk())
                .andReturn()
                .getModelAndView()
                .getModel()
                .get("relationTypes");

    org.junit.jupiter.api.Assertions.assertEquals(List.of("DEPENDS_ON", "HAS_PART"), relationTypes);
  }

  @Test
  void entityLinkFormShowsMappingRelationshipsReadOnly() throws Exception {
    var mapped = new MappingEntityRelationship();
    mapped.setSourceEntityId(java.util.Optional.of("ent-1"));
    mapped.setTargetEntityId(java.util.Optional.of("ent-2"));
    mapped.setRelationType(java.util.Optional.of("MAPPED_TO"));
    var inverse = new MappingEntityRelationship();
    inverse.setSourceEntityId(java.util.Optional.of("ent-2"));
    inverse.setTargetEntityId(java.util.Optional.of("ent-1"));
    inverse.setRelationType(java.util.Optional.of("IS_MAPPED_BY"));
    given(api.listMappingEntityRelationships())
        .willReturn(ResponseEntity.ok(List.of(mapped, inverse)));

    var mappingLinks =
        (List<EntityLinkView>)
            mockMvc
                .perform(get("/ui/instances/ent-1/links"))
                .andExpect(status().isOk())
                .andReturn()
                .getModelAndView()
                .getModel()
                .get("mappingLinks");

    // Only the primary direction, so the pair shows once.
    org.junit.jupiter.api.Assertions.assertEquals(1, mappingLinks.size());
    org.junit.jupiter.api.Assertions.assertEquals("MAPPED_TO", mappingLinks.get(0).relationType());
    org.junit.jupiter.api.Assertions.assertEquals("source", mappingLinks.get(0).role());
  }

  @Test
  void createEntityLinkRejectsAMappingRelationType() throws Exception {
    mockMvc
        .perform(
            post("/ui/instances/ent-1/links")
                .param("sourceEntityId", "ent-1")
                .param("relationshipType", "MAPPED_TO")
                .param("targetEntityId", "ent-2"))
        .andExpect(status().isOk())
        .andExpect(view().name("entity-link-form"))
        .andExpect(
            model().attribute("error", org.hamcrest.Matchers.containsString("cannot be created")));

    verify(api, org.mockito.Mockito.never()).linkEntity(any());
  }

  @Test
  void createEntityLinkSubmitsToApiAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/instances/ent-1/links")
                .param("sourceEntityId", "ent-1")
                .param("relationshipType", "DEPENDS_ON")
                .param("targetEntityId", "ent-2"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances/ent-1/links"));

    verify(api).linkEntity(any());
  }

  @Test
  void createEntityLinkReRendersFormOnApiError() throws Exception {
    doThrow(apiError("Loops are not allowed")).when(api).linkEntity(any());

    mockMvc
        .perform(
            post("/ui/instances/ent-1/links")
                .param("sourceEntityId", "ent-1")
                .param("relationshipType", "DEPENDS_ON")
                .param("targetEntityId", "ent-2"))
        .andExpect(status().isOk())
        .andExpect(view().name("entity-link-form"))
        .andExpect(
            model()
                .attribute("error", org.hamcrest.Matchers.containsString("Loops are not allowed")));
  }

  @Test
  void deleteEntityLinkSubmitsToApiAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/instances/ent-1/links/delete")
                .param("sourceEntityId", "ent-1")
                .param("relationshipType", "HAS_PART")
                .param("targetEntityId", "ent-2"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances/ent-1/links"));

    verify(api).unlinkEntity("ent-1", "HAS_PART", "ent-2");
  }
}
