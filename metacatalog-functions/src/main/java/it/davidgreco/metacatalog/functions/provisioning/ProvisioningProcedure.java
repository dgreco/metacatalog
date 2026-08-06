package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.service.ServiceUtils.hasTrait;

import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.AbstractEntityProcedure;
import it.davidgreco.metacatalog.service.*;
import java.util.HashMap;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
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
 *
 * <p>Each task comes from the {@link TaskFactory} registered against the resource's entity type
 * name, so a resource type with no registered factory fails the run rather than being reported as
 * provisioned by nothing. See {@link UnprovisioningProcedure} for the inverse.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ProvisioningProcedure extends AbstractEntityProcedure {

  private final AggregateService aggregateService;
  private final ResourceGraphBuilder resourceGraphBuilder;
  private final TaskManager taskManager;

  @Override
  protected Optional<TaskManager.ScheduleHandle> execute(Entity entity) {
    var aggregate = aggregateService.read(entity.getId(), true);
    var provisioningGraph = resourceGraphBuilder.buildResourceGraph(aggregate);
    var tasks = createTasksForVertices(provisioningGraph);
    wireDependencies(provisioningGraph, tasks);
    var schedule = taskManager.createSchedule();
    for (var task : tasks.values()) {
      schedule.addTask(task);
    }
    var handle = taskManager.schedule(schedule);
    return Optional.of(handle);
  }

  private HashMap<String, Task> createTasksForVertices(
      Graph<Entity, DefaultEdge> provisioningGraph) {
    var tasks = new HashMap<String, Task>();
    provisioningGraph.vertexSet().forEach(e -> tasks.put(e.getId(), taskManager.createTask(e)));
    return tasks;
  }

  /**
   * Makes each task depend on the tasks of the resources it is derived from, so a resource is
   * provisioned only once everything it is built from exists.
   */
  private void wireDependencies(
      Graph<Entity, DefaultEdge> provisioningGraph, HashMap<String, Task> tasks) {
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
