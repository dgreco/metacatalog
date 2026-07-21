package it.davidgreco.metacatalog.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for {@link AuthMode#LDAP}.
 *
 * <p>HTTP Basic credentials are validated against an external LDAP directory using a bind
 * authenticator. The user can be located either by a DN pattern ({@link
 * SecurityConfigProperties.Ldap#userDnPattern()}) or by a search filter ({@link
 * SecurityConfigProperties.Ldap#userSearchBase()} / {@link
 * SecurityConfigProperties.Ldap#userSearchFilter()}).
 */
@Configuration
@ConditionalOnProperty(name = SecurityConfigProperties.AUTH_MODE_PROPERTY, havingValue = "ldap")
public class LdapSecurityConfig {

  private final SecurityConfigProperties properties;
  private final SecurityFilterChainCustomizer customizer;

  /**
   * Constructs the configuration.
   *
   * @param properties the security configuration properties
   * @param customizer the shared filter-chain customizer
   */
  public LdapSecurityConfig(
      final SecurityConfigProperties properties, final SecurityFilterChainCustomizer customizer) {
    this.properties = properties;
    this.customizer = customizer;
  }

  /**
   * The {@link SecurityFilterChain} for LDAP-backed Basic auth.
   *
   * @param http the security builder
   * @param authenticationManager the LDAP-based authentication manager
   * @return the configured security filter chain
   * @throws Exception if the security builder cannot be configured
   */
  @Bean
  @Order(100)
  public SecurityFilterChain securityFilterChain(
      final HttpSecurity http, final AuthenticationManager authenticationManager) throws Exception {
    customizer.customize(http);
    http.httpBasic(org.springframework.security.config.Customizer.withDefaults())
        .authenticationManager(authenticationManager);
    return http.build();
  }

  /**
   * Builds the LDAP {@link AuthenticationManager} from the configured context source and bind
   * authenticator.
   *
   * @return the LDAP authentication manager
   * @throws Exception if the context source cannot be initialized
   */
  @Bean
  public AuthenticationManager authenticationManager() throws Exception {
    final var ldap = properties.ldap();
    if (ldap == null || !SecurityConfigSupport.isSet(ldap.url())) {
      throw new IllegalStateException(
          "application.config.security.ldap.url must be configured when auth-mode = ldap");
    }
    final var contextSource = new DefaultSpringSecurityContextSource(ldap.url());
    if (SecurityConfigSupport.isSet(ldap.managerDn())) {
      contextSource.setUserDn(ldap.managerDn());
      contextSource.setPassword(ldap.managerPassword());
    }
    contextSource.afterPropertiesSet();

    final var bindAuthenticator = new BindAuthenticator(contextSource);
    if (SecurityConfigSupport.isSet(ldap.userDnPattern())) {
      bindAuthenticator.setUserDnPatterns(new String[] {ldap.userDnPattern()});
    } else if (SecurityConfigSupport.isSet(ldap.userSearchBase())
        && SecurityConfigSupport.isSet(ldap.userSearchFilter())) {
      final var search =
          new FilterBasedLdapUserSearch(
              ldap.userSearchBase(), ldap.userSearchFilter(), contextSource);
      bindAuthenticator.setUserSearch(search);
    } else {
      throw new IllegalStateException(
          "Either application.config.security.ldap.user-dn-pattern or "
              + "(user-search-base + user-search-filter) must be configured for LDAP");
    }

    final var provider = new LdapAuthenticationProvider(bindAuthenticator);
    return new ProviderManager(provider);
  }
}
