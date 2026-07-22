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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship.EntityPathReference;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitVersion;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.MappingService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TraitService;
import it.davidgreco.metacatalog.service.VersionResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Route / model / service-interaction tests for {@link UiController} using a standalone MockMvc
 * setup with mocked domain services (no Spring context or database required). End-to-end template
 * rendering is exercised by running the full application.
 */
class UiControllerTest {

  private final TraitService traitService = mock(TraitService.class);
  private final EntityTypeService entityTypeService = mock(EntityTypeService.class);
  private final EntityService entityService = mock(EntityService.class);
  private final BulkLoaderService bulkLoaderService = mock(BulkLoaderService.class);
  private final MappingService mappingService = mock(MappingService.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    given(traitService.list()).willReturn(List.of());
    given(entityTypeService.list()).willReturn(List.of());
    given(mappingService.list()).willReturn(List.of());
    given(entityTypeService.listAllVersions()).willReturn(List.of());
    given(traitService.listAllVersions()).willReturn(List.of());
    given(entityService.listAll()).willReturn(List.of());
    given(entityService.listAllRelationships()).willReturn(List.of());
    given(mappingService.listAllEntityRelationships()).willReturn(List.of());
    var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var catalogGraphService =
        new CatalogGraphService(
            traitService,
            entityTypeService,
            entityService,
            mappingService,
            new HtmlSafeJsonSerializer(objectMapper),
            objectMapper);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new GraphUiController(
                    traitService,
                    entityTypeService,
                    mappingService,
                    catalogGraphService,
                    objectMapper),
                new TraitUiController(traitService, catalogGraphService),
                new EntityTypeUiController(entityTypeService, traitService),
                new MappingUiController(mappingService, entityTypeService, objectMapper),
                new BulkUiController(bulkLoaderService))
            .setViewResolvers(
                new org.springframework.web.servlet.view.InternalResourceViewResolver(
                    "/WEB-INF/views/", ".jsp"))
            .build();
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
  void createTraitSubmitsToServiceAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/traits")
                .param("name", "Timestamped")
                .param("father", "")
                .param("schema", "{\"type\":\"object\",\"properties\":{}}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(traitService)
        .create(
            eq("Timestamped"),
            eq(Optional.of("{\"type\":\"object\",\"properties\":{}}")),
            eq(Optional.empty()));
  }

  @Test
  void createEntityTypeSubmitsToServiceAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/entity-types")
                .param("name", "Person")
                .param("father", "")
                .param("traits", "Timestamped")
                .param("schema", "{\"type\":\"object\",\"properties\":{}}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(entityTypeService)
        .create(
            eq("Person"),
            eq(List.of("Timestamped")),
            eq(Optional.empty()),
            eq("{\"type\":\"object\",\"properties\":{}}"));
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
  void graphDataIncludesEntitiesWhenShowEntitiesTrue() throws Exception {
    var type = new EntityType();
    type.setName("Person");
    given(entityTypeService.list()).willReturn(List.of(type));

    var entity = new Entity();
    entity.setId("ent-1");
    entity.setEntityType(type);
    entity.setValues(mapper.readTree("{\"name\":\"Alice\"}"));
    given(entityService.listAll()).willReturn(List.of(entity));

    mockMvc
        .perform(get("/ui/graph/data").param("showEntities", "true"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("entity:ent-1"),
                        org.hamcrest.Matchers.containsString("\"instance-of\""),
                        org.hamcrest.Matchers.containsString("\"Alice\""),
                        org.hamcrest.Matchers.containsString("type:Person"))));
  }

  @Test
  void graphDataConnectsEntityToPinnedVersionWhenPinned() throws Exception {
    var type = new EntityType();
    type.setName("Person");
    type.setVersionGroupId("vg-1");
    type.setVersion(1);
    given(entityTypeService.list()).willReturn(List.of(type));

    var snapshot = new EntityTypeVersion();
    snapshot.setId("snap-1");
    snapshot.setVersionGroupId("vg-1");
    snapshot.setVersion(1);
    snapshot.setName("Person");
    given(entityTypeService.listAllVersions()).willReturn(List.of(snapshot));

    var entity = new Entity();
    entity.setId("ent-1");
    entity.setEntityType(type);
    entity.setEntityTypeVersion(snapshot);
    entity.setValues(mapper.readTree("{\"name\":\"Alice\"}"));
    given(entityService.listAll()).willReturn(List.of(entity));

    mockMvc
        .perform(get("/ui/graph/data").param("showEntities", "true"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("entity:ent-1"),
                        org.hamcrest.Matchers.containsString("type-version:snap-1"),
                        org.hamcrest.Matchers.containsString("\"instance-of\""),
                        org.hamcrest.Matchers.containsString("Person (v1)"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "\"source\":\"entity:ent-1\",\"target\":\"type-version:snap-1\"")));
  }

