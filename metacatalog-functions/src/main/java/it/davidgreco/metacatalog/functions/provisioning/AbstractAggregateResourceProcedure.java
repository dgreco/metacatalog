package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.service.ServiceUtils.hasTrait;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.AbstractEntityProcedure;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.Task;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The shape every procedure that walks an aggregate's resources shares: read the aggregate, select
 * the members carrying a resource trait, build their dependency graph, turn each into a task from
 * the factory registered for its entity type, wire the tasks in one direction or the other, and
 * schedule them — with the whole thing recorded on the root.
 *
 * <p>Four procedures fill it in, two per capability and differing only in three answers:
 *
 * <table border="1">
 *   <caption>The four procedures</caption>
 *   <tr><th>Procedure</th><th>Root / resource trait</th><th>Operation</th><th>Order</th></tr>
 *   <tr><td>{@link ProvisioningProcedure}</td><td>Provisionable</td>
 *       <td>PROVISION</td><td>derived-from first</td></tr>
 *   <tr><td>{@link UnprovisioningProcedure}</td><td>Provisionable</td>
 *       <td>UNPROVISION</td><td>dependents first</td></tr>
 *   <tr><td>{@link AuthorizationProcedure}</td><td>Authorizable</td>
 *       <td>AUTHORIZE</td><td>derived-from first</td></tr>
 *   <tr><td>{@link RejectionProcedure}</td><td>Authorizable</td>
 *       <td>REJECT</td><td>dependents first</td></tr>
 * </table>
 *
 * <p>The direction is the part worth reading twice, and it is the same reasoning in both pairs.
 * Building up makes a resource wait for what it is derived from; tearing down makes what it is
 * derived from wait for <em>it</em>. An Athena table reading an S3 folder is created after the
 * folder and destroyed before it, and by the same token access to it is granted after — and
 * withdrawn before — access to the folder it reads, so nothing is ever left pointing at something
 * it can no longer reach.
 */
public abstract class AbstractAggregateResourceProcedure extends AbstractEntityProcedure {

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

  protected AbstractAggregateResourceProcedure(
      AggregateService aggregateService,
      ResourceGraphBuilder resourceGraphBuilder,
      TaskManager taskManager,
      AggregateProvisioningStatusRecorder statusRecorder,
      ObjectProvider<DeferredTaskFactoryRegistrar> deferredRegistrars) {
    this.aggregateService = aggregateService;
    this.resourceGraphBuilder = resourceGraphBuilder;
    this.taskManager = taskManager;
    this.statusRecorder = statusRecorder;
    this.deferredRegistrars = deferredRegistrars;
  }

  /** The trait the aggregate root must carry for this procedure to act on it. */
  protected abstract String rootTrait();

  /** The trait an aggregate member must carry to take part in the run. */
  protected abstract String resourceTrait();

  /** Which of the task's four directions this procedure runs. */
  protected abstract ProvisioningTask.Operation operation();

  /**
   * {@code false} to make each resource wait for what it is derived from (building up), {@code
   * true} to make what it is derived from wait for the resource (tearing down).
   */
  protected abstract boolean dependentsFirst();

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
        operation(),
        () -> {
          // Inside the plan-building transaction, before any task is created: entity types created
          // since startup exist by now, so a registrar that could not register at boot gets its
          // chance here.
          deferredRegistrars
              .orderedStream()
              .forEach(DeferredTaskFactoryRegistrar::ensureRegistered);
          var aggregate = aggregateService.read(entity.getId(), true);
          var resourceGraph = resourceGraphBuilder.buildResourceGraph(aggregate, resourceTrait());
          var tasks = createTasksForVertices(resourceGraph);
          wireDependencies(resourceGraph, tasks);
          var schedule = taskManager.createSchedule();
          for (var task : tasks.values()) {
            schedule.addTask(task);
          }
          return new AggregateProvisioningStatusRecorder.Plan(
              taskManager.schedule(schedule), tasks.size());
        });
  }

  /**
   * Creates a task per resource from its registered factory and switches it to this procedure's
   * operation.
   *
   * <p>{@link ProvisioningTask.Operation#PROVISION} is the task's own default, so a factory
   * producing a plain {@link Task} is still accepted there — it simply records no status of its
   * own. Every other direction has to be selected on the task, so a factory that does not produce a
   * {@link ProvisioningTask} is refused rather than silently provisioning instead.
   *
   * @throws ServiceError if a resource's factory produces something that cannot run this operation
   */
  private HashMap<String, Task> createTasksForVertices(Graph<Entity, DefaultEdge> resourceGraph) {
    var tasks = new HashMap<String, Task>();
    resourceGraph
        .vertexSet()
        .forEach(
            e -> {
              var task = taskManager.createTask(e);
              if (task instanceof ProvisioningTask provisioningTask) {
                provisioningTask.setOperation(operation());
              } else if (operation() != ProvisioningTask.Operation.PROVISION) {
                throw new ServiceError(
                    "The task registered for entity type "
                        + e.getEntityType().getName()
                        + " is not a ProvisioningTask and cannot "
                        + operation().name().toLowerCase(Locale.ROOT));
              }
              tasks.put(e.getId(), task);
            });
    return tasks;
  }

  /** Wires the tasks in the direction {@link #dependentsFirst()} asks for. */
  private void wireDependencies(
      Graph<Entity, DefaultEdge> resourceGraph, HashMap<String, Task> tasks) {
    tasks
        .values()
        .forEach(
            task ->
                resourceGraph.outgoingEdgesOf(task.getEntity()).stream()
                    .map(e -> tasks.get(resourceGraph.getEdgeTarget(e).getId()))
                    .forEach(
                        derivedFrom -> {
                          if (dependentsFirst()) {
                            derivedFrom.dependsOn(task);
                          } else {
                            task.dependsOn(derivedFrom);
                          }
                        }));
  }

  @Override
  protected void checkInputType(Entity entity) {
    if (!hasTrait(entity, rootTrait()))
      throw new ServiceError(
          "Entity type: " + entity.getEntityType().getName() + " has not a trait " + rootTrait());
  }
}
