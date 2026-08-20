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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The inverse of {@link ProvisioningProcedure}: tears down every ProvisionableResource in an
 * aggregate, leaving each one {@code UNPROVISIONED}.
 *
 * <p>It reads the same aggregate, builds the same graph through {@link ResourceGraphBuilder} and
 * takes its tasks from the same {@link TaskFactory} registered against each resource's entity type
 * name. Two things differ.
 *
 * <p>First, the tasks are told to run {@link ProvisioningTask.Operation#UNPROVISION}, so they call
 * {@code unprovision()} and record {@code UNPROVISIONED} rather than {@code PROVISIONED}.
 *
 * <p>Second — and this is the part worth reading twice — the dependencies are wired the other way
 * round. Provisioning makes a resource wait for what it is built from; unprovisioning makes what it
 * is built from wait for <em>it</em>. An Athena table reading an S3 folder is created after the
 * folder and destroyed before it, so nothing is ever removed while something still depends on it.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class UnprovisioningProcedure extends AbstractEntityProcedure {

  private final AggregateService aggregateService;
  private final ResourceGraphBuilder resourceGraphBuilder;
  private final TaskManager taskManager;
  private final AggregateProvisioningStatusRecorder statusRecorder;

  /**
   * Registrars whose task factories key off entity types created at runtime, so they cannot
   * register at startup. An {@link ObjectProvider} rather than a {@code List}: injecting a list
   * when no such bean exists fails outright, and none is the default.
   */
  private final ObjectProvider<DeferredTaskFactoryRegistrar> deferredRegistrars;

  /**
   * Planning runs inside {@code recordingAround} so the root ends up reflecting the outcome whether
   * the run finishes or never starts: the returned future completes only after the status is
   * written, and a failure to build the plan at all is recorded before it propagates. Callers and
   * status polls therefore never see a finished — or abandoned — run behind a stale root.
   */
  @Override
  protected Optional<TaskManager.ScheduleHandle> execute(Entity entity) {
    return statusRecorder.recordingAround(
        entity.getId(),
        ProvisioningTask.Operation.UNPROVISION,
        () -> {
          // Inside the plan-building transaction, before any task is created: entity types created
          // since startup exist by now, so a registrar that could not register at boot gets its
          // chance here.
          deferredRegistrars
              .orderedStream()
              .forEach(DeferredTaskFactoryRegistrar::ensureRegistered);
          var aggregate = aggregateService.read(entity.getId(), true);
          var resourceGraph = resourceGraphBuilder.buildResourceGraph(aggregate);
          var tasks = createTasksForVertices(resourceGraph);
          wireReverseDependencies(resourceGraph, tasks);
          var schedule = taskManager.createSchedule();
          for (var task : tasks.values()) {
            schedule.addTask(task);
          }
          return new AggregateProvisioningStatusRecorder.Plan(
              taskManager.schedule(schedule), tasks.size());
        });
  }

  /**
   * Creates a task per resource from its registered factory and switches it to unprovisioning.
   *
   * @throws ServiceError if a task's factory produces something that is not a {@link
   *     ProvisioningTask} — the operation could not then be selected, and the task would silently
   *     provision instead of tearing down
   */
  private HashMap<String, Task> createTasksForVertices(Graph<Entity, DefaultEdge> resourceGraph) {
    var tasks = new HashMap<String, Task>();
    resourceGraph
        .vertexSet()
        .forEach(
            e -> {
              var task = taskManager.createTask(e);
              if (!(task instanceof ProvisioningTask provisioningTask)) {
                throw new ServiceError(
                    "The task registered for entity type "
                        + e.getEntityType().getName()
                        + " is not a ProvisioningTask and cannot unprovision");
              }
              provisioningTask.setOperation(ProvisioningTask.Operation.UNPROVISION);
              tasks.put(e.getId(), task);
            });
    return tasks;
  }

  /**
   * Makes each resource's task depend on the tasks of the resources derived from it — the reverse
   * of provisioning — so dependents are torn down first.
   */
  private void wireReverseDependencies(
      Graph<Entity, DefaultEdge> resourceGraph, HashMap<String, Task> tasks) {
    tasks
        .values()
        .forEach(
            task ->
                resourceGraph.outgoingEdgesOf(task.getEntity()).stream()
                    .map(e -> tasks.get(resourceGraph.getEdgeTarget(e).getId()))
                    .forEach(dependency -> dependency.dependsOn(task)));
  }

  @Override
  protected void checkInputType(Entity entity) {
    if (!hasTrait(entity, BuiltInTraits.PROVISIONABLE))
      throw new ServiceError(
          "Entity type: " + entity.getEntityType().getName() + " has not a trait Provisionable");
  }
}
