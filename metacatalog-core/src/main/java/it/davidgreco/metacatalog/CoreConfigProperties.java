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
 * @param entityLifeCycleEventCleanupSchedulingInterval interval for lifecycle event cleanup
 * @param entityPathResolutionMaxAttempts maximum retry attempts for entity path resolution
 */
@ConfigurationProperties("application.config")
public record CoreConfigProperties(
    boolean automaticEntitiesMapping,
    Duration updateMappedEntitiesSchedulingInterval,
    Duration entityLifeCycleEventCleanupSchedulingInterval,
    int entityPathResolutionMaxAttempts) {}
