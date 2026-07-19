package it.davidgreco.metacatalog.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.stereotype.Component;

/**
 * Applies the URL authorization rules shared by all non-{@link AuthMode#NONE} modes.
 *
 * <ul>
 *   <li>Actuator endpoints, Swagger UI, API docs and Javadoc are publicly reachable.
 *   <li>The REST API at {@code /metacatalog/v1/**} requires an authenticated user.
 *   <li>Any other path (e.g. the server-side rendered UI under {@code /ui/**}) is permitted, since
 *       authentication is configured to protect the API surface only.
 * </ul>
 *
 * <p>CSRF is disabled and sessions are stateless because the API is meant to be consumed
 * programmatically (Basic credentials, JWT bearer tokens or LDAP-backed Basic credentials are sent
 * on every request).
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
   * Applies the common URL rules and session/CSRF settings to the given {@link HttpSecurity}.
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
                    .requestMatchers(API_PATH)
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .httpBasic(Customizer.withDefaults());
  }
}
