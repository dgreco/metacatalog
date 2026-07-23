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
import it.davidgreco.metacatalog.openapi.model.Trait;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.MappingService;
import it.davidgreco.metacatalog.service.TraitService;
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
 * <p>The graph-page tests still mock the core services because {@link CatalogGraphService} uses
 * them directly (TODO: move to REST API once the spec has list-all endpoints).
 */
class UiControllerTest {

  private final MetacatalogApiDelegate api = mock(MetacatalogApiDelegate.class);
  private final TraitService traitService = mock(TraitService.class);
  private final EntityTypeService entityTypeService = mock(EntityTypeService.class);
  private final EntityService entityService = mock(EntityService.class);
  private final MappingService mappingService = mock(MappingService.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    given(api.listTraits()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listEntityTypes()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listMappings()).willReturn(ResponseEntity.ok(List.of()));
    given(api.listTraitRelationships()).willReturn(ResponseEntity.ok(List.of()));
    given(entityTypeService.list()).willReturn(List.of());
    given(entityTypeService.listAllVersions()).willReturn(List.of());
    given(traitService.listAllVersions()).willReturn(List.of());
    given(entityService.listAll()).willReturn(List.of());
    given(entityService.listAllRelationships()).willReturn(List.of());
    given(mappingService.listAllEntityRelationships()).willReturn(List.of());
    var catalogGraphService =
        new CatalogGraphService(
            traitService,
            entityTypeService,
            entityService,
            mappingService,
            new HtmlSafeJsonSerializer(mapper),
            mapper);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new GraphUiController(api, catalogGraphService),
                new TraitUiController(api, catalogGraphService),
                new EntityTypeUiController(api),
                new MappingUiController(api, mapper),
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
        .andExpect(model().attributeExists("mappingForm", "entityTypes", "mappings"));
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
  void entityInstanceViewRenders() throws Exception {
    var entity = new Entity();
    entity.setEntityType("Person");
    entity.setValues("{\"name\":\"Alice\"}");
    given(api.getEntity("ent-1")).willReturn(ResponseEntity.ok(entity));

    mockMvc
        .perform(get("/ui/instances/ent-1"))
        .andExpect(status().isOk())
        .andExpect(view().name("instances-view"))
        .andExpect(model().attributeExists("entity", "valuesJson", "entityId"));
  }
}
