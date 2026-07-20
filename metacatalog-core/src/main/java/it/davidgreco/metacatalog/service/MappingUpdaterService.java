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
import org.springframework.transaction.annotation.Transactional;

/** Service class for updating mapped entities. */
@Slf4j
@Service
@RequiredArgsConstructor
@Getter
@Setter
public class MappingUpdaterService {

  /** Advisory lock id serializing the mapped-entities update task across instances. */
  private static final int UPDATE_MAPPING_LOCK_ID = 1;

  /** Advisory lock id serializing the lifecycle-event cleanup task across instances. */
  private static final int CLEANUP_LOCK_ID = 2;

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
      initialDelay = 100,
      fixedRateString =
          "#{@coreConfig.getApplicationConfigurationProperties().updateMappedEntitiesSchedulingInterval}")
  @Transactional
  public void updateMappedEntities() {
    log.info(
        "Update mapped entities task started with automaticEntitiesMapping={}",
        automaticEntitiesMapping);
    if (!automaticEntitiesMapping) {
      return;
    }
    if (!advisoryLockManager.acquireLock(UPDATE_MAPPING_LOCK_ID)) {
      log.debug("Advisory lock not acquired, another instance is running; skipping");
      return;
    }
    var createdEvents =
        entityLifeCycleEventRepository.findByEventTypeAndEventStatus(
            EntityLifeCycleEvent.ENTITY_SOURCE_CREATED, EntityLifeCycleEvent.STATUS_PENDING);
    log.debug("Found {} SOURCE_CREATED PENDING events", createdEvents.size());
    createdEvents.forEach(
        event -> {
          try {
            log.debug("Processing SOURCE_CREATED event for entity {}", event.getEntityId());
            mappingService.createMappedEntities(event);
          } catch (Exception e) {
            log.error("Error creating mapped entities for event {}", event.getId(), e);
            markFailed(event);
          }
        });
    var updatedEvents =
        entityLifeCycleEventRepository.findByEventTypeAndEventStatus(
            EntityLifeCycleEvent.ENTITY_SOURCE_UPDATED, EntityLifeCycleEvent.STATUS_PENDING);
    log.debug("Found {} SOURCE_UPDATED PENDING events", updatedEvents.size());
    updatedEvents.forEach(
        event -> {
          try {
            log.debug("Processing SOURCE_UPDATED event for entity {}", event.getEntityId());
            mappingService.updateMappedEntities(event);
          } catch (Exception e) {
            log.error("Error updating mapped entities for event {}", event.getId(), e);
            markFailed(event);
          }
        });
    log.info("Update mapped entities task completed");
  }

  /**
   * Marks an event as {@code FAILED} so that a permanently failing (poison) event is not
   * re-selected and re-processed on every scheduling tick. Runs in the surrounding transaction; the
   * per-event work that threw ran in its own {@code REQUIRES_NEW} transaction and has already
   * rolled back, so this write is unaffected by that rollback.
   *
   * @param event the event that failed to process
   */
  private void markFailed(EntityLifeCycleEvent event) {
    try {
      event.setEventStatus(EntityLifeCycleEvent.STATUS_FAILED);
      entityLifeCycleEventRepository.save(event);
    } catch (Exception e) {
      log.error("Failed to mark event {} as FAILED", event.getId(), e);
    }
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
      initialDelay = 5000,
      fixedRateString =
          "#{@coreConfig.getApplicationConfigurationProperties().entityLifeCycleEventCleanupSchedulingInterval}")
  @Transactional
  public void entityLifeCycleEventCleanup() {
    if (!automaticEntitiesMapping) {
      return;
    }
    if (!advisoryLockManager.acquireLock(CLEANUP_LOCK_ID)) {
      log.debug("Advisory lock not acquired, another instance is running; skipping");
      return;
    }
    log.info("Cleaning up entity life cycle events");
  }
}
