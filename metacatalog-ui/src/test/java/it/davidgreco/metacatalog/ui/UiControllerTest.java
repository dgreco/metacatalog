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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.service.BulkLoaderService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TraitService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
  private final BulkLoaderService bulkLoaderService = mock(BulkLoaderService.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    given(traitService.list()).willReturn(List.of());
    given(entityTypeService.list()).willReturn(List.of());
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new UiController(traitService, entityTypeService, bulkLoaderService))
            .build();
  }

  @Test
  void dashboardRenders() throws Exception {
    mockMvc
        .perform(get("/ui"))
        .andExpect(status().isOk())
        .andExpect(view().name("index"))
        .andExpect(model().attributeExists("traits", "entityTypes"));
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
}
