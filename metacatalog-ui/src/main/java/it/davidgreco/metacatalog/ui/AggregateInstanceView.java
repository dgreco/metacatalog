package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.service.AggregateService;
import java.util.ArrayList;
import java.util.List;

/**
 * Read model for one aggregate instance rendered in a list or table.
 *
 * @param id the unique identifier of the aggregate entity
 * @param entityType the name of the aggregate entity type
 * @param dependencyCount the number of direct dependencies
 * @param elementCount the number of child aggregate parts (nested elements + sub-aggregates)
 */
public record AggregateInstanceView(
    String id, String entityType, int dependencyCount, int elementCount) {

  /**
   * Builds {@link AggregateInstanceView} rows for every aggregate returned by {@code
   * aggregateService}, counting dependencies and child elements.
   */
  public static List<AggregateInstanceView> listFrom(List<AggregateService.Aggregate> aggregates) {
    var views = new ArrayList<AggregateInstanceView>();
    for (var aggregate : aggregates) {
      views.add(
          new AggregateInstanceView(
              aggregate.entity().getId(),
              aggregate.entity().getEntityType().getName(),
              aggregate.dependencies().size(),
              aggregate.elements().size()));
    }
    return views;
  }
}
