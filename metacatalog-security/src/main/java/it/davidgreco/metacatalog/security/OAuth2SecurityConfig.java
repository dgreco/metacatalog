package it.davidgreco.metacatalog.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

/**
 * Security configuration for {@link AuthMode#OAUTH2}.
 *
 * <p>Configures the application as a Spring Security OAuth2 <em>resource server</em> that validates
 * JWT bearer tokens issued by an external IdP such as Keycloak. The JWK set is resolved either from
 * a direct URL ({@link SecurityConfigProperties.Oauth2#jwkSetUri()}) or from the IdP's OpenID
 * Connect metadata via the issuer URI ({@link SecurityConfigProperties.Oauth2#issuerUri()}).
 *
 * <p>When {@code clientId} (and {@code clientSecret}) is additionally configured, <strong>browser
 * SSO</strong> is enabled via the OIDC authorization-code flow. This adds a dedicated, stateful UI
 * filter chain (order 0, {@link #uiSecurityFilterChain}) that redirects unauthenticated browser
 * requests to the IdP. After a successful login the resulting session is reused by the API chain
 * (order 100), so Swagger "Try it out" and SPARQL XHRs are authenticated without re-authentication
 * — mirroring the {@code basic}/{@code ldap} session-reuse behaviour. Programmatic clients continue
 * to authenticate with JWT bearer tokens on every request.
 *
 * <p>When {@code clientId} is not set, the resource server validates JWT bearer tokens only (no
 * browser login flow). A browser hitting the UI gets a 401.
 */
@Configuration
@ConditionalOnAuthMode(AuthMode.OAUTH2)
public class OAuth2SecurityConfig {

  private static final String REGISTRATION_ID = "metacatalog";

  private final SecurityConfigProperties properties;
  private final SecurityFilterChainCustomizer customizer;

  /**
   * Constructs the configuration.
   *
   * @param properties the security configuration properties
   * @param customizer the shared filter-chain customizer
   */
  public OAuth2SecurityConfig(
      final SecurityConfigProperties properties, final SecurityFilterChainCustomizer customizer) {
    this.properties = properties;
    this.customizer = customizer;
  }

  /**
   * Whether SSO is enabled (i.e. {@code client-id} is set on the OAuth2 config).
   *
   * @return {@code true} if browser SSO should be configured
   */
  private boolean isSsoEnabled() {
    return properties.oauth2() != null
        && SecurityConfigSupport.isSet(properties.oauth2().clientId());
  }

  // ────────────────────────── API chain (always present) ──────────────────────────

