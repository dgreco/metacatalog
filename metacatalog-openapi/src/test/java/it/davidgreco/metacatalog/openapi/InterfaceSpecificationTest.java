package it.davidgreco.metacatalog.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Static checks on {@code interface-specification.yaml} that guard against spec constructs which
 * compile and serve fine but break the generated OpenAPI document at runtime.
 *
 * <p>These run without a Spring context: the failures they catch surface only when springdoc
 * serializes the document for {@code GET /api-docs}, which no other test exercises (the Swagger UI
 * is the first thing to notice, by which point it is already broken in a deployed build).
 */
class InterfaceSpecificationTest {

  private static final String SPEC = "/static/api/interface-specification.yaml";

  private static JsonNode spec() throws IOException {
    try (InputStream in = InterfaceSpecificationTest.class.getResourceAsStream(SPEC)) {
      Assertions.assertNotNull(in, "interface-specification.yaml not found on the test classpath");
      return new ObjectMapper(new YAMLFactory()).readTree(in);
    }
  }

  /**
   * An optional query parameter must not declare a schema {@code default}.
   *
   * <p>The generator maps an optional parameter to {@code Optional<String>} and carries the default
   * across as {@code @RequestParam(defaultValue = ...)}. Springdoc then holds that default as an
   * {@code Optional} in the document model, and its serializer cannot write one — so {@code GET
   * /api-docs} fails with 400 ("Java 8 optional type not supported") and the Swagger UI reports
   * "Failed to load API definition", while every actual endpoint keeps working.
   *
   * <p>Express the fallback in the implementation instead, and describe it in the parameter's
   * {@code description}.
   */
  @Test
  void optionalQueryParametersDeclareNoSchemaDefault() throws IOException {
    var offenders = new ArrayList<String>();

    var paths = spec().get("paths");
    paths
        .properties()
        .forEach(
            pathEntry ->
                pathEntry
                    .getValue()
                    .properties()
                    .forEach(
                        opEntry -> {
                          var parameters = opEntry.getValue().get("parameters");
                          if (parameters == null || !parameters.isArray()) return;
                          for (var parameter : parameters) {
                            if (isOptionalWithDefault(parameter)) {
                              offenders.add(
                                  opEntry.getKey().toUpperCase(java.util.Locale.ROOT)
                                      + " "
                                      + pathEntry.getKey()
                                      + " -> parameter '"
                                      + parameter.path("name").asText()
                                      + "'");
                            }
                          }
                        }));

    Assertions.assertEquals(
        List.of(),
        offenders,
        "Optional parameters must not declare a schema default: springdoc cannot serialize the"
            + " resulting Optional and GET /api-docs then fails with 400");
  }

  private static boolean isOptionalWithDefault(JsonNode parameter) {
    var required = parameter.path("required").asBoolean(false);
    var hasDefault = parameter.path("schema").has("default");
    return !required && hasDefault;
  }

  /** Every operation needs a unique {@code operationId}: the generator derives method names it. */
  @Test
  void operationIdsArePresentAndUnique() throws IOException {
    var seen = new java.util.HashSet<String>();
    var duplicates = new ArrayList<String>();
    var missing = new ArrayList<String>();

    var paths = spec().get("paths");
    paths
        .properties()
        .forEach(
            pathEntry ->
                pathEntry
                    .getValue()
                    .properties()
                    .forEach(
                        opEntry -> {
                          var operationId = opEntry.getValue().get("operationId");
                          if (operationId == null || operationId.asText().isBlank()) {
                            missing.add(opEntry.getKey() + " " + pathEntry.getKey());
                          } else if (!seen.add(operationId.asText())) {
                            duplicates.add(operationId.asText());
                          }
                        }));

    Assertions.assertEquals(List.of(), missing, "operations without an operationId");
    Assertions.assertEquals(List.of(), duplicates, "duplicate operationIds");
  }
}
