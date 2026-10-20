package it.davidgreco.metacatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.common.JsonSchema;
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
   * <p>The message lists them too. The REST API answers with {@link #getErrors()}, but some callers
   * only ever see the message — the UI, which may not depend on this module, and the recorded error
   * of a failed lifecycle event or procedure run — and a bare "Schema validation failed" leaves
   * them nothing to act on.
   *
   * @param errors the list of validation error messages
   */
  public SchemaValidationError(List<String> errors) {
    super("Schema validation failed: " + String.join("; ", errors));
    this.errors = errors;
  }

  /**
   * Validates {@code values} against {@code schema} and throws this exception if validation fails.
   *
   * @param schema the JSON schema to validate against
   * @param values the JSON node to validate
   * @throws SchemaValidationError if validation produces any messages
   */
  public static void validateOrThrow(JsonSchema schema, JsonNode values) {
    var messages = schema.validate(values);
    if (!messages.isEmpty()) {
      throw new SchemaValidationError(messages);
    }
  }
}
