package it.davidgreco.metacatalog.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the security module's {@link ConfigurationProperties} and acts as the entry point for
 * component-scanning.
 *
 * <p>This class lives in the {@code it.davidgreco.metacatalog.security} package, which is a
 * sub-package of the application's {@link
 * org.springframework.boot.autoconfigure.SpringBootApplication} root, so all {@link
 * org.springframework.context.annotation.Configuration} classes in this module are picked up
 * automatically when {@code metacatalog-application} depends on this module.
 */
@Configuration
@EnableConfigurationProperties(SecurityConfigProperties.class)
public class SecurityModuleConfiguration {

  /** Protected constructor to prevent instantiation. */
  protected SecurityModuleConfiguration() {}
}
