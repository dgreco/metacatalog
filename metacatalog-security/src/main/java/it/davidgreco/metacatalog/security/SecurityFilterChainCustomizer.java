package it.davidgreco.metacatalog.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.stereotype.Component;

/**
 * Applies the URL authorization rules shared by all non-{@link AuthMode#NONE} modes.
 *
 * <ul>
 *   <li>Actuator endpoints, Swagger UI, API docs and Javadoc are publicly reachable.
 *   <li>The REST API at {@code /metacatalog/v1/**} and the SPARQL Protocol endpoint at {@code
 *       /sparql/query} require an authenticated user.
 *   <li>Any other path (e.g. the server-side rendered UI under {@code /ui/**}, or the SPARQL query
 *       UI at {@code /sparql}) is permitted, since authentication is configured to protect the API
 *       surface only.
 * </ul>
 *
 * <p>CSRF is disabled and sessions are stateless because the API is meant to be consumed
 * programmatically (Basic credentials, JWT bearer tokens or LDAP-backed Basic credentials are sent
 * on every request).
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
   * Applies the common URL rules and session/CSRF settings to the given {@link HttpSecurity}.
   *
   * <p>The caller is responsible for adding the mode-specific authentication mechanism (e.g. {@code
   * .httpBasic(...)} or {@code .oauth2ResourceServer(...)}) after this method returns.
   *
   * @param http the security builder to customize
   * @throws Exception if the security builder cannot be configured
   */
  public void customize(final HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(PUBLIC_PATHS)
                    .permitAll()
                    .requestMatchers(API_PATH, SPARQL_QUERY_PATH)
                    .authenticated()
                    .anyRequest()
                    .permitAll());
  }
}
