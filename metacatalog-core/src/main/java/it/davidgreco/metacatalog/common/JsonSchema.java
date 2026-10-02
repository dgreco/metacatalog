package it.davidgreco.metacatalog.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import java.util.List;

/**
 * A JSON Schema compiled for validation, together with the schema document it was compiled from.
 *
 * <p>json-schema-validator 3.x reads and returns Jackson 3 trees, while metacatalog works in
 * Jackson 2 throughout. Documents therefore cross into the validator as JSON text, and {@link
 * #getSchemaNode()} hands back the Jackson 2 tree the schema was compiled from rather than the
 * validator's own copy. Errors are reported as {@code com.networknt.schema.Error#toString()}: the
 * location of the failure, then the message, which is the form API clients receive; {@code
 * getMessage()} no longer carries the location.
 */
public final class JsonSchema {

  private static final ObjectMapper READER = new ObjectMapper();

  private final JsonNode schemaNode;
  private final Schema schema;

  private JsonSchema(JsonNode schemaNode, Schema schema) {
    this.schemaNode = schemaNode;
    this.schema = schema;
  }

  /**
   * Compiles a schema document.
   *
   * @param registry the registry to compile with, normally {@link JsonUtils#newSchemaRegistry()}
   * @param schemaNode the schema document
   * @return the compiled schema
   */
  public static JsonSchema compile(SchemaRegistry registry, JsonNode schemaNode) {
    return new JsonSchema(schemaNode, registry.getSchema(schemaNode.toString(), InputFormat.JSON));
  }

  /**
   * Compiles a schema document given as JSON text.
   *
   * @param registry the registry to compile with, normally {@link JsonUtils#newSchemaRegistry()}
   * @param schemaJson the schema document
   * @return the compiled schema
   * @throws IllegalArgumentException if {@code schemaJson} is not JSON
   */
  public static JsonSchema compile(SchemaRegistry registry, String schemaJson) {
    try {
      return compile(registry, READER.readTree(schemaJson));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Not a JSON document: " + e.getOriginalMessage(), e);
    }
  }

  /**
   * The schema document. Callers that transform it must copy it first.
   *
   * @return the schema document this schema was compiled from
   */
  public JsonNode getSchemaNode() {
    return schemaNode;
  }

  /**
   * Validates a document, treating {@code format} as an annotation, as the 2020-12 default dialect
   * does.
   *
   * @param instance the document to validate
   * @return one message per error, empty when the document is valid
   */
  public List<String> validate(JsonNode instance) {
    return messages(schema.validate(instance.toString(), InputFormat.JSON));
  }

  /**
   * Validates a document with {@code format} asserted, for documents metacatalog generates or
   * checks for itself rather than accepts from a client.
   *
   * @param instance the document to validate
   * @return one message per error, empty when the document is valid
   */
  public List<String> validateWithFormatAssertions(JsonNode instance) {
    return messages(validateWithFormatAssertions(schema, instance.toString()));
  }

  static List<com.networknt.schema.Error> validateWithFormatAssertions(
      Schema schema, String instance) {
    return schema.validate(
        instance,
        InputFormat.JSON,
        context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
  }

  static List<String> messages(List<com.networknt.schema.Error> errors) {
    return errors.stream().map(com.networknt.schema.Error::toString).toList();
  }
}
