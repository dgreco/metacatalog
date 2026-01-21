package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.entity.EntityLifeCycleEvent;
import it.davidgreco.metacatalog.repository.EntityLifeCycleEventRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Service class for updating mapped entities. */
@Slf4j
@Service
@RequiredArgsConstructor
@Getter
@Setter
public class MappingUpdaterService {

  private boolean automaticEntitiesMapping;

  private final AdvisoryLockManager advisoryLockManager;

  private final EntityLifeCycleEventRepository entityLifeCycleEventRepository;

  public final MappingService mappingService;

  /**
   * Scheduled task that checks for new {@link EntityLifeCycleEvent}s that are source created or
   * source updated. If the {@link EntityLifeCycleEvent} is source created, it calls {@link
   * MappingService#createMappedEntities} to create the mapped entities. If the {@link
   * EntityLifeCycleEvent} is source updated, it calls {@link MappingService#updateMappedEntities}
   * to update the mapped entities.
   *
   * <p>This task is only executed if {@link #automaticEntitiesMapping} is set to true. The task is
   * also synchronized using an advisory lock, so that only one instance of this task can run at the
   * same time.
   */
  @Scheduled(
      fixedRateString =
          "#{@coreConfig.getApplicationConfigurationProperties().updateMappedEntitiesSchedulingInterval}")
  void updateMappedEntities() {
    log.info(
        "Update mapped entities task started with automaticEntitiesMapping={}",
        automaticEntitiesMapping);
    var lockAcquired = advisoryLockManager.acquireLock(1);
    log.debug("Advisory lock acquired: {}", lockAcquired);
    if (automaticEntitiesMapping && lockAcquired) {
      var createdEvents =
          entityLifeCycleEventRepository.findByEventTypeAndEventStatus("SOURCE_CREATED", "PENDING");
      log.debug("Found {} SOURCE_CREATED PENDING events", createdEvents.size());
      createdEvents.forEach(
          event -> {
            try {
              log.debug("Processing SOURCE_CREATED event for entity {}", event.getEntityId());
              mappingService.createMappedEntities(event);
            } catch (Exception e) {
              log.error("Error creating mapped entities", e);
            }
          });
      var updatedEvents =
          entityLifeCycleEventRepository.findByEventTypeAndEventStatus("SOURCE_UPDATED", "PENDING");
      log.debug("Found {} SOURCE_UPDATED PENDING events", updatedEvents.size());
      updatedEvents.forEach(
          event -> {
            try {
              log.debug("Processing SOURCE_UPDATED event for entity {}", event.getEntityId());
              mappingService.updateMappedEntities(event);
            } catch (Exception e) {
              log.error("Error updating mapped entities", e);
            }
          });
    }
    log.info("Update mapped entities task completed");
  }

  /**
   * Scheduled task that cleans up entity life cycle events.
   *
   * <p>This task is only executed if {@link #automaticEntitiesMapping} is set to true. The task is
   * also synchronized using an advisory lock, so that only one instance of this task can run at the
   * same time.
   *
   * <p>The task logs a message indicating that cleanup is being performed, but does not actually
   * perform any cleanup. This is a placeholder for future cleanup tasks.
   */
  @Scheduled(
      fixedRateString =
          "#{@coreConfig.getApplicationConfigurationProperties().entityLifeCycleEventCleanupSchedulingInterval}")
  void entityLifeCycleEventCleanup() {
    var lockAcquired = advisoryLockManager.acquireLock(1);
    if (automaticEntitiesMapping && lockAcquired) {
      log.info("Cleaning up entity life cycle events");
    }
  }
}
