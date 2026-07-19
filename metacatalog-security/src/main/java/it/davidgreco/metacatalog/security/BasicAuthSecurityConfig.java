package it.davidgreco.metacatalog.security;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for {@link AuthMode#BASIC}.
 *
 * <p>HTTP Basic authentication backed by an {@link InMemoryUserDetailsManager} populated from
 * {@link SecurityConfigProperties.Basic#users()}. Passwords must be encoded with a Spring Security
 * DelegatingPasswordEncoder scheme (e.g. {@code {noop}secret} or {@code {bcrypt}$2a$...}).
 */
@Configuration
@ConditionalOnProperty(name = "application.config.security.auth-mode", havingValue = "basic")
public class BasicAuthSecurityConfig {

  private final SecurityConfigProperties properties;
  private final SecurityFilterChainCustomizer customizer;

  /**
   * Constructs the configuration.
   *
   * @param properties the security configuration properties
   * @param customizer the shared filter-chain customizer
   */
  public BasicAuthSecurityConfig(
      final SecurityConfigProperties properties, final SecurityFilterChainCustomizer customizer) {
    this.properties = properties;
    this.customizer = customizer;
  }

  /**
   * The {@link SecurityFilterChain} for Basic auth.
   *
   * @param http the security builder
   * @return the configured security filter chain
   * @throws Exception if the security builder cannot be configured
   */
  @Bean
  public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
    customizer.customize(http);
    return http.build();
  }

  /**
   * An in-memory {@link UserDetailsService} populated from configuration.
   *
   * @return the user details service
   */
  @Bean
  public UserDetailsService userDetailsService() {
    final List<SecurityConfigProperties.Basic.User> users =
        properties.basic() == null || properties.basic().users() == null
            ? List.<SecurityConfigProperties.Basic.User>of()
            : properties.basic().users();
    if (users.isEmpty()) {
      throw new IllegalStateException(
          "application.config.security.basic.users must be configured when auth-mode = basic");
    }
    final var manager = new InMemoryUserDetailsManager();
    for (final var u : users) {
      final var builder =
          User.withUsername(u.username())
              .password(u.password())
              .roles(u.roles() == null ? new String[0] : u.roles().toArray(new String[0]));
      manager.createUser(builder.build());
    }
    return manager;
  }

  /**
   * A {@link PasswordEncoder} that delegates to the scheme declared in each stored password via the
   * {@code {prefix}} mechanism.
   *
   * @return the delegating password encoder
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
}
