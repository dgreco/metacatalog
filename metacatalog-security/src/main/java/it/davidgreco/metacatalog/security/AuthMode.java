package it.davidgreco.metacatalog.security;

/**
 * Supported authentication mechanisms for the Meta Catalog service.
 *
 * <p>The active mode is selected through the {@code application.config.security.auth-mode}
 * property. Exactly one {@link org.springframework.security.web.SecurityFilterChain} is registered
 * based on this value; no authentication is enforced when {@link #NONE} is selected.
 */
public enum AuthMode {

  /** Security disabled: every request is permitted. Intended for local development and tests. */
  NONE,

  /** HTTP Basic authentication with users defined statically in configuration. */
  BASIC,

  /** OAuth2 / OpenID Connect resource server (JWT), e.g. Keycloak as the IdP. */
  OAUTH2,

  /** LDAP bind authentication against an external directory service. */
  LDAP
}
