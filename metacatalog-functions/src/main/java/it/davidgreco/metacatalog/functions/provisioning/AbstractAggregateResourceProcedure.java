package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.service.ServiceUtils.hasTrait;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.AbstractEntityProcedure;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.Task;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.HashMap;
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
 * <p>Four procedures fill it in — {@link ProvisioningProcedure} and {@link
 * UnprovisioningProcedure}, {@link AuthorizationProcedure} and {@link RejectionProcedure} — and
 * each supplies exactly one answer: which {@link ProvisioningTask.Operation} it runs. Everything
 * else follows from it. The traits a run selects on come from the operation's {@link
 * it.davidgreco.metacatalog.entity.BuiltInCapability}, and so does the direction the tasks are
 * wired in, so a procedure cannot pair an operation with the wrong capability's traits.
 *
 * <p>They remain four classes rather than four beans of one class because {@code ProcedureExecutor}
 * resolves a procedure by {@code EntityProcedure.name()}, which defaults to the simple class name.
 */
public abstract class AbstractAggregateResourceProcedure extends AbstractEntityProcedure {

  private final ProvisioningTask.Operation operation;
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
      ProvisioningTask.Operation operation,
      AggregateService aggregateService,
      ResourceGraphBuilder resourceGraphBuilder,
      TaskManager taskManager,
      AggregateProvisioningStatusRecorder statusRecorder,
      ObjectProvider<DeferredTaskFactoryRegistrar> deferredRegistrars) {
    this.operation = operation;
    this.aggregateService = aggregateService;
    this.resourceGraphBuilder = resourceGraphBuilder;
    this.taskManager = taskManager;
    this.statusRecorder = statusRecorder;
    this.deferredRegistrars = deferredRegistrars;
  }

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
        operation,
        () -> {
          // Inside the plan-building transaction, before any task is created: entity types created
          // since startup exist by now, so a registrar that could not register at boot gets its
          // chance here.
          deferredRegistrars
              .orderedStream()
              .forEach(DeferredTaskFactoryRegistrar::ensureRegistered);
          var aggregate = aggregateService.read(entity.getId(), true);
          var resourceGraph =
              resourceGraphBuilder.buildResourceGraph(
                  aggregate, operation.capability().resourceTrait());
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
   * <p>A factory that does not produce a {@link ProvisioningTask} fails the run, in every
   * direction. Provisioning used to tolerate a plain {@link Task} — it happens to be the operation
   * a task runs by default, so nothing needed selecting — but a plain task records no status, and
   * the run would then leave the root {@code PROVISIONED} for a resource that reported nothing at
   * all. That is the same silence a resource type with no registered factory is refused for, and it
   * is worth no more here.
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
              if (!(task instanceof ProvisioningTask provisioningTask)) {
                throw new ServiceError(
                    "The task registered for entity type "
                        + e.getEntityType().getName()
                        + " is not a ProvisioningTask and cannot "
                        + operation.command());
              }
              provisioningTask.setOperation(operation);
              tasks.put(e.getId(), task);
            });
    return tasks;
  }

  /** Wires the tasks in the direction the operation asks for. */
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
                          if (operation.dependentsFirst()) {
                            derivedFrom.dependsOn(task);
                          } else {
                            task.dependsOn(derivedFrom);
                          }
                        }));
  }

  @Override
  protected void checkInputType(Entity entity) {
    var rootTrait = operation.capability().rootTrait();
    if (!hasTrait(entity, rootTrait))
      throw new ServiceError(
          "Entity type: " + entity.getEntityType().getName() + " has not a trait " + rootTrait);
  }
}
