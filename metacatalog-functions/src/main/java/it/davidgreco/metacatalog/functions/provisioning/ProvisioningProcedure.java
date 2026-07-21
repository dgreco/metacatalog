package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.entity.RelationType.MAPPED_TO;
import static it.davidgreco.metacatalog.service.CommonService.hasTrait;

import io.vavr.Tuple2;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.AbstractEntityProcedure;
import it.davidgreco.metacatalog.repository.MappingEntityRelationshipRepository;
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

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;
  private final MappingService mappingService;
  private final AggregateService aggregateService;
  private final TaskManager taskManager;

  private List<Entity> getPhysicalResourceSequence(AggregateService.AggregatePart aggregate) {
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

  /**
   * Executes the provisioning logic for the given entity.
   *
   * <p>This method builds the provisioning plan (aggregate read, dependency graph, task creation)
   * and submits it to the {@link TaskManager}. It returns the schedule ID so the caller ({@link
   * it.davidgreco.metacatalog.functions.ProcedureExecutor}) can join the schedule after the
   * plan-building transaction has committed. Previously this method called {@code
   * taskManager.joinSchedule(...)} inline, which blocked the transaction thread (and held its DB
   * connection) for the entire async execution — risking pool exhaustion / deadlock since the async
   * tasks themselves need connections from the same pool.
   *
   * @param entity the aggregate entity to provision
   * @return the schedule ID to join after the transaction commits
   * @throws ServiceError if provisioning fails or cycles are detected
   */
  @Override
  protected Optional<String> execute(Entity entity) throws ServiceError {
    try {
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
    } catch (ServiceRuntimeError e) {
      if (e.getCause() instanceof ServiceError se) throw se;
      else throw e;
    }
  }

  private Graph<Entity, DefaultEdge> buildProvisioningGraph(
      AggregateService.AggregatePart aggregate) throws ServiceError {
    Graph<Entity, DefaultEdge> provisioningGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
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
        .forEach(
            e -> {
              try {
                tasks.put(e.getId(), taskManager.createTask(e, e.getEntityType().getName()));
              } catch (ServiceError ex) {
                throw new ServiceRuntimeError(ex);
              }
            });
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

  /**
   * Validates that the entity has the Provisionable trait.
   *
   * @param entity the entity to validate
   * @throws ServiceError if the entity does not have the Provisionable trait
   */
  @Override
  protected void checkInputType(Entity entity) throws ServiceError {
    if (!hasTrait(entity, BuiltInTraits.PROVISIONABLE))
      throw new ServiceError(
          "Entity type: " + entity.getEntityType().getName() + " has not a trait Provisionable");
  }
}
