package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.EntityLifeCycleEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository interface for {@link EntityLifeCycleEvent} persistence operations.
 *
 * <p>Provides methods for querying entity lifecycle events, which are used to track entity changes
 * and trigger automatic mapping operations.
 */
public interface EntityLifeCycleEventRepository extends JpaRepository<EntityLifeCycleEvent, Long> {

  /**
   * Finds all lifecycle events with the specified event type and status.
   *
   * @param eventType the type of event (e.g., "SOURCE_CREATED", "SOURCE_UPDATED")
   * @param eventStatus the status of the event (e.g., "PENDING", "PROCESSED")
   * @return list of matching lifecycle events
   */
  List<EntityLifeCycleEvent> findByEventTypeAndEventStatus(String eventType, String eventStatus);
}
