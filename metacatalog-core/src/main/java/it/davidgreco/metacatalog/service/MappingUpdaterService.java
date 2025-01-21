package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.repository.EntityLifeCycleEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MappingUpdaterService {

    public boolean automaticEntitiesMapping;

    private final AdvisoryLockManager advisoryLockManager;

    private final EntityLifeCycleEventRepository entityLifeCycleEventRepository;

    public final MappingService mappingService;

    /**
     * Scheduled task that checks for new {@link EntityLifeCycleEvent}s that are source created or source updated.
     * If the {@link EntityLifeCycleEvent} is source created, it calls {@link MappingService#createMappedEntities} to
     * create the mapped entities. If the {@link EntityLifeCycleEvent} is source updated, it calls
     * {@link MappingService#updateMappedEntities} to update the mapped entities.
     *
     * <p>This task is only executed if {@link #automaticEntitiesMapping} is set to true. The task is also synchronized
     * using an advisory lock, so that only one instance of this task can run at the same time.
     */
    @Scheduled(
            fixedRateString =
                    "#{@coreConfig.getApplicationConfigurationProperties().updateMappedEntitiesSchedulingInterval}")
    void updateMappedEntities() {
        if (automaticEntitiesMapping) {
            if (advisoryLockManager.acquireLock(1)) {
                var createdEvents =
                        entityLifeCycleEventRepository.findByEventTypeAndEventStatus("SOURCE_CREATED", "PENDING");
                createdEvents.forEach(event -> {
                    try {
                        mappingService.createMappedEntities(event);
                    } catch (Exception e) {
                        e.printStackTrace(); // TODO
                    }
                });
                var updatedEvents =
                        entityLifeCycleEventRepository.findByEventTypeAndEventStatus("SOURCE_UPDATED", "PENDING");
                updatedEvents.forEach(event -> {
                    try {
                        mappingService.updateMappedEntities(event);
                    } catch (Exception e) {
                        e.printStackTrace(); // TODO
                    }
                });
            }
        }
    }

    @Scheduled(
            fixedRateString =
                    "#{@coreConfig.getApplicationConfigurationProperties().entityLifeCycleEventCleanupSchedulingInterval}")
    void entityLifeCycleEventCleanup() {
        if (automaticEntitiesMapping) {
            if (advisoryLockManager.acquireLock(1)) {}
        }
    }
}
