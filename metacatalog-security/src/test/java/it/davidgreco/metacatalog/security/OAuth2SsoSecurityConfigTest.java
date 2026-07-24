package it.davidgreco.metacatalog.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies the OAuth2 SSO flow: when {@code client-id} is configured alongside {@code issuer-uri},
 * the UI chain redirects unauthenticated browser requests to the IdP, the API chain reuses the
 * resulting session (so Swagger "Try it out" is authenticated without re-authentication), and
 * programmatic clients can still authenticate with JWT bearer tokens.
 *
 * <p>The {@link JwtDecoder} and {@link ClientRegistrationRepository} production beans are replaced
 * with mocks to avoid OIDC discovery HTTP calls to a real IdP at startup. The mock {@link
 * ClientRegistrationRepository} is configured in {@link #setUp()} to return a manually-built {@link
 * ClientRegistration} so that {@code oauth2Login()} can resolve the authorization URL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "application.config.security.auth-mode=oauth2",
      "application.config.security.oauth2.issuer-uri=http://localhost:0/realms/test",
      "application.config.security.oauth2.client-id=test-client",
      "application.config.security.oauth2.client-secret=test-secret"
    })
class OAuth2SsoSecurityConfigTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtDecoder jwtDecoder;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Configures the mock {@link ClientRegistrationRepository} to return a manually-built {@link
   * ClientRegistration} so the {@code oauth2Login()} filter can build the authorization redirect
   * URL.
   */
  @BeforeEach
  void setUp() {
    final ClientRegistration registration =
        ClientRegistration.withRegistrationId("metacatalog")
            .clientId("test-client")
            .clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile", "email")
            .authorizationUri("http://localhost:0/realms/test/protocol/openid-connect/auth")
            .tokenUri("http://localhost:0/realms/test/protocol/openid-connect/token")
            .jwkSetUri("http://localhost:0/realms/test/protocol/openid-connect/certs")
            .issuerUri("http://localhost:0/realms/test")
            .userInfoUri("http://localhost:0/realms/test/protocol/openid-connect/userinfo")
            .userNameAttributeName("sub")
            .clientName("Test")
            .build();
    org.mockito.Mockito.when(clientRegistrationRepository.findByRegistrationId("metacatalog"))
        .thenReturn(registration);
  }

  /**
   * Unauthenticated browser request to the UI is redirected (302) to the OAuth2 authorization
   * initiation endpoint ({@code /oauth2/authorization/metacatalog}), which in turn redirects to the
   * IdP. This proves the {@code oauth2Login()} flow is wired — in resource-server-only mode (no
   * {@code client-id}) the same request returns a 401.
   */
  @Test
  void uiRedirectsToIdpWhenUnauthenticated() throws Exception {
    mockMvc
        .perform(get("/ui/"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/oauth2/authorization/metacatalog"));
  }

  /**
   * Programmatic client with a JWT bearer token is accepted by the API chain (resource server
   * coexists with SSO).
   */
  @Test
  void apiAcceptsJwtBearerToken() throws Exception {
    mockMvc
        .perform(get("/metacatalog/v1/test").with(jwt().jwt(b -> b.claim("sub", "test-user"))))
        .andExpect(status().isOk())
        .andExpect(content().string("ok"));
  }

  /** API rejects requests without a bearer token and without a session (401). */
  @Test
  void apiRejectsRequestsWithoutTokenOrSession() throws Exception {
    mockMvc.perform(get("/metacatalog/v1/test")).andExpect(status().isUnauthorized());
  }

  /**
   * After an OIDC login, the API chain reuses the session — a Swagger "Try it out" XHR is
   * authenticated without a bearer token, just like {@code basic}/{@code ldap} mode.
   *
   * <p>The OIDC user is simulated by placing an {@link OidcUser}-backed {@link Authentication} into
   * the HTTP session under {@code SPRING_SECURITY_CONTEXT}, which is exactly what {@code
   * oauth2Login()} does after a successful callback.
   */
  @Test
  void apiReusesOidcSessionWithoutBearerToken() throws Exception {
    final OidcUser oidcUser = testOidcUser();
    final Authentication auth =
        new TestingAuthenticationToken(
            oidcUser, null, AuthorityUtils.createAuthorityList("ROLE_USER"));
    auth.setAuthenticated(true);
    final SecurityContext ctx = new SecurityContextImpl(auth);

    final MvcResult result =
        mockMvc
            .perform(get("/metacatalog/v1/test").sessionAttr("SPRING_SECURITY_CONTEXT", ctx))
            .andExpect(status().isOk())
            .andExpect(content().string("ok"))
            .andReturn();

    // The session was not freshly created by the API chain (NEVER policy).
    assert result.getRequest().getSession(false) != null;
  }

  private static OidcUser testOidcUser() {
    final OidcIdToken idToken =
        OidcIdToken.withTokenValue("token")
            .issuer("http://localhost:0/realms/test")
            .subject("test-user")
            .build();
    return new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), idToken, "sub");
  }
}
