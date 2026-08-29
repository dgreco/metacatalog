package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.service.ServiceUtils.hasTrait;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.AbstractEntityProcedure;
import it.davidgreco.metacatalog.service.AccessPolicy;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.Task;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The one procedure that walks an aggregate's resources: read the aggregate, select the members
 * carrying a resource trait, build their dependency graph, turn each into a task from the factory
 * registered for its entity type, wire the tasks in one direction or the other, and schedule them —
 * with the whole thing recorded on the root.
 *
 * <p>Everything that distinguishes one run from another is the {@link ProvisioningTask.Operation}:
 * the traits a run selects on come from the operation's {@link
 * it.davidgreco.metacatalog.entity.BuiltInCapability}, and so do the direction the tasks are wired
 * in, the status pair the outcome is recorded in and the {@linkplain #name() name} the procedure
 * answers to — so a procedure cannot pair an operation with the wrong capability's traits. {@link
 * AggregateResourceProcedures} declares one bean of this class per operation; a fifth direction is
 * an enum constant and a bean method, not a new class.
 *
 * <p><b>The direction is the part worth reading twice.</b> Building up ({@code PROVISION}, {@code
 * AUTHORIZE}) makes a resource wait for what it is derived from; tearing down ({@code UNPROVISION},
 * {@code REJECT}) makes what it is derived from wait for <em>it</em>. An Athena table reading an S3
 * folder is created after the folder and destroyed before it, and by the same token access to it is
 * granted after — and withdrawn before — access to the folder it reads, so nothing is ever left
 * holding a grant on something it can no longer reach. {@code ProvisioningProcedureTests} and
 * {@code AuthorizationProcedureTests} assert both orders.
 */
public final class AggregateResourceProcedure extends AbstractEntityProcedure {

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

  AggregateResourceProcedure(
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
   * The operation's own {@link ProvisioningTask.Operation#procedureName()} — {@code
   * ProvisioningProcedure}, {@code AuthorizationProcedure}, … — kept from when each was a class of
   * that name, since it is the registry key {@code ProcedureExecutor} resolves and the name in
   * durable {@code procedure_run} rows.
   */
  @Override
  public String name() {
    return operation.procedureName();
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
          var accessGrants = resolveAccessGrants(aggregate.entity(), resourceGraph.vertexSet());
          var tasks = createTasksForVertices(resourceGraph, accessGrants);
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
   * Resolves the root's access policy into the grants that apply to each resource, for the one
   * operation that applies a policy at all.
   *
   * <p>This happens here, once, inside the plan-building transaction — not in each task. The policy
   * lives on the root; the procedure has already read the whole aggregate; and every way a policy
   * can be wrong (a grant naming an undeclared principal or permission, a selector reaching
   * nothing) is found before a single task runs. Because planning is wrapped in {@code
   * recordingAround}, such a refusal is recorded as {@code FAILED} on the root rather than leaving
   * behind the {@code AUTHORIZED} of the last run that worked.
   *
   * @return each resource id mapped to its grants; empty for every operation that applies no
   *     policy, which leaves each task with the empty list it defaults to
   */
  private Map<String, List<AccessPolicy.EffectiveGrant>> resolveAccessGrants(
      Entity root, Set<Entity> resources) {
    if (!operation.appliesAccessPolicy()) return Map.of();
    return AccessPolicy.of(root).resolve(resources);
  }

  /**
   * Creates a task per resource from its registered factory, switches it to this procedure's
   * operation, and hands it the grants the root's policy resolved for its resource.
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
  private Map<String, Task> createTasksForVertices(
      Graph<Entity, DefaultEdge> resourceGraph,
      Map<String, List<AccessPolicy.EffectiveGrant>> accessGrants) {
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
              provisioningTask.setAccessGrants(accessGrants.getOrDefault(e.getId(), List.of()));
              tasks.put(e.getId(), task);
            });
    return tasks;
  }

  /** Wires the tasks in the direction the operation asks for. */
  private void wireDependencies(Graph<Entity, DefaultEdge> resourceGraph, Map<String, Task> tasks) {
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
