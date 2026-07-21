package it.davidgreco.metacatalog.service;

/**
 * Checked exception thrown by service layer operations when a named or identified resource (entity,
 * entity type, trait, version, mapping, ...) cannot be found.
 *
 * <p>The API layer maps this to HTTP {@code 404 Not Found} (instead of the generic {@code 400} used
 * for {@link ServiceError}) so that clients can distinguish "your input was well-formed but the
 * resource does not exist" from "your input was malformed".
 */
public class NotFoundException extends ServiceError {

  /**
   * Creates a new not-found error with the specified message.
   *
   * @param message the error message describing which resource was not found
   */
  public NotFoundException(String message) {
    super(message);
  }
}
