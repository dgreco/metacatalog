package it.davidgreco.metacatalog.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the server-side rendered UI (the {@code /ui/**} pages, plus the {@code
 * /login} and {@code /logout} endpoints).
 *
 * <p>Active only when {@code auth-mode} is {@code basic} or {@code ldap} — the two modes that have
 * a username/password to check. In {@code none} mode the UI stays open; in {@code oauth2} mode the
 * UI is not protected (JWT bearer tokens are for API clients, not browser sessions).
 *
 * <p>This chain is ordered with higher priority than the API chain so that requests to {@code
 * /ui/**} are handled here (form login + stateful sessions) instead of falling through to the API
 * chain (Basic/LDAP + stateless). Static resources under {@code /ui/css/**} and {@code /ui/js/**}
 * are permitted so the login page can be styled before the user authenticates.
 *
 * <p>CSRF is left enabled (Spring Security default) because the UI uses state-changing POST forms;
 * Thymeleaf auto-injects the CSRF token into every {@code <form th:action>}.
 */
@Configuration
@ConditionalOnExpression(
    "'${application.config.security.auth-mode:}' eq 'basic'"
        + " or '${application.config.security.auth-mode:}' eq 'ldap'")
public class UiSecurityConfig {

  /** Higher priority than the API chain (which uses the default order). */
  static final int UI_CHAIN_ORDER = 0;

  /**
   * The {@link SecurityFilterChain} for the UI.
   *
   * @param http the security builder
   * @return the configured security filter chain
   * @throws Exception if the security builder cannot be configured
   */
  @Bean
  @Order(UI_CHAIN_ORDER)
  public SecurityFilterChain uiSecurityFilterChain(final HttpSecurity http) throws Exception {
    http.securityMatcher("/ui/**", "/login", "/logout")
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/ui/css/**", "/ui/js/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            form ->
                form.loginPage("/login")
                    .loginProcessingUrl("/login")
                    .defaultSuccessUrl("/ui", true)
                    .failureUrl("/login?error=true")
                    .permitAll())
        .logout(
            logout ->
                logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout=true").permitAll())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
    // CSRF stays enabled — Thymeleaf injects the token into <form th:action>.
    return http.build();
  }
}
