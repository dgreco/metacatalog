package it.davidgreco.metacatalog.service;

/**
 * Unchecked runtime exception for service layer operations.
 *
 * <p>This exception is used to wrap checked exceptions in contexts where only unchecked exceptions
 * can be thrown (e.g., lambdas, streams) or to indicate unrecoverable errors.
 */
public class ServiceRuntimeError extends RuntimeException {

  /**
   * Creates a new runtime error with the specified message.
   *
   * @param message the error message describing what went wrong
   */
  public ServiceRuntimeError(String message) {
    super(message);
  }

  /**
   * Creates a new runtime error wrapping another throwable.
   *
   * @param cause the underlying cause of this error
   */
  public ServiceRuntimeError(Throwable cause) {
    super(cause);
  }
}
