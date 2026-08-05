package it.davidgreco.metacatalog.functions.provisioning;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the provisioning functions, loaded under the {@code
 * application.config.provisioning} prefix.
 *
 * @param entityTypes the names of the entity types whose provisioning is handled by {@link
 *     StdoutProvisioningFunction}. Entity type names rather than traits, because that is the key
 *     {@link ProvisioningFunctionRegistry} uses; configuration rather than constants, because
 *     entity types are catalogue data created at runtime. Defaults to empty, so adding this module
 *     to an application changes nothing until types are named.
 */
@ConfigurationProperties("application.config.provisioning")
public record ProvisioningConfigProperties(List<String> entityTypes) {

  public ProvisioningConfigProperties {
    entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
  }
}
