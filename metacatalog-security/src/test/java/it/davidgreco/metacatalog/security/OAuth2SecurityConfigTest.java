package it.davidgreco.metacatalog.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the OAuth2 resource server: requests without a bearer token are rejected (401), requests
 * with a mock JWT are accepted (200).
 *
 * <p>The {@link org.springframework.security.oauth2.jwt.JwtDecoder} bean is created with a fake JWK
 * set URI; the actual decoder is never invoked because {@code jwt()} mocks the decoded JWT
 * directly.
 *
 * <p>A {@link UserDetailsService} and {@link PasswordEncoder} are provided via {@link
 * BasicAuthProbeConfig} so that the test can verify Basic auth credentials are <em>not</em>
 * accepted in OAuth2 mode. If the shared {@link SecurityFilterChainCustomizer} ever re-adds {@code
 * .httpBasic(...)} unconditionally, the Basic credentials would be validated against this user
 * store and the request would succeed (200); the test asserts it still returns 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "application.config.security.auth-mode=oauth2",
      "application.config.security.oauth2.jwk-set-uri=http://localhost:0/realms/test/protocol/openid-connect/certs"
    })
class OAuth2SecurityConfigTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void withoutTokenReturnsUnauthorized() throws Exception {
    mockMvc.perform(get("/metacatalog/v1/test")).andExpect(status().isUnauthorized());
  }

  @Test
  void withValidJwtReturnsOk() throws Exception {
    mockMvc
        .perform(get("/metacatalog/v1/test").with(jwt().jwt(b -> b.claim("sub", "test-user"))))
        .andExpect(status().isOk())
        .andExpect(content().string("ok"));
  }

  @Test
  void sparqlQueryEndpointRequiresJwt() throws Exception {
    // No SPARQL controller is mapped in this test context, but security fires first: a 401 proves
    // auth is required, and a 404 (not 401) with a JWT proves the request passed security.
    mockMvc
        .perform(get("/sparql/query").param("query", "ASK { ?s ?p ?o }"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            get("/sparql/query")
                .param("query", "ASK { ?s ?p ?o }")
                .with(jwt().jwt(b -> b.claim("sub", "test-user"))))
        .andExpect(status().isNotFound());
  }

  /**
   * Verifies that the management UI at {@code /ui/**} is not open in oauth2 mode. Without a JWT
   * bearer token the request must be rejected (401), not permitted. This prevents state-changing
   * POSTs (create/delete trait, entity-type, mapping, bulk) from being unauthenticated when a
   * protected profile is deployed with {@code auth-mode: oauth2}.
   */
  @Test
  void uiPathRequiresAuthenticationInOauth2Mode() throws Exception {
    mockMvc.perform(get("/ui/")).andExpect(status().isUnauthorized());
  }

  /**
   * Verifies that Basic auth credentials are rejected in OAuth2 mode even when a {@link
   * UserDetailsService} is available. The {@link SecurityFilterChainCustomizer} must not wire
   * {@code .httpBasic(...)} in OAuth2 mode; only JWT bearer tokens should be accepted. If the
   * customizer re-introduces unconditional {@code httpBasic}, the Basic credentials would be
   * validated against the {@link BasicAuthProbeConfig#userDetailsService() in-memory user store}
   * and the request would return 200 instead of 401.
   */
  @Test
  void basicAuthCredentialsAreRejectedEvenWithUserDetailsServiceAvailable() throws Exception {
    mockMvc
        .perform(get("/metacatalog/v1/test").with(httpBasic("admin", "secret")))
        .andExpect(status().isUnauthorized());
  }

  /**
   * Provides a minimal in-memory {@link UserDetailsService} and {@link PasswordEncoder} so that the
   * test can detect a regression where {@code .httpBasic(...)} is unconditionally applied by the
   * shared {@link SecurityFilterChainCustomizer}. Without this config, Basic auth would fail with
   * 401 regardless of whether the filter is wired (no user store to validate against), making the
   * test unable to distinguish the fixed and broken states.
   */
  @TestConfiguration
  static class BasicAuthProbeConfig {
    @Bean
    UserDetailsService userDetailsService() {
      var manager = new InMemoryUserDetailsManager();
      manager.createUser(User.withUsername("admin").password("{noop}secret").roles("USER").build());
      return manager;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
      return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
  }
}
