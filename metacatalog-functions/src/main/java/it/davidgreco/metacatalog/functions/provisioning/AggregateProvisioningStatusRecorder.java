package it.davidgreco.metacatalog.functions.provisioning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vavr.control.Try;
import it.davidgreco.metacatalog.service.EntityService;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Records a provisioning run's overall outcome on the aggregate root — the {@code Provisionable}
 * entity the procedure was invoked on — in the same {@code provisioningStatus} /{@code
 * provisioningResult} fields the tasks write on each resource (the {@code Provisionable} trait
 * carries the same schema as {@code ProvisionableResource}).
 *
 * <p>The root is {@code PROVISIONED} (or {@code UNPROVISIONED}) only when the whole schedule
 * succeeded — i.e. every contained {@code ProvisionableResource} completed successfully — and
 * {@code FAILED} with the error otherwise. The write happens by <em>composing</em> the schedule's
 * future rather than merely observing it: {@link #recording} returns a future that completes only
 * after the root status is written, and the procedures hand that future back in their {@link
 * it.davidgreco.metacatalog.service.TaskManager.ScheduleHandle}. A synchronous call thus returns —
 * and an asynchronous run reports its terminal state — only once the root reflects the outcome,
 * never before.
 *
 * <p>The root's values are re-read at completion time, not carried over from the plan-building
 * transaction: a root that also carries {@code ProvisionableResource} (an intermediate node
 * provisioned as its own task) has its resource-level status written during the run, and a stale
 * snapshot would erase it.
 *
 * <p>A failure to record the status never alters the run's result — the returned future preserves
 * the original outcome, and the write error is logged. The run's truth lives in the schedule
 * result; the root fields are its durable, queryable reflection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregateProvisioningStatusRecorder {

  private final EntityService entityService;

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
                      + operation.successStatus().toLowerCase()
                  : failureMessage(result, exception);
          try {
            writeRootStatus(rootEntityId, status, message);
          } catch (Exception e) {
            log.error(
                "Failed to record aggregate provisioning status {} on root entity {}",
                status,
                rootEntityId,
                e);
          }
        });
  }

  private void writeRootStatus(String rootEntityId, String status, String message) {
    var root = entityService.read(rootEntityId);
    if (!(root.getValues() instanceof ObjectNode values)) {
      log.error(
          "Root entity {} values are not a JSON object; cannot record provisioning status",
          rootEntityId);
      return;
    }
    values.put(ProvisioningTask.PROVISIONING_STATUS, status);
    values.put(ProvisioningTask.PROVISIONING_RESULT, message);
    entityService.updateValues(rootEntityId, values.toPrettyString());
  }

  private static String failureMessage(Try<Void> result, Throwable exception) {
    var cause = exception != null ? exception : result != null ? result.getCause() : null;
    if (cause == null) return "unknown failure";
    return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
  }
}
