package it.davidgreco.metacatalog.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
 * Verifies the OAuth2 resource server: requests without a bearer token are rejected (401), requests
 * with a mock JWT are accepted (200).
 *
 * <p>The {@link org.springframework.security.oauth2.jwt.JwtDecoder} bean is created with a fake JWK
 * set URI; the actual decoder is never invoked because {@code jwt()} mocks the decoded JWT
 * directly.
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
}