  /**
   * The {@link SecurityFilterChain} for the REST API and SPARQL protocol endpoint.
   *
   * <p>Validates JWT bearer tokens via {@code oauth2ResourceServer().jwt()}. When SSO is enabled
   * the session policy is {@link SessionCreationPolicy#NEVER}: an existing UI form-login session
   * (from the UI chain) is honoured so Swagger "Try it out" XHRs are authenticated without
   * re-authentication, while programmatic clients sending a bearer token stay effectively
   * stateless. When SSO is disabled the session policy is {@link SessionCreationPolicy#STATELESS}.
   *
   * @param http the security builder
   * @return the configured security filter chain
   * @throws Exception if the security builder cannot be configured
   */
  @Bean
  @Order(100)
  public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
    customizer.customize(
        http, isSsoEnabled() ? SessionCreationPolicy.NEVER : SessionCreationPolicy.STATELESS);
    http.oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
    return http.build();
  }

  // ────────────────────────── UI chain (SSO only) ──────────────────────────

  /**
   * The {@link SecurityFilterChain} for the server-side rendered UI when SSO is enabled.
   *
   * <p>Configures OIDC authorization-code login: unauthenticated browser requests are redirected to
   * the IdP. After a successful login a session is created and the user is sent to {@code /ui}.
   * CSRF stays enabled for the logout POST form. Logout clears the local session and then redirects
   * to the IdP's end-session endpoint (OIDC RP-initiated logout) so the user is logged out of the
   * IdP too.
   *
   * @param http the security builder
   * @param clientRegistrationRepository the OAuth2 client registration repository
   * @return the configured security filter chain
   * @throws Exception if the security builder cannot be configured
   */
  @Bean
  @Order(0)
  @Conditional(SSOEnabledCondition.class)
  public SecurityFilterChain uiSecurityFilterChain(
      final HttpSecurity http, final ClientRegistrationRepository clientRegistrationRepository)
      throws Exception {
    http.securityMatcher(
            SecurityFilterChainCustomizer.UI_PATH,
            SecurityFilterChainCustomizer.SPARQL_UI_PATH,
            "/login",
            "/logout",
            "/oauth2/authorization/**",
            "/login/oauth2/code/**")
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/ui/css/**",
                        "/ui/js/**",
                        "/oauth2/authorization/**",
                        "/login/oauth2/code/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            login ->
                login
                    .loginPage("/oauth2/authorization/" + REGISTRATION_ID)
                    .defaultSuccessUrl("/ui", true)
                    .permitAll())
        .logout(
            logout ->
                logout
                    .logoutUrl("/logout")
                    .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))
                    .permitAll())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
    return http.build();
  }

  // ────────────────────────── Client registration (SSO only) ──────────────────────────

  /**
   * A {@link ClientRegistrationRepository} with a single registration discovered from the issuer's
   * OpenID Connect metadata, configured with the provided {@code client-id} / {@code
   * client-secret}.
   *
   * <p>This bean triggers Spring Boot's {@code OAuth2ClientAutoConfiguration}, which auto-creates
   * the {@link OAuth2AuthorizedClientService} and {@link OAuth2AuthorizedClientRepository} beans
   * needed by {@code oauth2Login()}.
   *
   * @return the client registration repository
   */
  @Bean
  @Conditional(SSOEnabledCondition.class)
  public ClientRegistrationRepository clientRegistrationRepository() {
    final var oauth2 = properties.oauth2();
    if (!SecurityConfigSupport.isSet(oauth2.issuerUri())) {
      throw new IllegalStateException(
          "application.config.security.oauth2.issuer-uri must be configured when client-id is set"
              + " (SSO requires OIDC discovery via the issuer URI)");
    }
    if (!SecurityConfigSupport.isSet(oauth2.clientSecret())) {
      throw new IllegalStateException(
          "application.config.security.oauth2.client-secret must be configured when client-id is"
              + " set");
    }
    final ClientRegistration registration =
        ClientRegistrations.fromIssuerLocation(oauth2.issuerUri())
            .registrationId(REGISTRATION_ID)
            .clientId(oauth2.clientId())
            .clientSecret(oauth2.clientSecret())
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile", "email")
            .clientName("Meta Catalog")
            .build();
    return new InMemoryClientRegistrationRepository(registration);
  }

  // ────────────────────────── JWT decoder (always present) ──────────────────────────

  /**
   * A {@link JwtDecoder} resolving keys from the configured JWK set URI or issuer URI.
   *
   * @return the JWT decoder
   */
  @Bean
  public JwtDecoder jwtDecoder() {
    final var oauth2 = properties.oauth2();
    if (oauth2 == null) {
      throw new IllegalStateException(
          "application.config.security.oauth2 must be configured when auth-mode = oauth2");
    }
    if (SecurityConfigSupport.isSet(oauth2.jwkSetUri())
        && SecurityConfigSupport.isSet(oauth2.issuerUri())) {
      throw new IllegalStateException(
          "Exactly one of application.config.security.oauth2.jwk-set-uri and issuer-uri must be"
              + " configured, not both");
    }
    if (SecurityConfigSupport.isSet(oauth2.jwkSetUri())) {
      return NimbusJwtDecoder.withJwkSetUri(oauth2.jwkSetUri()).build();
    }
    if (SecurityConfigSupport.isSet(oauth2.issuerUri())) {
      return NimbusJwtDecoder.withIssuerLocation(oauth2.issuerUri()).build();
    }
    throw new IllegalStateException(
        "Either application.config.security.oauth2.jwk-set-uri or issuer-uri must be configured");
  }

  // ────────────────────────── Helpers ──────────────────────────

  /**
   * Builds an OIDC RP-initiated logout success handler that redirects to the IdP's end-session
   * endpoint after clearing the local session, then back to {@code /ui}.
   */
  private static LogoutSuccessHandler oidcLogoutSuccessHandler(
      final ClientRegistrationRepository clientRegistrationRepository) {
    final var handler = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
    handler.setPostLogoutRedirectUri("{baseUrl}/ui");
    return handler;
  }

  /**
   * Spring condition that matches when SSO is enabled, i.e. {@code client-id} is set on the OAuth2
   * config.
   */
  static class SSOEnabledCondition implements org.springframework.context.annotation.Condition {
    @Override
    public boolean matches(
        final org.springframework.context.annotation.ConditionContext context,
        final org.springframework.core.type.AnnotatedTypeMetadata metadata) {
      final var env = context.getEnvironment();
      final var clientId = env.getProperty("application.config.security.oauth2.client-id", "");
      return !clientId.isBlank();
    }
  }
}
