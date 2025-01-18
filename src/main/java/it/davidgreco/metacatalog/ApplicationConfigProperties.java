package it.davidgreco.metacatalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("application.config")
public record ApplicationConfigProperties(
        boolean automaticEntitiesMapping,
        Duration updateMappedEntitiesSchedulingInterval,
        Duration entityLifeCycleEventCleanupSchedulingInterval) {}
