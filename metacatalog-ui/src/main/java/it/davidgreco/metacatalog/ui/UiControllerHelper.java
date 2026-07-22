package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Shared static helpers used across the split UI controllers: blank-to-empty {@link Optional}
 * conversion, flash-and-redirect boilerplate for delete handlers, friendly delete-error messages,
 * timestamp formatting for version snapshots, and trait-name extraction from frozen JSON arrays.
 */
final class UiControllerHelper {

  /** Formats a snapshot's capture instant as a readable UTC timestamp. */
  static final DateTimeFormatter INSTANT_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

  private UiControllerHelper() {}

  /** Treats blank strings as absent, so an empty father / schema field becomes {@code empty()}. */
  static Optional<String> optional(String value) {
    return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
  }

  /**
   * Runs an action, flashes a success message on success or a friendly error on failure, and
   * redirects to the given path. The {@code kind} and {@code name} are used to build the
   * user-friendly error message.
   */
  static String flashAndRedirect(
      Runnable action,
      String successMessage,
      String kind,
      String name,
      String redirect,
      RedirectAttributes redirectAttributes) {
    try {
      action.run();
      redirectAttributes.addFlashAttribute("message", successMessage);
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", deleteError(kind, name, e));
    }
    return "redirect:" + redirect;
  }

  /**
   * Runs an action, flashes a success message on success or an error message on failure, and
   * redirects to the given path. Simpler variant for operations where the error message is shown
   * verbatim.
   */
  static String flashAndRedirect(
      Runnable action,
      String successMessage,
      String redirect,
      RedirectAttributes redirectAttributes) {
    try {
      action.run();
      redirectAttributes.addFlashAttribute("message", successMessage);
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:" + redirect;
  }

  /**
   * Builds the {@link VersionView} rows for an entity type's version history. The live row is
   * tagged {@code live = true} and carries its trait names; snapshots carry their frozen trait
   * names extracted from the {@code traits} JSON array.
   */
  static String formatInstant(Instant instant) {
    return instant == null ? null : INSTANT_FMT.format(instant);
  }

  /**
   * Extracts the trait names frozen in a version's {@code traits} JSON array. Returns an empty list
   * when the node is missing or not an array.
   */
  static List<String> traitNames(JsonNode traitsNode) {
    if (traitsNode == null || !traitsNode.isArray()) return List.of();
    return StreamSupport.stream(traitsNode.spliterator(), false)
        .filter(JsonNode::isTextual)
        .map(JsonNode::asText)
        .collect(Collectors.toList());
  }

  /**
   * Turns a delete failure into a user-friendly message: a missing target reads as "not found",
   * anything else (a foreign-key / integrity violation) reads as "still in use" rather than leaking
   * the raw database error.
   */
  static String deleteError(String kind, String name, Exception e) {
    var message = e.getMessage() == null ? "" : e.getMessage();
    if (message.contains("not found")) {
      return kind + " '" + name + "' was not found.";
    }
    return "Could not delete "
        + kind.toLowerCase(java.util.Locale.ROOT)
        + " '"
        + name
        + "': it is still in use (referenced by another type, a relationship, or an entity).";
  }
}
