package it.davidgreco.metacatalog.service;

import java.util.List;
import lombok.Getter;

/**
 * Exception thrown when JSON schema validation fails.
 *
 * <p>This exception contains a list of validation error messages describing which schema
 * constraints were violated.
 */
@Getter
public class SchemaValidationError extends ServiceError {

  /** The list of validation error messages. */
  private final List<String> errors;

  /**
   * Creates a new schema validation error with the specified validation errors.
   *
   * @param errors the list of validation error messages
   */
  public SchemaValidationError(List<String> errors) {
    super("Schema validation failed");
    this.errors = errors;
  }
}
