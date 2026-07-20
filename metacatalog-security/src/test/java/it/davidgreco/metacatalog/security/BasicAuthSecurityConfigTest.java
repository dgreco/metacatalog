package it.davidgreco.metacatalog.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies HTTP Basic auth: requests without credentials are rejected (401), requests with valid
 * credentials are accepted (200), and requests with wrong credentials are rejected (401).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "application.config.security.auth-mode=basic",
      "application.config.security.basic.users[0].username=admin",
      "application.config.security.basic.users[0].password={noop}secret",
      "application.config.security.basic.users[0].roles[0]=USER"
    })
class BasicAuthSecurityConfigTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void withoutCredentialsReturnsUnauthorized() throws Exception {
    mockMvc.perform(get("/metacatalog/v1/test")).andExpect(status().isUnauthorized());
  }

  @Test
  void withValidCredentialsReturnsOk() throws Exception {
    mockMvc
        .perform(get("/metacatalog/v1/test").with(httpBasic("admin", "secret")))
        .andExpect(status().isOk())
        .andExpect(content().string("ok"));
  }

  @Test
  void withWrongCredentialsReturnsUnauthorized() throws Exception {
    mockMvc
        .perform(get("/metacatalog/v1/test").with(httpBasic("admin", "wrong")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void publicEndpointIsAccessibleWithoutCredentials() throws Exception {
    // /actuator/health is not mapped in this test context, but security permits it through:
    // the response is a 404 (not a 401), proving auth was not required.
    mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
  }

  @Test
  void sparqlQueryEndpointRequiresAuthentication() throws Exception {
    // No SPARQL controller is mapped in this test context, but security fires first: a 401 proves
    // auth is required, and a 404 (not 401) with credentials proves the request passed security.
    mockMvc
        .perform(get("/sparql/query").param("query", "ASK { ?s ?p ?o }"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            get("/sparql/query")
                .param("query", "ASK { ?s ?p ?o }")
                .with(httpBasic("admin", "secret")))
        .andExpect(status().isNotFound());
  }
}
