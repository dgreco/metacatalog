package it.davidgreco.metacatalog.ui;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TraitService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    given(traitService.list()).willReturn(List.of());
    given(entityTypeService.list()).willReturn(List.of());
    mockMvc =
        MockMvcBuilders.standaloneSetup(new UiController(traitService, entityTypeService)).build();
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
