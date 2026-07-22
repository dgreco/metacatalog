package it.davidgreco.metacatalog.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for {@link AuthMode#OAUTH2}.
 *
 * <p>Configures the application as a Spring Security OAuth2 resource server validating JWT bearer
 * tokens issued by an external IdP such as Keycloak. The JWK set is resolved either from a direct
 * URL ({@link SecurityConfigProperties.Oauth2#jwkSetUri()}) or from the IdP's OpenID Connect
 * metadata via the issuer URI ({@link SecurityConfigProperties.Oauth2#issuerUri()}).
 */
@Configuration
@ConditionalOnAuthMode(AuthMode.OAUTH2)
public class OAuth2SecurityConfig {

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
   * The {@link SecurityFilterChain} for OAuth2 resource server.
   *
   * @param http the security builder
   * @return the configured security filter chain
   * @throws Exception if the security builder cannot be configured
   */
  @Bean
  public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
    customizer.customize(http);
    http.oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
    return http.build();
  }

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
}
