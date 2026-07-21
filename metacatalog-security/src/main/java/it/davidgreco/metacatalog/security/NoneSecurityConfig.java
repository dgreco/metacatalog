package it.davidgreco.metacatalog.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for {@link AuthMode#NONE}.
 *
 * <p>Disables CSRF and permits every request. This is the default mode and is intended for local
 * development, tests, and any environment where authentication is handled by an external layer
 * (e.g. an API gateway).
 */
@Configuration
@ConditionalOnProperty(
    name = SecurityConfigProperties.AUTH_MODE_PROPERTY,
    havingValue = "none",
    matchIfMissing = true)
public class NoneSecurityConfig {

  /**
   * A permissive {@link SecurityFilterChain} that allows all requests.
   *
   * @param http the security builder
   * @return the configured security filter chain
   * @throws Exception if the security builder cannot be configured
   */
  @Bean
  public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}
