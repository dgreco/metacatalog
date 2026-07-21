package it.davidgreco.metacatalog.service;

/**
 * Checked exception thrown by service layer operations.
 *
 * <p>This exception is used to indicate business logic errors that can be handled and recovered
 * from, such as entity not found, validation failures, or constraint violations.
 */
public class ServiceError extends Exception {

  /** Generic, non-leaking message used whenever a database constraint is violated. */
  public static final String DATA_INTEGRITY_VIOLATION =
      "A resource with the same key already exists or a database constraint was violated";

  /**
   * Creates a new service error with the specified message.
   *
   * @param message the error message describing what went wrong
   */
  public ServiceError(String message) {
    super(message);
  }

  /**
   * Creates a new service error with the specified message and underlying cause.
   *
   * @param message the error message describing what went wrong
   * @param cause the underlying cause of this error, preserved for diagnostics
   */
  public ServiceError(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Builds a {@link ServiceError} for a {@link
   * org.springframework.dao.DataIntegrityViolationException} without leaking the raw database
   * message (constraint name, table/column names, duplicate-key details) to the API client. The
   * original exception is preserved as the cause for server-side diagnostics.
   *
   * @param cause the data integrity violation raised by the data access layer
   * @return a {@link ServiceError} carrying a generic, client-safe message
   */
  public static ServiceError forDataIntegrity(Throwable cause) {
    return new ServiceError(DATA_INTEGRITY_VIOLATION, cause);
  }
}
