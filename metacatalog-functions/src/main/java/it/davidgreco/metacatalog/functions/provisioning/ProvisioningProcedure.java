package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.entity.RelationType.MAPPED_TO;
import static it.davidgreco.metacatalog.service.CommonService.hasTrait;

import io.vavr.Tuple2;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.AbstractEntityProcedure;
import it.davidgreco.metacatalog.functions.ProcedureExecutor;
import it.davidgreco.metacatalog.repository.MappingEntityRelationshipRepository;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.MappingService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class ProvisioningProcedure extends AbstractEntityProcedure {

  private static final String PROVISIONABLE_RESOURCE = "ProvisionableResource";

  static {
    ProcedureExecutor.getProcedureRegistry()
        .put("ProvisioningProcedure", ProvisioningProcedure.class);
  }

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;
  private final MappingService mappingService;
  private final AggregateService aggregateService;

  private List<Entity> getPhysicalResourceSequence(AggregateService.AggregatePart aggregate) {
    switch (aggregate) {
      case AggregateService.AggregateElement(Entity entity, List<Entity> _):
        if (hasTrait(entity, PROVISIONABLE_RESOURCE)) {
          return List.of(entity);
        } else {
          return List.of();
        }
      case AggregateService.Aggregate(
          Entity entity,
          List<Entity> _,
          List<AggregateService.AggregatePart> elements):
        var sequence = new ArrayList<Entity>();
        if (hasTrait(entity, PROVISIONABLE_RESOURCE)) {
          sequence.add(entity);
        }
        for (var childElement : elements) {
          sequence.addAll(getPhysicalResourceSequence(childElement));
        }
        return sequence;
      default:
        throw new IllegalArgumentException("Unknown aggregate part type: " + aggregate.getClass());
    }
  }

  private List<Entity> getMappingDependencies(Entity entity) {
    return mappingEntityRelationshipRepository
        .findByTargetAndRelationType(entity, MAPPED_TO)
        .stream()
        .flatMap(
            mer -> {
              var source = mer.getSource();
              return mer.getMappingEntityTypeRelationship().getEntityPathReferences().stream()
                  .map(
                      er -> {
                        try {
                          return mappingService.retrieveEntityByPath(
                              source.getId(), er.referencePath());
                        } catch (ServiceError e) {
                          throw new ServiceRuntimeError(e);
                        }
                      })
                  .filter(Optional::isPresent)
                  .map(Optional::get)
                  .filter(e -> hasTrait(e, PROVISIONABLE_RESOURCE));
            })
        .toList();
  }

  @Override
  protected void execute(Entity entity) throws ServiceError {
    Graph<Entity, DefaultEdge> provisioningGraph = new DefaultDirectedGraph<>(DefaultEdge.class);

    var aggregate = aggregateService.read(entity.getId(), true);
    var physicalResourceSequence = getPhysicalResourceSequence(aggregate);
    physicalResourceSequence.stream()
        .map(e -> new Tuple2<>(e, getMappingDependencies(e)))
        .toList()
        .forEach(
            t -> {
              if (!provisioningGraph.containsVertex(t._1)) provisioningGraph.addVertex(t._1);
              t._2.forEach(
                  e -> {
                    if (!provisioningGraph.containsVertex(e)) provisioningGraph.addVertex(e);
                    provisioningGraph.addEdge(e, t._1);
                  });
            });
    log.error(provisioningGraph.vertexSet().toString());
    log.error(provisioningGraph.edgeSet().toString());

    if (new CycleDetector<Entity, DefaultEdge>(provisioningGraph).detectCycles())
      throw new ServiceError("Cycle detected in provisioning graph");
  }

  @Override
  protected void checkInputType(Entity entity) throws ServiceError {
    if (!hasTrait(entity, "Provisionable"))
      throw new ServiceError(
          "Entity type: " + entity.getEntityType().getName() + " has not a trait Provisionable");
  }
}
