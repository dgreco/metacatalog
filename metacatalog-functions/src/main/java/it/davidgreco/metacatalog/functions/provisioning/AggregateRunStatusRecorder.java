package it.davidgreco.metacatalog.functions.provisioning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vavr.control.Try;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.TaskManager;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a run's overall outcome on the aggregate root — the entity the procedure was invoked on —
 * in the same pair of fields the tasks write on each resource. Which pair that is comes from the
 * {@link ProvisioningTask.Operation}: a provisioning run writes {@code provisioningStatus} /{@code
 * provisioningResult} on the {@code Provisionable} root, an authorization run writes {@code
 * authorizationStatus} /{@code authorizationResult} on the {@code Authorizable} one. Either way the
 * root trait carries the same schema as its resource counterpart, which is what makes the root a
 * legal place to write the whole run's answer.
 *
 * <p>The root is {@code PROVISIONED} / {@code UNPROVISIONED} / {@code AUTHORIZED} / {@code
 * REJECTED} only when the whole schedule succeeded — i.e. every contained resource completed
 * successfully — and {@code FAILED} with the error otherwise. The write happens by
 * <em>composing</em> the schedule's future rather than merely observing it: {@link #recording}
 * returns a future that completes only after the root status is written, and the procedures hand
 * that future back in their {@link it.davidgreco.metacatalog.service.TaskManager.ScheduleHandle}. A
 * synchronous call thus returns — and an asynchronous run reports its terminal state — only once
 * the root reflects the outcome, never before.
 *
 * <p>The root's values are re-read at completion time, not carried over from the plan-building
 * transaction: a root that also carries the resource trait (an intermediate node run as its own
 * task) has its resource-level status written during the run, and a stale snapshot would erase it.
 * Re-reading is necessary but not sufficient on its own — the write takes a transaction of its own
 * so that the re-read cannot be answered from the plan-building transaction's persistence context,
 * which would hand back exactly the snapshot it is trying to avoid. See the field comment on {@code
 * ownTransaction}.
 *
 * <p>A failure to record the status never alters the run's result — the returned future preserves
 * the original outcome, and the write error is logged. The run's truth lives in the schedule
 * result; the root fields are its durable, queryable reflection.
 */
@Slf4j
@Service
public class AggregateRunStatusRecorder {

  private final EntityService entityService;

  /**
   * The status write runs in a transaction of its own, and this is load-bearing twice over.
   *
   * <p>The callback is not guaranteed to run on the async executor. {@code Schedule.schedule}
   * composes with {@code allOf(...).thenApply(...)} and the recorder attaches with {@code
   * whenComplete}; either runs <em>inline on the calling thread</em> when the futures it is given
   * have already completed. For an empty or fast schedule that thread is the one building the plan,
   * still inside {@code ProcedureExecutor}'s transaction — which has already read the root. A
   * re-read there is served from that transaction's persistence context, returning the plan-time
   * instance rather than a fresh row, and writing it back reverts whatever the run committed
   * meanwhile. Suspending it and starting a new one is what makes the re-read a real one.
   *
   * <p>It also makes the read-modify-write atomic. On the async path the callback runs with no
   * transaction at all, so the read and the write would otherwise be two of them, with room between
   * for a concurrent writer to lose an update.
   */
  private final TransactionTemplate ownTransaction;

