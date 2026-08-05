package it.davidgreco.metacatalog.functions.provisioning;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link ProvisioningConfigProperties} for any context that component-scans this package.
 *
 * <p>Keeping it here rather than on the application's main class means an application only has to
 * depend on this module to get the provisioning functions configured — it does not also have to
 * know which properties record to enable.
 */
@Configuration
@EnableConfigurationProperties(ProvisioningConfigProperties.class)
public class ProvisioningConfig {}
