package it.davidgreco.metacatalog.functions.provisioning;

import static org.awaitility.Awaitility.await;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.AggregateService;
import java.util.List;
import org.awaitility.Durations;

/**
 * Helpers over the two-output-port demo aggregate the authorization tests provision from {@code
 * bulk/bulk-policy*.yaml}, shared so the two test classes cannot drift on what "the aggregate is
 * ready" means.
 */
final class MappedResourceTestSupport {

  private MappedResourceTestSupport() {}

  /** Waits until the mapping updater has derived a resource under each of the two output ports. */
  static void awaitMappedResources(AggregateService aggregateService, String rootId) {
    await()
        .atMost(Durations.ONE_MINUTE)
        .pollDelay(Durations.ONE_SECOND)
        .pollInterval(Durations.ONE_SECOND)
        .until(
            () ->
                aggregateService.read(rootId, true).elements().stream()
                        .filter(AggregateService.Aggregate.class::isInstance)
                        .map(AggregateService.Aggregate.class::cast)
                        .filter(port -> !port.elements().isEmpty())
                        .count()
                    == 2);
  }

  /** The mapped resources of the aggregate, one per output port, in tree order. */
  static List<Entity> resources(AggregateService aggregateService, String rootId) {
    var aggregate = (AggregateService.Aggregate) aggregateService.read(rootId, true);
    return aggregate.elements().stream()
        .map(AggregateService.Aggregate.class::cast)
        .map(port -> ((AggregateService.AggregateElement) port.elements().getFirst()).entity())
        .toList();
  }
}
