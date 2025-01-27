package it.davidgreco.metacatalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for the core module. */
@ConfigurationProperties("application.config")
public record CoreConfigProperties(
    boolean automaticEntitiesMapping,
    Duration updateMappedEntitiesSchedulingInterval,
    Duration entityLifeCycleEventCleanupSchedulingInterval,
    int entityPathResolutionMaxAttempts) {}
