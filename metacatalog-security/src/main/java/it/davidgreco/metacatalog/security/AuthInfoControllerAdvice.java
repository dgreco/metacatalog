package it.davidgreco.metacatalog.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the current authentication state to every Thymeleaf template.
 *
 * <p>Adds two model attributes:
 *
 * <ul>
 *   <li>{@code authenticated} — {@code true} when a real (non-anonymous) user is logged in
 *   <li>{@code username} — the principal name, or {@code null} when unauthenticated
 * </ul>
 *
 * <p>Templates use these to conditionally render the logout button and the username display in the
 * topbar. In {@code none} mode the security context is never populated, so both attributes are
 * falsy and the logout button is hidden.
 */
@ControllerAdvice
public class AuthInfoControllerAdvice {

  /** Default constructor. */
  public AuthInfoControllerAdvice() {}

  /**
   * Whether the current user is authenticated.
   *
   * @return {@code true} if a non-anonymous authentication is present in the security context
   */
  @ModelAttribute("authenticated")
  public boolean authenticated() {
    return currentAuthentication() != null;
  }

  /**
   * The name of the current user, or {@code null} when unauthenticated.
   *
   * @return the principal name, or {@code null}
   */
  @ModelAttribute("username")
  public String username() {
    final var auth = currentAuthentication();
    return auth != null ? auth.getName() : null;
  }

  private static Authentication currentAuthentication() {
    final var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      return null;
    }
    return auth;
  }
}
