package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.entity.EntityLifeCycleEvent;
import it.davidgreco.metacatalog.repository.EntityLifeCycleEventRepository;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Service class for updating mapped entities. */
@Slf4j
@Getter
@Setter
public class MappingUpdaterService {

  /** Advisory lock id serializing the mapped-entities update task across instances. */
  private static final int UPDATE_MAPPING_LOCK_ID = 1;

  private boolean automaticEntitiesMapping;

  private final AdvisoryLockManager advisoryLockManager;

  private final EntityLifeCycleEventRepository entityLifeCycleEventRepository;

  public final MappingService mappingService;

  private final TransactionTemplate markFailedTx;

  public MappingUpdaterService(
      AdvisoryLockManager advisoryLockManager,
      EntityLifeCycleEventRepository entityLifeCycleEventRepository,
      MappingService mappingService,
      PlatformTransactionManager transactionManager) {
    this.advisoryLockManager = advisoryLockManager;
    this.entityLifeCycleEventRepository = entityLifeCycleEventRepository;
    this.mappingService = mappingService;
    this.markFailedTx = new TransactionTemplate(transactionManager);
    this.markFailedTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

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
   *
   * <p>The {@code @Transactional} annotation is required because {@link
   * AdvisoryLockManager#acquireLock} uses {@code pg_try_advisory_xact_lock} with {@code MANDATORY}
   * propagation — the transaction-scoped lock would be released immediately without an active
   * transaction. Each event's create/update work runs in its own {@code REQUIRES_NEW} transaction
   * (see {@link MappingService}), and {@link #markFailed} runs in a separate {@code REQUIRES_NEW}
   * transaction so the failure marker persists even if the surrounding transaction is compromised.
   */
  @Scheduled(
      initialDelay = 100,
      fixedRateString =
          "#{@coreConfig.getApplicationConfigurationProperties().updateMappedEntitiesSchedulingInterval}")
  @Transactional
  public void updateMappedEntities() {
    log.debug(
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
          } catch (ServiceError | ServiceRuntimeError e) {
            log.error(
                "Error creating mapped entities for event {} (entity {})",
                event.getId(),
                event.getEntityId(),
                e);
            markFailed(event);
          } catch (RuntimeException e) {
            log.error(
                "Unexpected error creating mapped entities for event {} (entity {});"
                    + " marking as FAILED to avoid poison-message head-of-line blocking",
                event.getId(),
                event.getEntityId(),
                e);
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
          } catch (ServiceError | ServiceRuntimeError e) {
            log.error(
                "Error updating mapped entities for event {} (entity {})",
                event.getId(),
                event.getEntityId(),
                e);
            markFailed(event);
          } catch (RuntimeException e) {
            log.error(
                "Unexpected error updating mapped entities for event {} (entity {});"
                    + " marking as FAILED to avoid poison-message head-of-line blocking",
                event.getId(),
                event.getEntityId(),
                e);
            markFailed(event);
          }
        });
    log.debug("Update mapped entities task completed");
  }

  /**
   * Marks an event as {@code FAILED} so that a permanently failing (poison) event is not
   * re-selected and re-processed on every scheduling tick. Runs in a dedicated {@code REQUIRES_NEW}
   * transaction so the failure marker persists regardless of the state of the surrounding
   * scheduled-method transaction. The event is re-fetched by id inside the new transaction to avoid
   * merging a stale detached instance.
   *
   * @param event the event that failed to process
   */
  private void markFailed(EntityLifeCycleEvent event) {
    try {
      markFailedTx.executeWithoutResult(
          status ->
              entityLifeCycleEventRepository
                  .findById(event.getId())
                  .ifPresent(
                      e -> {
                        e.setEventStatus(EntityLifeCycleEvent.STATUS_FAILED);
                        e.setProcessTime(Instant.now());
                        entityLifeCycleEventRepository.save(e);
                      }));
    } catch (RuntimeException e) {
      log.error("Failed to mark event {} as FAILED", event.getId(), e);
    }
  }
}
