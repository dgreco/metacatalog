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
  LDAP;

  /**
   * Indicates whether this mode supports browser-based UI login with a username and password.
   *
   * <p>{@link #BASIC} and {@link #LDAP} have credentials that can be checked against a browser
   * session; {@link #NONE} disables security entirely and {@link #OAUTH2} relies on a JWT bearer
   * token that a browser cannot obtain through a form POST.
   *
   * @return true if this mode has a UI login flow
   */
  public boolean isUiMode() {
    return this == BASIC || this == LDAP;
  }
}
