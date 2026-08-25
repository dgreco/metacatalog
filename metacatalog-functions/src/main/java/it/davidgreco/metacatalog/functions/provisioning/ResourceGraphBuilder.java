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
 * Builds the graph of the resources in an aggregate that carry a given trait, with an edge from
 * each resource to the resources it depends on.
 *
 * <p>Shared by all four procedures — {@link ProvisioningProcedure} and {@link
 * UnprovisioningProcedure} over {@code ProvisionableResource}, {@link AuthorizationProcedure} and
 * {@link RejectionProcedure} over {@code AuthorizableResource} — so no two of them can disagree
 * about what an aggregate contains or how its resources relate. They differ only in the trait they
 * select on and the direction they then wire the tasks in.
 *
 * <p>The trait is a parameter rather than four copies of this walk because the selection is the
 * <em>only</em> thing that varies: an aggregate whose members carry both traits produces the same
 * tree, the same mapping-derived edges and the same cycle check either way, and a resource that is
 * provisionable but not authorizable simply drops out of the authorization graph.
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
    return getResourceSequence(aggregate, PROVISIONABLE_RESOURCE);
  }

  /**
   * The resources in an aggregate carrying the given trait, in tree order.
   *
   * @param aggregate the aggregate, read with its mapped instances
   * @param resourceTrait the trait a member must carry to be a resource of this run
   * @return the resources it contains
   */
  public List<Entity> getResourceSequence(
      AggregateService.AggregatePart aggregate, String resourceTrait) {
    switch (aggregate) {
      case AggregateService.AggregateElement(Entity entity, _):
        if (hasTrait(entity, resourceTrait)) {
          return List.of(entity);
        } else {
          return List.of();
        }
      case AggregateService.Aggregate(
          Entity entity,
          _,
          List<AggregateService.AggregatePart> elements):
        var sequence = new ArrayList<Entity>();
        if (hasTrait(entity, resourceTrait)) {
          sequence.add(entity);
        }
        for (var childElement : elements) {
          sequence.addAll(getResourceSequence(childElement, resourceTrait));
        }
        return sequence;
    }
  }

  /**
   * The resources the given resource is derived from, found by following its mapping sources'
   * reference paths.
   */
  private List<Entity> getMappingDependencies(Entity entity, String resourceTrait) {
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
                    .filter(e -> hasTrait(e, resourceTrait)))
        .toList();
  }

  /**
   * Builds the dependency graph of an aggregate's provisionable resources. An edge runs from a
   * resource to each resource it depends on.
   *
   * @param aggregate the aggregate, read with its mapped instances
   * @return the graph
   * @throws ServiceError if the dependencies form a cycle
   */
  public Graph<Entity, DefaultEdge> buildResourceGraph(AggregateService.AggregatePart aggregate) {
    return buildResourceGraph(aggregate, PROVISIONABLE_RESOURCE);
  }

  /**
   * Builds the dependency graph of the aggregate's resources carrying the given trait. An edge runs
   * from a resource to each resource it depends on.
   *
   * @param aggregate the aggregate, read with its mapped instances
   * @param resourceTrait the trait a member must carry to be a resource of this run
   * @return the graph
   * @throws ServiceError if the dependencies form a cycle
   */
  public Graph<Entity, DefaultEdge> buildResourceGraph(
      AggregateService.AggregatePart aggregate, String resourceTrait) {
    Graph<Entity, DefaultEdge> resourceGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
    var resourceSequence = getResourceSequence(aggregate, resourceTrait);
    resourceSequence.stream()
        .map(e -> new Tuple2<>(e, getMappingDependencies(e, resourceTrait)))
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
      throw new ServiceError("Cycle detected in the " + resourceTrait + " graph");
    return resourceGraph;
  }
}