  @Test
  void graphDataOmitsEntitiesByDefault() throws Exception {
    var type = new EntityType();
    type.setName("Person");
    given(entityTypeService.list()).willReturn(List.of(type));

    var entity = new Entity();
    entity.setId("ent-1");
    entity.setEntityType(type);
    entity.setValues(mapper.readTree("{\"name\":\"Alice\"}"));
    given(entityService.listAll()).willReturn(List.of(entity));

    mockMvc
        .perform(get("/ui/graph/data"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("entity:ent-1"))));
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
  void createMappingSubmitsToServiceAndRedirects() throws Exception {
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

    verify(mappingService)
        .create(
            "Source",
            "Target",
            "{\"n\":\"#source.n\"}",
            List.of(new EntityPathReference("a", "HAS_PART{$}")));
  }

  @Test
  void createMappingDropsBlankPathReferenceRows() throws Exception {
    mockMvc
        .perform(
            post("/ui/mappings")
                .param("sourceEntityType", "Source")
                .param("targetEntityType", "Target")
                .param("mappingValues", "{}")
                .param("aliases", "a", "")
                .param("referencePaths", "HAS_PART{$}", "DEPENDS_ON{$}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(mappingService)
        .create("Source", "Target", "{}", List.of(new EntityPathReference("a", "HAS_PART{$}")));
  }

  @Test
  void createMappingReRendersFormOnServiceError() throws Exception {
    doThrow(new ServiceError("Loops are not allowed"))
        .when(mappingService)
        .create(eq("Source"), eq("Target"), eq("{}"), eq(List.of()));

    mockMvc
        .perform(
            post("/ui/mappings")
                .param("sourceEntityType", "Source")
                .param("targetEntityType", "Target")
                .param("mappingValues", "{}"))
        .andExpect(status().isOk())
        .andExpect(view().name("mapping-form"))
        .andExpect(model().attribute("error", "Loops are not allowed"));
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
  void createTraitLinkSubmitsToServiceAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/trait-links")
                .param("sourceTrait", "A")
                .param("relationshipType", "DEPENDS_ON")
                .param("targetTrait", "B"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(traitService).link("A", RelationType.DEPENDS_ON, "B");
  }

  @Test
  void createSelfReferentialTraitLinkIsForwardedToService() throws Exception {
    mockMvc
        .perform(
            post("/ui/trait-links")
                .param("sourceTrait", "A")
                .param("relationshipType", "DEPENDS_ON")
                .param("targetTrait", "A"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(traitService).link("A", RelationType.DEPENDS_ON, "A");
  }

  @Test
  void createTraitLinkReRendersFormOnServiceError() throws Exception {
    doThrow(new ServiceError("Loops are not allowed"))
        .when(traitService)
        .link(eq("A"), eq(RelationType.DEPENDS_ON), eq("B"));

    mockMvc
        .perform(
            post("/ui/trait-links")
                .param("sourceTrait", "A")
                .param("relationshipType", "DEPENDS_ON")
                .param("targetTrait", "B"))
        .andExpect(status().isOk())
        .andExpect(view().name("trait-link-form"))
        .andExpect(model().attribute("error", "Loops are not allowed"));
  }

  @Test
  void deleteTraitLinkSubmitsToServiceAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/ui/trait-links/delete")
                .param("sourceTrait", "A")
                .param("relationshipType", "HAS_PART")
                .param("targetTrait", "B"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(traitService).unlink("A", RelationType.HAS_PART, "B");
  }

  @Test
  void deleteTraitDelegatesToServiceAndRedirects() throws Exception {
    mockMvc
        .perform(post("/ui/traits/delete").param("name", "Timestamped"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(traitService).delete("Timestamped");
  }

  @Test
  void deleteEntityTypeDelegatesToServiceAndRedirects() throws Exception {
    mockMvc
        .perform(post("/ui/entity-types/delete").param("name", "Person"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(entityTypeService).delete("Person");
  }

  @Test
  void deleteMappingDelegatesToServiceAndRedirects() throws Exception {
    mockMvc
        .perform(post("/ui/mappings/delete").param("id", "abc-123"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(mappingService).delete("abc-123");
  }

  @Test
  void deleteMappingInUseShowsFriendlyError() throws Exception {
    doThrow(new org.springframework.dao.DataIntegrityViolationException("violates foreign key"))
        .when(mappingService)
        .delete("abc-123");

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
  void deleteTraitInUseShowsFriendlyError() throws Exception {
    doThrow(new ServiceError("violates foreign key constraint \"fk_trait_on_father\""))
        .when(traitService)
        .delete("Aggregate");

    mockMvc
        .perform(post("/ui/traits/delete").param("name", "Aggregate"))
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
  void deleteMissingTraitShowsNotFoundError() throws Exception {
    doThrow(new ServiceError("Trait Ghost not found")).when(traitService).delete("Ghost");

    mockMvc
        .perform(post("/ui/traits/delete").param("name", "Ghost"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("error", "Trait 'Ghost' was not found."));
  }

  @Test
  void bulkUploadFileDelegatesToService() throws Exception {
    var yaml = "Traits:\n  - name: WithName\n";
    var file = new MockMultipartFile("file", "model.yaml", "application/x-yaml", yaml.getBytes());

    mockMvc
        .perform(multipart("/ui/bulk").file(file))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    var captor = ArgumentCaptor.forClass(java.io.InputStream.class);
    verify(bulkLoaderService).bulkModelCreation(captor.capture());
    var received = new String(captor.getValue().readAllBytes(), StandardCharsets.UTF_8);
    org.junit.jupiter.api.Assertions.assertEquals(yaml, received);
  }

  @Test
  void bulkUploadPastedTextDelegatesToService() throws Exception {
    var yaml = "Traits:\n  - name: WithName\n";

    mockMvc
        .perform(post("/ui/bulk").param("yamlText", yaml))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    var captor = ArgumentCaptor.forClass(java.io.InputStream.class);
    verify(bulkLoaderService).bulkModelCreation(captor.capture());
    var received = new String(captor.getValue().readAllBytes(), StandardCharsets.UTF_8);
    org.junit.jupiter.api.Assertions.assertEquals(yaml, received);
  }

  @Test
  void bulkUploadAggregatesDelegatesToAggregateService() throws Exception {
    var yaml = "entityType: DataProductType\nvalues:\n  name: dp1\n";
    given(bulkLoaderService.bulkAggregateCreation(any())).willReturn(List.of("id-1", "id-2"));

    mockMvc
        .perform(post("/ui/bulk").param("yamlText", yaml).param("kind", "aggregates"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));

    verify(bulkLoaderService).bulkAggregateCreation(any());
    verify(bulkLoaderService, never()).bulkModelCreation(any());
  }

  @Test
  void bulkUploadWithNoInputReRendersFormWithError() throws Exception {
    mockMvc
        .perform(post("/ui/bulk"))
        .andExpect(status().isOk())
        .andExpect(view().name("bulk-form"))
        .andExpect(model().attributeExists("error"));

    verify(bulkLoaderService, never()).bulkModelCreation(any());
  }

  @Test
  void bulkUploadReRendersFormOnServiceError() throws Exception {
    doThrow(new ServiceError("bad document"))
        .when(bulkLoaderService)
        .bulkModelCreation(any(ByteArrayInputStream.class));

    mockMvc
        .perform(post("/ui/bulk").param("yamlText", "not: valid: yaml"))
        .andExpect(status().isOk())
        .andExpect(view().name("bulk-form"))
        .andExpect(model().attribute("error", "bad document"));
  }

  @Test
  void createTraitReRendersFormOnServiceError() throws Exception {
    doThrow(new ServiceError("Trait already exists"))
        .when(traitService)
        .create(any(), any(), any());

    mockMvc
        .perform(post("/ui/traits").param("name", "Dup").param("schema", "{}"))
        .andExpect(status().isOk())
        .andExpect(view().name("trait-form"))
        .andExpect(model().attribute("error", "Trait already exists"));
  }

  // --- entity-type versioning -----------------------------------------------------

  @Test
  void entityTypeVersionFormRendersPreFilledFromLiveType() throws Exception {
    var live = new EntityType();
    live.setName("Person");
    live.setVersion(1);
    live.setBaseSchema(
        mapper.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"string\"}}}"));
    var father = new EntityType();
    father.setName("Base");
    live.setFather(father);
    var trait = new Trait();
    trait.setName("Timestamped");
    live.setTraits(List.of(trait));
    given(entityTypeService.read("Person")).willReturn(live);

    mockMvc
        .perform(get("/ui/entity-types/Person/versions/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("entity-type-version-form"))
        .andExpect(model().attribute("currentVersion", 1))
        .andExpect(model().attributeExists("entityTypeVersionForm", "entityTypes", "traits"));

    verify(entityTypeService).read("Person");
  }

  @Test
  void entityTypeVersionFormRedirectsWhenTypeMissing() throws Exception {
    given(entityTypeService.read("Ghost"))
        .willThrow(new ServiceError("EntityType Ghost not found"));

    mockMvc
        .perform(get("/ui/entity-types/Ghost/versions/new"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"))
        .andExpect(flash().attribute("error", "EntityType Ghost not found"));
  }

  @Test
  void createEntityTypeVersionSubmitsToServiceAndRedirectsToVersionsList() throws Exception {
    mockMvc
        .perform(
            post("/ui/entity-types/Person/versions")
                .param("name", "Person")
                .param("father", "")
                .param("traits", "Timestamped")
                .param("schema", "{\"type\":\"object\",\"properties\":{}}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/entity-types/Person/versions"));

    verify(entityTypeService)
        .createVersion(
            eq("Person"),
            eq(List.of("Timestamped")),
            eq(Optional.empty()),
            eq("{\"type\":\"object\",\"properties\":{}}"));
  }

  @Test
  void createEntityTypeVersionReRendersFormOnServiceError() throws Exception {
    doThrow(new ServiceError("Trait Missing does not exist"))
        .when(entityTypeService)
        .createVersion(eq("Person"), any(), any(), any());
    var live = new EntityType();
    live.setVersion(1);
    given(entityTypeService.read("Person")).willReturn(live);

    mockMvc
        .perform(
            post("/ui/entity-types/Person/versions").param("name", "Person").param("schema", "{}"))
        .andExpect(status().isOk())
        .andExpect(view().name("entity-type-version-form"))
        .andExpect(model().attribute("error", "Trait Missing does not exist"));
  }

  @Test
  void entityTypeVersionsListRendersSnapshotsAndLive() throws Exception {
    var snap = new EntityTypeVersion();
    snap.setVersion(1);
    snap.setName("Person");
    snap.setFatherName("Base");
    snap.setBaseSchema(
        mapper.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"string\"}}}"));
    snap.setTraits(mapper.readTree("[\"Timestamped\"]"));
    snap.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    var live = new EntityType();
    live.setVersion(2);
    live.setName("Person");
    live.setBaseSchema(
        mapper.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"string\"}}}"));
    given(entityTypeService.listVersions("Person"))
        .willReturn(List.of(new VersionResult.Snapshot<>(snap), new VersionResult.Live<>(live)));

    mockMvc
        .perform(get("/ui/entity-types/Person/versions"))
        .andExpect(status().isOk())
        .andExpect(view().name("versions"))
        .andExpect(model().attributeExists("versions", "kind", "name", "resource", "showTraits"))
        .andExpect(model().attribute("kind", "Entity Type"))
        .andExpect(model().attribute("showTraits", true));
  }

  // --- trait versioning -----------------------------------------------------------

  @Test
  void traitVersionFormRendersPreFilledFromLiveTrait() throws Exception {
    var live = new Trait();
    live.setName("Timestamped");
    live.setVersion(1);
    live.setBaseSchema(
        mapper.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"string\"}}}"));
    var father = new Trait();
    father.setName("Base");
    live.setFather(father);
    given(traitService.read("Timestamped")).willReturn(live);

    mockMvc
        .perform(get("/ui/traits/Timestamped/versions/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("trait-version-form"))
        .andExpect(model().attribute("currentVersion", 1))
        .andExpect(model().attributeExists("traitVersionForm", "traits"));

    verify(traitService).read("Timestamped");
  }

  @Test
  void createTraitVersionSubmitsToServiceAndRedirectsToVersionsList() throws Exception {
    mockMvc
        .perform(
            post("/ui/traits/Timestamped/versions")
                .param("name", "Timestamped")
                .param("father", "")
                .param("schema", "{\"type\":\"object\",\"properties\":{}}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/traits/Timestamped/versions"));

    verify(traitService)
        .createVersion(
            eq("Timestamped"),
            eq(Optional.of("{\"type\":\"object\",\"properties\":{}}")),
            eq(Optional.empty()));
  }

  @Test
  void createTraitVersionReRendersFormOnServiceError() throws Exception {
    doThrow(new ServiceError("bad schema"))
        .when(traitService)
        .createVersion(eq("Timestamped"), any(), any());
    var live = new Trait();
    live.setVersion(1);
    given(traitService.read("Timestamped")).willReturn(live);

    mockMvc
        .perform(
            post("/ui/traits/Timestamped/versions")
                .param("name", "Timestamped")
                .param("schema", "{}"))
        .andExpect(status().isOk())
        .andExpect(view().name("trait-version-form"))
        .andExpect(model().attribute("error", "bad schema"));
  }

  @Test
  void traitVersionsListRendersSnapshotsAndLive() throws Exception {
    var snap = new TraitVersion();
    snap.setVersion(1);
    snap.setName("Timestamped");
    snap.setFatherName("Base");
    snap.setBaseSchema(
        mapper.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"string\"}}}"));
    snap.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    var live = new Trait();
    live.setVersion(2);
    live.setName("Timestamped");
    live.setBaseSchema(
        mapper.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"string\"}}}"));
    given(traitService.listVersions("Timestamped"))
        .willReturn(List.of(new VersionResult.Snapshot<>(snap), new VersionResult.Live<>(live)));

    mockMvc
        .perform(get("/ui/traits/Timestamped/versions"))
        .andExpect(status().isOk())
        .andExpect(view().name("versions"))
        .andExpect(model().attributeExists("versions", "kind", "name", "resource", "showTraits"))
        .andExpect(model().attribute("kind", "Trait"))
        .andExpect(model().attribute("showTraits", false));
  }

  // --- version deletion ----------------------------------------------------------

  @Test
  void deleteEntityTypeVersionDelegatesToServiceAndRedirectsToVersionsList() throws Exception {
    mockMvc
        .perform(post("/ui/entity-types/Person/versions/1/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/entity-types/Person/versions"));

    verify(entityTypeService).deleteVersion("Person", 1);
  }

  @Test
  void deleteEntityTypeVersionRedirectsWithFlashOnServiceError() throws Exception {
    doThrow(new ServiceError("Cannot delete the current version of EntityType Person"))
        .when(entityTypeService)
        .deleteVersion("Person", 2);

    mockMvc
        .perform(post("/ui/entity-types/Person/versions/2/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/entity-types/Person/versions"))
        .andExpect(
            flash().attribute("error", "Cannot delete the current version of EntityType Person"));
  }

  @Test
  void deleteAllEntityTypeVersionsDelegatesToServiceAndRedirectsToVersionsList() throws Exception {
    mockMvc
        .perform(post("/ui/entity-types/Person/versions/delete-all"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/entity-types/Person/versions"));

    verify(entityTypeService).deleteAllVersions("Person");
  }

  @Test
  void deleteAllEntityTypeVersionsRedirectsWithFlashOnServiceError() throws Exception {
    doThrow(new ServiceError("EntityType Ghost not found"))
        .when(entityTypeService)
        .deleteAllVersions("Ghost");

    mockMvc
        .perform(post("/ui/entity-types/Ghost/versions/delete-all"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/entity-types/Ghost/versions"))
        .andExpect(flash().attribute("error", "EntityType Ghost not found"));
  }

  @Test
  void deleteTraitVersionDelegatesToServiceAndRedirectsToVersionsList() throws Exception {
    mockMvc
        .perform(post("/ui/traits/Timestamped/versions/1/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/traits/Timestamped/versions"));

    verify(traitService).deleteVersion("Timestamped", 1);
  }

  @Test
  void deleteTraitVersionRedirectsWithFlashOnServiceError() throws Exception {
    doThrow(new ServiceError("Cannot delete the current version of Trait Timestamped"))
        .when(traitService)
        .deleteVersion("Timestamped", 2);

    mockMvc
        .perform(post("/ui/traits/Timestamped/versions/2/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/traits/Timestamped/versions"))
        .andExpect(
            flash().attribute("error", "Cannot delete the current version of Trait Timestamped"));
  }

  @Test
  void deleteAllTraitVersionsDelegatesToServiceAndRedirectsToVersionsList() throws Exception {
    mockMvc
        .perform(post("/ui/traits/Timestamped/versions/delete-all"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/traits/Timestamped/versions"));

    verify(traitService).deleteAllVersions("Timestamped");
  }

  @Test
  void deleteAllTraitVersionsRedirectsWithFlashOnServiceError() throws Exception {
    doThrow(new ServiceError("Trait Ghost not found"))
        .when(traitService)
        .deleteAllVersions("Ghost");

    mockMvc
        .perform(post("/ui/traits/Ghost/versions/delete-all"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/traits/Ghost/versions"))
        .andExpect(flash().attribute("error", "Trait Ghost not found"));
  }
}
