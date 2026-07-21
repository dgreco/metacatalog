package it.davidgreco.metacatalog;

import it.davidgreco.metacatalog.security.AuthMode;
import it.davidgreco.metacatalog.security.SecurityConfigProperties;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails fast at startup when a <em>protected</em> Spring profile is active and authentication is
 * still set to {@code none}.
 *
 * <p>Protected profiles are non-local deployment profiles where shipping an open API is a security
 * incident, not a convenience. The default set is {@code [kubernetes, docker]} and can be
 * overridden via {@code application.config.security.protected-profiles}.
 *
 * <p>The {@code kubernetes} and {@code docker} profiles ship with {@code
 * application.config.security.auth-mode: none} (or {@code basic}) as placeholders so a fresh
 * checkout can boot without an IdP. Leaving them as {@code none} in a deployed environment,
 * however, exposes the entire REST API and SPARQL endpoint without any authentication. This guard
 * makes that misconfiguration a loud startup error instead of a silent open API.
 *
 * <p>To override, set {@code APPLICATION_CONFIG_SECURITY_AUTH_MODE} (and the accompanying oauth2 /
 * ldap / basic properties) on the deployment, or remove the active profile from {@code
 * protected-profiles} if you have a bespoke non-local profile that you want to exempt.
 */
@Component
public class SecurityProfileGuard implements ApplicationRunner {

  private final SecurityConfigProperties securityConfigProperties;
  private final Environment environment;

  SecurityProfileGuard(SecurityConfigProperties securityConfigProperties, Environment environment) {
    this.securityConfigProperties = securityConfigProperties;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (securityConfigProperties.authMode() != AuthMode.NONE) {
      return;
    }
    List<String> protectedProfiles = securityConfigProperties.protectedProfiles();
    if (protectedProfiles == null || protectedProfiles.isEmpty()) {
      return;
    }
    var activeProtected =
        Arrays.stream(environment.getActiveProfiles())
            .filter(p -> protectedProfiles.stream().anyMatch(pp -> pp.equals(p)))
            .toList();
    if (activeProtected.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "application.config.security.auth-mode is 'none' but protected profile(s) "
            + activeProtected
            + " are active. Set APPLICATION_CONFIG_SECURITY_AUTH_MODE to basic, oauth2 or ldap"
            + " before deploying, or remove the profile(s) from"
            + " application.config.security.protected-profiles if this is intentional.");
  }
}
