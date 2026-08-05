package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.service.ServiceUtils.hasTrait;

import io.vavr.Tuple2;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.EntityPathResolver;
import it.davidgreco.metacatalog.service.MappingService;
import it.davidgreco.metacatalog.service.ServiceError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.stereotype.Service;

/**
 * Builds the graph of {@code ProvisionableResource} entities in an aggregate, with an edge from
 * each resource to the resources it depends on.
 *
 * <p>Shared by {@link ProvisioningProcedure} and {@link UnprovisioningProcedure} so the two cannot
 * disagree about what an aggregate contains or how its resources relate. They differ only in the
 * direction they then wire the tasks in.
 */
@Service
@RequiredArgsConstructor
public class ResourceGraphBuilder {

  private static final String PROVISIONABLE_RESOURCE = BuiltInTraits.PROVISIONABLE_RESOURCE;

  private final MappingService mappingService;

  private final EntityPathResolver entityPathResolver;

  /**
   * The provisionable resources in an aggregate, in tree order.
   *
   * @param aggregate the aggregate, read with its mapped instances
   * @return the resources it contains
   */
  public List<Entity> getProvisionableResourceSequence(AggregateService.AggregatePart aggregate) {
    switch (aggregate) {
      case AggregateService.AggregateElement(Entity entity, _):
        if (hasTrait(entity, PROVISIONABLE_RESOURCE)) {
          return List.of(entity);
        } else {
          return List.of();
        }
      case AggregateService.Aggregate(
          Entity entity,
          _,
          List<AggregateService.AggregatePart> elements):
        var sequence = new ArrayList<Entity>();
        if (hasTrait(entity, PROVISIONABLE_RESOURCE)) {
          sequence.add(entity);
        }
        for (var childElement : elements) {
          sequence.addAll(getProvisionableResourceSequence(childElement));
        }
        return sequence;
    }
  }

  /**
   * The resources the given resource is derived from, found by following its mapping sources'
   * reference paths.
   */
  private List<Entity> getMappingDependencies(Entity entity) {
    return mappingService.findMappingSources(entity).stream()
        .flatMap(
            ms ->
                ms.pathReferences().stream()
                    .map(
                        er ->
                            entityPathResolver.retrieveEntityByPath(
                                ms.source().getId(), er.referencePath()))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(e -> hasTrait(e, PROVISIONABLE_RESOURCE)))
        .toList();
  }

  /**
   * Builds the dependency graph of an aggregate's resources. An edge runs from a resource to each
   * resource it depends on.
   *
   * @param aggregate the aggregate, read with its mapped instances
   * @return the graph
   * @throws ServiceError if the dependencies form a cycle
   */
  public Graph<Entity, DefaultEdge> buildResourceGraph(AggregateService.AggregatePart aggregate) {
    Graph<Entity, DefaultEdge> resourceGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
    var provisionableResourceSequence = getProvisionableResourceSequence(aggregate);
    provisionableResourceSequence.stream()
        .map(e -> new Tuple2<>(e, getMappingDependencies(e)))
        .toList()
        .forEach(
            t -> {
              if (!resourceGraph.containsVertex(t._1)) resourceGraph.addVertex(t._1);
              t._2.forEach(
                  e -> {
                    if (!resourceGraph.containsVertex(e)) resourceGraph.addVertex(e);
                    resourceGraph.addEdge(t._1, e);
                  });
            });
    if (new CycleDetector<>(resourceGraph).detectCycles())
      throw new ServiceError("Cycle detected in provisioning graph");
    return resourceGraph;
  }
}
