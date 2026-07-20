package it.davidgreco.metacatalog.security;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Renders the login page at {@code /login}.
 *
 * <p>Active only when the UI security chain is active (auth-mode = basic or ldap). In other modes
 * the login page is not mapped and the UI is either open ({@code none}) or unprotected ({@code
 * oauth2}).
 */
@Controller
@ConditionalOnUiAuthMode
public class LoginController {

  /** View name for the Thymeleaf login template. */
  static final String LOGIN_VIEW = "login";

  /**
   * Renders the login page.
   *
   * @param error present (any value) when Spring Security redirects here after a failed login
   * @param logout present (any value) when Spring Security redirects here after a successful logout
   * @param model the Spring MVC model
   * @return the view name {@code login}
   */
  @GetMapping("/login")
  public String login(
      @RequestParam(value = "error", required = false) final String error,
      @RequestParam(value = "logout", required = false) final String logout,
      final Model model) {
    if (error != null) {
      model.addAttribute("loginError", true);
    }
    if (logout != null) {
      model.addAttribute("loggedOut", true);
    }
    return LOGIN_VIEW;
  }
}
