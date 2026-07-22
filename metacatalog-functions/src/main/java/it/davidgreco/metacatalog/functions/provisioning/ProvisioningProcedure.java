package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.service.ServiceUtils.hasTrait;

import io.vavr.Tuple2;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.AbstractEntityProcedure;
import it.davidgreco.metacatalog.service.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.stereotype.Service;

/**
 * Procedure for provisioning entities with the Provisionable trait.
 *
 * <p>This procedure orchestrates the provisioning of aggregate entities by:
 *
 * <ol>
 *   <li>Reading the aggregate structure including mapped instances
 *   <li>Identifying all ProvisionableResource entities in the hierarchy
 *   <li>Building a dependency graph based on mapping relationships
 *   <li>Creating provisioning tasks for each resource
 *   <li>Scheduling and executing tasks in dependency order
 * </ol>
 *
 * <p>The procedure detects cycles in the provisioning graph and fails early if any are found.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ProvisioningProcedure extends AbstractEntityProcedure {

  private static final String PROVISIONABLE_RESOURCE = BuiltInTraits.PROVISIONABLE_RESOURCE;

  private final MappingService mappingService;
  private final EntityPathResolver entityPathResolver;
  private final AggregateService aggregateService;
  private final TaskManager taskManager;

  private List<Entity> getProvisionableResourceSequence(AggregateService.AggregatePart aggregate) {
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

  @Override
  protected Optional<String> execute(Entity entity) {
    var aggregate = aggregateService.read(entity.getId(), true);
    var provisioningGraph = buildProvisioningGraph(aggregate);
    var tasks = createTasksForVertices(provisioningGraph);
    wireDependencies(provisioningGraph, tasks);
    var schedule = taskManager.createSchedule();
    for (var task : tasks.values()) {
      schedule.addTask(task);
    }
    taskManager.schedule(schedule);
    return Optional.of(schedule.getId());
  }

  private Graph<Entity, DefaultEdge> buildProvisioningGraph(
      AggregateService.AggregatePart aggregate) {
    Graph<Entity, DefaultEdge> provisioningGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
    var provisionableResourceSequence = getProvisionableResourceSequence(aggregate);
    provisionableResourceSequence.stream()
        .map(e -> new Tuple2<>(e, getMappingDependencies(e)))
        .toList()
        .forEach(
            t -> {
              if (!provisioningGraph.containsVertex(t._1)) provisioningGraph.addVertex(t._1);
              t._2.forEach(
                  e -> {
                    if (!provisioningGraph.containsVertex(e)) provisioningGraph.addVertex(e);
                    provisioningGraph.addEdge(t._1, e);
                  });
            });
    if (new CycleDetector<>(provisioningGraph).detectCycles())
      throw new ServiceError("Cycle detected in provisioning graph");
    return provisioningGraph;
  }

  private HashMap<String, Task<Entity>> createTasksForVertices(
      Graph<Entity, DefaultEdge> provisioningGraph) {
    var tasks = new HashMap<String, Task<Entity>>();
    provisioningGraph
        .vertexSet()
        .forEach(e -> tasks.put(e.getId(), taskManager.createTask(e, e.getEntityType().getName())));
    return tasks;
  }

  private void wireDependencies(
      Graph<Entity, DefaultEdge> provisioningGraph, HashMap<String, Task<Entity>> tasks) {
    tasks
        .values()
        .forEach(
            task -> {
              var dependsOnTasks =
                  provisioningGraph.outgoingEdgesOf(task.getEntity()).stream()
                      .map(e -> tasks.get(provisioningGraph.getEdgeTarget(e).getId()))
                      .toList();
              dependsOnTasks.forEach(task::dependsOn);
            });
  }

  @Override
  protected void checkInputType(Entity entity) {
    if (!hasTrait(entity, BuiltInTraits.PROVISIONABLE))
      throw new ServiceError(
          "Entity type: " + entity.getEntityType().getName() + " has not a trait Provisionable");
  }
}
