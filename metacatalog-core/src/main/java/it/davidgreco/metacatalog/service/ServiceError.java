package it.davidgreco.metacatalog.service;

/**
 * Checked exception thrown by service layer operations.
 *
 * <p>This exception is used to indicate business logic errors that can be handled and recovered
 * from, such as entity not found, validation failures, or constraint violations.
 */
public class ServiceError extends Exception {

  /**
   * Creates a new service error with the specified message.
   *
   * @param message the error message describing what went wrong
   */
  public ServiceError(String message) {
    super(message);
  }
}