  AggregateRunStatusRecorder(
      EntityService entityService, PlatformTransactionManager transactionManager) {
    this.entityService = entityService;
    this.ownTransaction = new TransactionTemplate(transactionManager);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * A built plan: the schedule that was handed to the executor and how many resources it covers.
   *
   * @param handle the scheduled run
   * @param resourceCount how many resources it covers, for the result message
   */
  record Plan(TaskManager.ScheduleHandle handle, int resourceCount) {}

  /**
   * Runs {@code planner} and returns its schedule wrapped so the root records the run's outcome —
   * covering the case where there is no run to wait for because planning itself failed.
   *
   * <p>That case is not exotic. A resource type with no registered task factory, a cycle in the
   * dependency graph and a throwing {@link DeferredTaskFactoryRegistrar} all fail here, before any
   * future exists to attach a completion to. The caller gets the error, but the root is the
   * durable, queryable reflection of the outcome — the UI and SPARQL read it — and left alone it
   * keeps the {@code PROVISIONED} of the last run that did succeed, which is worse than no answer.
   *
   * @param rootEntityId the aggregate root the procedure was invoked on
   * @param operation which direction the run goes, deciding the success status
   * @param planner builds the plan and schedules it
   * @return the schedule, wrapped so its future completes only after the status is written
   */
  Optional<TaskManager.ScheduleHandle> recordingAround(
      String rootEntityId, ProvisioningTask.Operation operation, Supplier<Plan> planner) {
    Plan plan;
    try {
      plan = planner.get();
    } catch (RuntimeException planningFailed) {
      recordPlanFailure(rootEntityId, operation, planningFailed);
      throw planningFailed;
    }
    return Optional.of(
        new TaskManager.ScheduleHandle(
            plan.handle().id(),
            recording(plan.handle().future(), rootEntityId, operation, plan.resourceCount())));
  }

  /**
   * Marks the root FAILED for a run that never started.
   *
   * <p>The root is read first, in a transaction of its own, to tell the two failures apart. When
   * the root is itself why planning failed — no such entity, or not a legitimate aggregate — there
   * is nothing to mark and the caller already has that error, so this stays silent rather than
   * logging an error for what is really a 404. The read is deliberately not part of the write's
   * transaction: an exception escaping a participating {@code @Transactional} call marks it
   * rollback-only, and catching it would then surface as an {@code UnexpectedRollbackException} at
   * commit, replacing the failure the caller is supposed to see.
   */
  private void recordPlanFailure(
      String rootEntityId, ProvisioningTask.Operation operation, Throwable failure) {
    try {
      ownTransaction.execute(transaction -> entityService.read(rootEntityId));
    } catch (RuntimeException rootNotReadable) {
      return;
    }
    try {
      writeRootStatus(
          rootEntityId, operation, ProvisioningTask.STATUS_FAILED, failureMessage(null, failure));
    } catch (Exception e) {
      log.error("Failed to record the plan failure on root entity {}", rootEntityId, e);
    }
  }

  /**
   * Composes the schedule future with the root-status write.
   *
   * @param future the schedule's result future
   * @param rootEntityId the aggregate root the procedure was invoked on
   * @param operation which direction the run went, deciding the success status
   * @param resourceCount how many resources the run covered, for the result message
   * @return a future with the same outcome that completes only after the status is recorded
   */
  CompletableFuture<Try<Void>> recording(
      CompletableFuture<Try<Void>> future,
      String rootEntityId,
      ProvisioningTask.Operation operation,
      int resourceCount) {
    return future.whenComplete(
        (result, exception) -> {
          var succeeded = exception == null && result != null && result.isSuccess();
          var status = succeeded ? operation.successStatus() : ProvisioningTask.STATUS_FAILED;
          var message =
              succeeded
                  ? operation.label()
                      + " succeeded: all "
                      + resourceCount
                      + " resource(s) "
                      + operation.successStatus().toLowerCase(Locale.ROOT)
                  : failureMessage(result, exception);
          try {
            writeRootStatus(rootEntityId, operation, status, message);
          } catch (Exception e) {
            log.error(
                "Failed to record aggregate {} status {} on root entity {}",
                operation.label(),
                status,
                rootEntityId,
                e);
          }
        });
  }

  private void writeRootStatus(
      String rootEntityId, ProvisioningTask.Operation operation, String status, String message) {
    ownTransaction.executeWithoutResult(
        transaction -> {
          var root = entityService.read(rootEntityId);
          if (!(root.getValues() instanceof ObjectNode values)) {
            log.error(
                "Root entity {} values are not a JSON object; cannot record the run status",
                rootEntityId);
            return;
          }
          values.put(operation.statusField(), status);
          values.put(operation.resultField(), message);
          entityService.updateValues(rootEntityId, values.toPrettyString());
        });
  }

  private static String failureMessage(Try<Void> result, Throwable exception) {
    var cause = exception != null ? exception : result != null ? result.getCause() : null;
    if (cause == null) return "unknown failure";
    return describe(cause);
  }

  /**
   * The failure's own message, plus its root cause when that says something else.
   *
   * <p>The outer message is usually a wrapper: a task reports "Provisioning failed for entity
   * &lt;id&gt;", which identifies the resource but not what went wrong with it. The reason is one
   * or more hops down the cause chain, and this field is where someone looks to find out why a run
   * failed — the resource's own {@code provisioningResult} has the detail, but only if you know
   * which resource to open.
   */
  private static String describe(Throwable cause) {
    var message =
        cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    var root = cause;
    // Bounded rather than "until null": a cause chain that loops would otherwise hang the callback.
    for (var hop = 0; hop < 10 && root.getCause() != null && root.getCause() != root; hop++) {
      root = root.getCause();
    }
    var rootMessage = root.getMessage();
    if (root == cause || rootMessage == null || message.contains(rootMessage)) {
      return message;
    }
    return message + ": " + rootMessage;
  }
}
