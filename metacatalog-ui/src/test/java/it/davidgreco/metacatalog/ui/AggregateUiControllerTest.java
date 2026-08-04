package it.davidgreco.metacatalog.ui;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Route / model / API-interaction tests for {@link AggregateUiController} using a standalone
 * MockMvc setup with a mocked {@link MetacatalogApiDelegate} (no Spring context or database
 * required).
 */
class AggregateUiControllerTest {

  private final MetacatalogApiDelegate api = mock(MetacatalogApiDelegate.class);
  private final ObjectMapper jsonMapper = new ObjectMapper();
  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
  private MockMvc mockMvc;

  private static final String SCHEMA_JSON =
      """
      {
        "type": "object",
        "properties": {
          "entityType": { "type": "string", "enum": ["ProductType"] },
          "values": { "type": "object", "properties": { "name": { "type": "string" } } }
        },
        "$defs": {
          "ProductType": { "type": "object" }
        }
      }
      """;

  private static EntityType entityType(String name) {
    var type = new EntityType();
    type.setName(name);
    return type;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> schemaMap() throws Exception {
    return jsonMapper.readValue(SCHEMA_JSON, Map.class);
  }

  @BeforeEach
  void setUp() throws Exception {
    given(api.listAggregateRootTypes())
        .willReturn(ResponseEntity.ok(List.of(entityType("ProductType"))));
    given(api.getAggregateSchema("ProductType")).willReturn(ResponseEntity.ok(schemaMap()));
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new AggregateUiController(
                    api, jsonMapper, yamlMapper, new HtmlSafeJsonSerializer(jsonMapper)))
            .setViewResolvers(
                new org.springframework.web.servlet.view.InternalResourceViewResolver(
                    "/WEB-INF/views/", ".jsp"))
            .build();
  }

  @Test
  void formRendersWithRootTypes() throws Exception {
    mockMvc
        .perform(get("/ui/aggregates/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("aggregate-form"))
        .andExpect(model().attributeExists("aggregateForm", "rootTypes"))
        .andExpect(model().attribute("rootTypes", List.of("ProductType")));
  }

  /** Selecting a root type embeds its combined schema so the page can build the tree. */
  @Test
  void formEmbedsTheSchemaOfTheSelectedRootType() throws Exception {
    mockMvc
        .perform(get("/ui/aggregates/new").param("rootType", "ProductType"))
        .andExpect(status().isOk())
        .andExpect(view().name("aggregate-form"))
        .andExpect(model().attributeExists("aggregateSchema"))
        .andExpect(model().attribute("selectedRootType", "ProductType"));
  }

  /** A type that is not an aggregate root surfaces as a form error rather than a 500. */
  @Test
  void formShowsAnErrorWhenTheSchemaCannotBeBuilt() throws Exception {
    given(api.getAggregateSchema("LonelyType"))
        .willThrow(new RuntimeException("EntityType LonelyType is not an aggregate root"));

    mockMvc
        .perform(get("/ui/aggregates/new").param("rootType", "LonelyType"))
        .andExpect(status().isOk())
        .andExpect(view().name("aggregate-form"))
        .andExpect(model().attributeExists("error"))
        .andExpect(model().attributeDoesNotExist("aggregateSchema"));
  }

  /**
   * The document the page submits is JSON; the aggregate endpoint consumes YAML, so the controller
   * must convert it. The captured payload is parsed back to prove the conversion preserved the
   * tree.
   */
  @Test
  void createConvertsTheJsonDocumentToYamlAndRedirects() throws Exception {
    var document =
        """
        {"entityType":"ProductType","values":{"name":"dp"},"ref":"p1",\
        "parts":[{"entityType":"PartType","values":{"name":"part"}}]}""";

    mockMvc
        .perform(
            post("/ui/aggregates").param("rootType", "ProductType").param("document", document))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui/instances"));

    var captor = org.mockito.ArgumentCaptor.forClass(Resource.class);
    verify(api).createAggregateAsYaml(captor.capture());

    var yaml =
        new String(captor.getValue().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    var parsed = yamlMapper.readTree(yaml);
    Assertions.assertEquals("ProductType", parsed.get("entityType").asText());
    Assertions.assertEquals("dp", parsed.get("values").get("name").asText());
    Assertions.assertEquals("p1", parsed.get("ref").asText());
    Assertions.assertEquals("PartType", parsed.get("parts").get(0).get("entityType").asText());
  }

  /** A malformed document is reported on the form instead of reaching the API. */
  @Test
  void createRejectsAMalformedDocument() throws Exception {
    mockMvc
        .perform(
            post("/ui/aggregates").param("rootType", "ProductType").param("document", "{not json"))
        .andExpect(status().isOk())
        .andExpect(view().name("aggregate-form"))
        .andExpect(model().attributeExists("error"));

    verify(api, org.mockito.Mockito.never()).createAggregateAsYaml(any());
  }

  /** An empty document is refused up-front with a readable message. */
  @Test
  void createRejectsAnEmptyDocument() throws Exception {
    mockMvc
        .perform(post("/ui/aggregates").param("rootType", "ProductType").param("document", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("aggregate-form"))
        .andExpect(model().attribute("error", "The aggregate document is empty."));

    verify(api, org.mockito.Mockito.never()).createAggregateAsYaml(any());
  }

  /** The helper endpoint serves the schema so the page can switch root type without a reload. */
  @Test
  void schemaEndpointServesTheCombinedSchema() throws Exception {
    mockMvc
        .perform(get("/ui/aggregates/schema").param("rootType", "ProductType"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"$defs\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ProductType")));
  }
}
