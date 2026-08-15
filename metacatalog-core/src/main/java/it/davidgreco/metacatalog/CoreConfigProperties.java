package it.davidgreco.metacatalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the metacatalog core module.
 *
 * <p>These properties are loaded from the application configuration under the "application.config"
 * prefix.
 *
 * @param automaticEntitiesMapping whether automatic entity mapping is enabled
 * @param updateMappedEntitiesSchedulingInterval interval for the mapped entities update scheduler
 * @param entityPathResolutionMaxAttempts maximum retry attempts for entity path resolution
 * @param procedureRunRetention how long an asynchronously launched procedure run's status row stays
 *     pollable after its last write; also the staleness threshold after which an orphaned RUNNING
 *     row (its executing instance is gone) is marked FAILED. Defaults to 24 hours when unset
 */
@ConfigurationProperties("application.config")
public record CoreConfigProperties(
    boolean automaticEntitiesMapping,
    Duration updateMappedEntitiesSchedulingInterval,
    int entityPathResolutionMaxAttempts,
    Duration procedureRunRetention) {

  public CoreConfigProperties {
    if (procedureRunRetention == null) procedureRunRetention = Duration.ofHours(24);
  }
}
