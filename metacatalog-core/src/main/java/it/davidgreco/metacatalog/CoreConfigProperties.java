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
 * @param taskScheduleCacheMaxSize maximum number of schedules kept in the {@link
 *     it.davidgreco.metacatalog.service.TaskManager} result cache before eviction; defaults to
 *     {@code 100} when unset or non-positive
 * @param taskScheduleCacheExpireAfterWrite time-to-live for entries in the {@link
 *     it.davidgreco.metacatalog.service.TaskManager} result cache; defaults to 1 hour when unset
 */
@ConfigurationProperties("application.config")
public record CoreConfigProperties(
    boolean automaticEntitiesMapping,
    Duration updateMappedEntitiesSchedulingInterval,
    int entityPathResolutionMaxAttempts,
    int taskScheduleCacheMaxSize,
    Duration taskScheduleCacheExpireAfterWrite) {

  public CoreConfigProperties {
    if (taskScheduleCacheMaxSize <= 0) taskScheduleCacheMaxSize = 100;
    if (taskScheduleCacheExpireAfterWrite == null)
      taskScheduleCacheExpireAfterWrite = Duration.ofHours(1);
  }
}
