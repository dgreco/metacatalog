package it.davidgreco.metacatalog.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.stereotype.Component;

/**
 * Applies the URL authorization rules shared by all non-{@link AuthMode#NONE} modes.
 *
 * <ul>
 *   <li>Actuator endpoints, Swagger UI, API docs and Javadoc are publicly reachable.
 *   <li>The REST API at {@code /metacatalog/v1/**}, the SPARQL Protocol endpoint at {@code
 *       /sparql/query}, and the server-side rendered UI at {@code /ui/**} and {@code /sparql} all
 *       require an authenticated user.
 *   <li>Any other path is permitted.
 * </ul>
 *
 * <p>In {@code basic} and {@code ldap} mode, the dedicated {@link UiSecurityConfig} chain (order 0)
 * and {@link SparqlSecurityConfig} chain (order 50) shadow the UI and SPARQL paths so the API
 * chain's rules for those paths are never reached. In {@code oauth2} mode those chains are
 * inactive, so the API chain is the only protection: requiring authentication for {@code /ui/**}
 * prevents the management UI from being fully open when a protected profile is deployed with {@code
 * auth-mode: oauth2}. A browser without a JWT bearer token gets a 401 — the safe default for a JWT
 * resource-server that has no form login.
 *
 * <p>CSRF is always disabled on this chain. Sessions are {@link SessionCreationPolicy#STATELESS
 * stateless} for {@link AuthMode#OAUTH2} (JWT bearer tokens, no session) and {@link
 * SessionCreationPolicy#NEVER NEVER} for {@link AuthMode#BASIC} / {@link AuthMode#LDAP}: an
 * existing UI form-login session is reused (so Swagger "Try it out" and Yasgui XHRs are
 * authenticated without re-authentication) but no session is ever created on the API chain, keeping
 * it stateless for programmatic Basic-auth clients.
 *
 * <p>This customizer does <strong>not</strong> configure a specific authentication mechanism. Each
 * mode-specific {@code *SecurityConfig} class is responsible for adding its own authentication
 * (e.g. {@code .httpBasic(...)} for {@link AuthMode#BASIC} and {@link AuthMode#LDAP}, or {@code
 * .oauth2ResourceServer(...)} for {@link AuthMode#OAUTH2}) after calling {@link #customize}. This
 * prevents a mode from accidentally inheriting an authentication mechanism it was not designed for.
 */
@Component
public class SecurityFilterChainCustomizer {

  /** Public paths that don't require authentication. */
  static final String[] PUBLIC_PATHS = {
    "/actuator/**",
    "/swagger-ui/**",
    "/swagger-ui.html",
    "/v3/api-docs/**",
    "/api-docs",
    "/api-docs/**",
    "/api/interface-specification.yaml",
    "/javadoc/**"
  };

  /** The REST API surface that requires authentication. */
  static final String API_PATH = "/metacatalog/v1/**";

  /** The server-side rendered UI pages that require authentication. */
  static final String UI_PATH = "/ui/**";

  /** The browser-rendered SPARQL query UI (not the protocol endpoint). */
  static final String SPARQL_UI_PATH = "/sparql";

  /**
   * The SPARQL Protocol endpoint ({@code /sparql/query}). Protected by the API chain (JWT) in
   * {@code oauth2} mode. In {@code basic} / {@code ldap} mode a dedicated, stateful {@link
   * SparqlSecurityConfig} chain (lower order) takes the path so that the browser session
   * established by the UI form-login flow carries over to Yasgui's XHR; this matcher is then
   * shadowed and never reached. The browser-rendered query UI at {@code /sparql} is handled by
   * {@link UiSecurityConfig}.
   */
  static final String SPARQL_QUERY_PATH = "/sparql/query";

  /**
   * Applies the common URL rules and a stateless session policy to the given {@link HttpSecurity}.
   *
   * <p>Equivalent to {@link #customize(HttpSecurity, SessionCreationPolicy) customize(http,}{@link
   * SessionCreationPolicy#STATELESS STATELESS)}. Use this overload for modes whose API is consumed
   * programmatically with a bearer token on every request (e.g. {@link AuthMode#OAUTH2}).
   *
   * @param http the security builder to customize
   * @throws Exception if the security builder cannot be configured
   */
  public void customize(final HttpSecurity http) throws Exception {
    customize(http, SessionCreationPolicy.STATELESS);
  }

  /**
   * Applies the common URL rules and the given session policy to the {@link HttpSecurity}.
   *
   * <p>The caller is responsible for adding the mode-specific authentication mechanism (e.g. {@code
   * .httpBasic(...)} or {@code .oauth2ResourceServer(...)}) after this method returns.
   *
   * <p>For {@link AuthMode#BASIC} / {@link AuthMode#LDAP} the caller passes {@link
   * SessionCreationPolicy#NEVER}: an existing UI form-login session is honoured (so the Swagger UI
   * "Try it out" XHRs and the Yasgui SPARQL XHRs are authenticated without re-authentication), but
   * no session is ever created on the API chain — programmatic clients sending Basic credentials on
   * every request stay effectively stateless. For {@link AuthMode#OAUTH2} the no-arg overload
   * ({@link SessionCreationPolicy#STATELESS}) is used because JWT bearer tokens have no session.
   *
   * @param http the security builder to customize
   * @param sessionPolicy the session-creation policy to apply
   * @throws Exception if the security builder cannot be configured
   */
  public void customize(final HttpSecurity http, final SessionCreationPolicy sessionPolicy)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(sessionPolicy))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(PUBLIC_PATHS)
                    .permitAll()
                    .requestMatchers(API_PATH, SPARQL_QUERY_PATH, UI_PATH, SPARQL_UI_PATH)
                    .authenticated()
                    .anyRequest()
                    .permitAll());
  }
}
