package it.davidgreco.metacatalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("application.config")
public record CoreConfigProperties(
        boolean automaticEntitiesMapping,
        Duration updateMappedEntitiesSchedulingInterval,
        Duration entityLifeCycleEventCleanupSchedulingInterval,
        int entityPathResolutionMaxAttempts) {}
