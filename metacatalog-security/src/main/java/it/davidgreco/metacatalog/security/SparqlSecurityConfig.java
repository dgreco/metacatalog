package it.davidgreco.metacatalog.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the SPARQL Protocol endpoint ({@code /sparql/query}) in {@code basic}
 * and {@code ldap} modes.
 *
 * <p>Active only when {@code auth-mode} is {@code basic} or {@code ldap}. In {@code none} mode the
 * endpoint is open; in {@code oauth2} mode it is protected by the API chain ({@link
 * SecurityFilterChainCustomizer}) as a JWT resource-server path.
 *
 * <p>This chain sits between the UI chain ({@link UiSecurityConfig}, order 0) and the API chain
 * (order 100) so that {@code /sparql/query} is handled here instead of falling through to the
 * stateless API chain. It is <em>stateful</em> ({@link SessionCreationPolicy#IF_REQUIRED}) so that
 * a browser session established by the UI form-login flow is honoured: Yasgui's XHR to {@code
 * /sparql/query} rides the same {@code JSESSIONID} cookie and is authenticated without
 * re-authentication.
 *
 * <p>HTTP Basic is also enabled so that programmatic SPARQL clients (curl, Python, rdflib, ...) can
 * authenticate with credentials on every request. With no credentials and no session the chain
 * responds {@code 401} (Basic entry point) rather than redirecting to the login page — the
 * behaviour programmatic clients expect.
 *
 * <p>CSRF is disabled for {@code /sparql/query}: the SPARQL Protocol is read-only here (SELECT /
 * ASK / CONSTRUCT / DESCRIBE — no SPARQL Update), so the CSRF protection that the UI chain keeps
 * for its state-changing POST forms would only break Yasgui's POSTs, which cannot carry a CSRF
 * token.
 */
@Configuration
@ConditionalOnExpression(
    "'${application.config.security.auth-mode:}' eq 'basic'"
        + " or '${application.config.security.auth-mode:}' eq 'ldap'")
public class SparqlSecurityConfig {

  /** Between the UI chain (0) and the API chain (100). */
  static final int SPARQL_CHAIN_ORDER = 50;

  /**
   * The {@link SecurityFilterChain} for the SPARQL Protocol endpoint.
   *
   * @param http the security builder
   * @return the configured security filter chain
   * @throws Exception if the security builder cannot be configured
   */
  @Bean
  @Order(SPARQL_CHAIN_ORDER)
  public SecurityFilterChain sparqlSecurityFilterChain(final HttpSecurity http) throws Exception {
    http.securityMatcher("/sparql/query")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .httpBasic(Customizer.withDefaults());
    return http.build();
  }
}
