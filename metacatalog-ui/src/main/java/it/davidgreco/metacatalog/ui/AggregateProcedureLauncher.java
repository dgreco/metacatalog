package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Entity;
import it.davidgreco.metacatalog.openapi.model.ProcedureStatus;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Launches the four aggregate procedures — provision, unprovision, authorize, reject — for
 * whichever page offers them, and leaves behind what the progress popup needs.
 *
 * <p>Every run is asynchronous: the API returns a schedule id right after plan-building (so a
 * missing aggregate or an illegitimate root still surfaces here, as a flash error), and the page
 * the caller redirects to seeds {@code procedure-poller.js} with it. The script polls {@code GET
 * /metacatalog/v1/procedure/{id}} until the run completes — so even a slow task never holds an HTTP
 * request open.
 *
 * <p>Shared rather than private to one controller because the same four buttons exist in both
 * halves of the UI: on an asset's page, and on the generic instances list. Two copies would be two
 * places to teach a fifth procedure to.
 */
final class AggregateProcedureLauncher {

  private static final Optional<Boolean> ASYNC = Optional.of(Boolean.TRUE);

  private final MetacatalogApiDelegate api;

  AggregateProcedureLauncher(MetacatalogApiDelegate api) {
    this.api = api;
  }

  /**
   * The procedure a posted path segment names, if any. Only the four procedures match: {@link
   * AggregateAction#DELETE} is an action but not a procedure, and an unknown segment matches
   * nothing — the buttons only ever offer the four, but a hand-crafted POST must not reach past
   * them.
   *
   * @param path the last path segment the form posted to
   */
  static Optional<AggregateAction> procedure(String path) {
    return Arrays.stream(AggregateAction.values())
        .filter(action -> action.isProcedure() && action.getPath().equals(path))
        .findFirst();
  }

  /**
   * Launches the procedure on the aggregate rooted at the given entity and flashes either the seed
   * of the progress popup or the reason the run could not start.
   *
   * @param redirectAttributes where the outcome is flashed for the page redirected to
   * @param action the procedure to run
   * @param entityId the aggregate root — a live one, or the frozen copy of a past version
   */
  void launch(RedirectAttributes redirectAttributes, AggregateAction action, String entityId) {
    var label = action.getProcedureLabel();
    try {
      var status = call(action, entityId).getBody();
      if (status != null && status.getScheduleId() != null) {
        redirectAttributes.addFlashAttribute("procedureScheduleId", status.getScheduleId());
        redirectAttributes.addFlashAttribute("procedureLabel", label);
        redirectAttributes.addFlashAttribute("procedureEntityId", entityId);
        redirectAttributes.addFlashAttribute("procedureEntityType", entityTypeOf(entityId));
      } else {
        // No schedule id to poll (should not happen): fall back to a plain confirmation.
        redirectAttributes.addFlashAttribute("message", label + " started.");
      }
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
  }

  /**
   * The delegate call each procedure action makes. The generated delegate has one typed method per
   * operation, so this is where the action becomes a call; everything either side of it is shared.
   */
  private ResponseEntity<ProcedureStatus> call(AggregateAction action, String entityId) {
    return switch (action) {
      case PROVISION -> api.provisionAggregate(entityId, ASYNC);
      case UNPROVISION -> api.unprovisionAggregate(entityId, ASYNC);
      case AUTHORIZE -> api.authorizeAggregate(entityId, ASYNC);
      case REJECT -> api.rejectAggregate(entityId, ASYNC);
      case DELETE ->
          throw new IllegalArgumentException("DELETE is not a procedure: " + action.getPath());
    };
  }

  /** The aggregate's entity type name, for the progress popup; best-effort, empty if unknown. */
  private String entityTypeOf(String entityId) {
    try {
      var entity = (Entity) api.getEntity(entityId).getBody();
      return entity != null && entity.getEntityType() != null ? entity.getEntityType() : "";
    } catch (RuntimeException e) {
      return "";
    }
  }
}
