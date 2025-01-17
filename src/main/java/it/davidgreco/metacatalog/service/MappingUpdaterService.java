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

    @Scheduled(fixedRate = 1000) // TODO Configurable
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

    @Scheduled(fixedRate = 1000) // TODO Configurable
    void entityLifeCycleEventCleanup() {
        if (automaticEntitiesMapping) {
            if (advisoryLockManager.acquireLock(1)) {
                //System.out.println("Cleaning up entity life cycle events");
            }
        }
    }
}
