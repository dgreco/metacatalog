package it.davidgreco.metacatalog.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Security configuration properties bound under the {@code application.config.security} prefix.
 *
 * <p>The {@link #authMode()} property selects which authentication mechanism is active. The
 * remaining records ({@link Basic}, {@link Oauth2}, {@link Ldap}) are only consulted when the
 * corresponding mode is selected.
 *
 * <p>Example YAML:
 *
 * <pre>{@code
 * application:
 *   config:
 *     security:
 *       auth-mode: basic
 *       basic:
 *         users:
 *           - username: admin
 *             password: "{noop}secret"
 *             roles: [USER, ADMIN]
 * }</pre>
 *
 * @param authMode the active authentication mode; defaults to {@link AuthMode#NONE} when omitted
 * @param basic basic-auth configuration (used when {@code authMode = BASIC})
 * @param oauth2 OAuth2 / OIDC resource server configuration (used when {@code authMode = OAUTH2})
 * @param ldap LDAP configuration (used when {@code authMode = LDAP})
 * @param protectedProfiles the Spring profiles that trigger a fail-fast startup check when {@code
 *     authMode = NONE}. If any of these profiles is active and auth is disabled, the application
 *     refuses to start. Defaults to {@code [kubernetes, docker]} so that non-local deployment
 *     profiles are guarded against accidentally shipping an open API. Add or remove profiles via
 *     {@code application.config.security.protected-profiles} in YAML.
 */
@ConfigurationProperties("application.config.security")
public record SecurityConfigProperties(
    AuthMode authMode,
    Basic basic,
    Oauth2 oauth2,
    Ldap ldap,
    @DefaultValue({"kubernetes", "docker"}) List<String> protectedProfiles) {

  /** The fully-qualified property name for the active authentication mode. */
  public static final String AUTH_MODE_PROPERTY = "application.config.security.auth-mode";

  /** HTTP Basic auth configuration. */
  public record Basic(List<User> users) {

    /**
     * A statically-configured user.
     *
     * @param username the login name
     * @param password the encoded password (use a {@code {prefix}} scheme, e.g. {@code
     *     {noop}secret} for plain text or {@code {bcrypt}$2a$...} for bcrypt)
     * @param roles the granted roles (no {@code ROLE_} prefix needed)
     */
    public record User(String username, String password, List<String> roles) {}
  }

  /**
   * OAuth2 / OIDC resource server configuration.
   *
   * @param jwkSetUri the JSON Web Key Set URL exposed by the IdP (e.g. Keycloak's {@code
   *     /protocol/openid-connect/certs} endpoint)
   * @param issuerUri the issuer URI, used to discover the JWK set and to validate the {@code iss}
   *     claim. Exactly one of {@code jwkSetUri} and {@code issuerUri} must be set.
   */
  public record Oauth2(String jwkSetUri, String issuerUri) {}

  /**
   * LDAP bind authentication configuration.
   *
   * @param url the LDAP URL (e.g. {@code ldap://host:389} or {@code ldaps://host:636})
   * @param userDnPattern the user DN pattern (e.g. {@code uid={0},ou=people}). When set, this is
   *     used to construct the user's DN directly. Mutually exclusive with {@code userSearchBase} /
   *     {@code userSearchFilter}.
   * @param userSearchBase the base DN for user search (e.g. {@code ou=people})
   * @param userSearchFilter the LDAP filter used to search for the user (e.g. {@code (uid={0})})
   * @param managerDn the DN used to bind to the directory for the user search (optional; if the
   *     directory supports anonymous bind, leave unset)
   * @param managerPassword the password for the manager DN
   */
  public record Ldap(
      String url,
      String userDnPattern,
      String userSearchBase,
      String userSearchFilter,
      String managerDn,
      String managerPassword) {}
}
