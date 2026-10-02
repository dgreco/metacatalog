package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.service.MappingValueEvaluator.generateMappedValues;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import it.davidgreco.metacatalog.common.JsonSchema;
import it.davidgreco.metacatalog.common.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Security tests for the SpEL evaluation in {@link MappingValueEvaluator}.
 *
 * <p>Mapping expressions are user-supplied strings evaluated with the Spring Expression Language.
 * Because they run server-side, they are a potential Remote Code Execution (RCE) vector if the
 * evaluation context is too permissive. The evaluator mitigates this in two layers:
 *
 * <ol>
 *   <li>A read-only {@code SimpleEvaluationContext} is used instead of {@code
 *       StandardEvaluationContext}. This blocks type references ({@code T(java.lang.Runtime)}) and
 *       constructors ({@code new ProcessBuilder(...)}), which are the classic SpEL RCE primitives.
 *   <li>A {@code WhitelistedMethodResolver} only resolves instance methods whose receiver is a
 *       {@link JsonNode} or {@code WrappedJsonNode}. This closes the residual vector where an
 *       attacker reaches {@code #source.getClass()} (inherited from {@link Object}) and chains into
 *       {@code Class.forName(...)} to load arbitrary classes.
 * </ol>
 *
 * <p>These tests exercise both layers with canonical RCE payloads. Each test feeds a malicious
 * mapping expression and asserts that evaluation fails rather than executing the payload. A
 * permissive JSON Schema is used so that any expression that somehow evaluates successfully would
 * also pass validation, ensuring the failure is attributable to the SpEL hardening and not to a
 * schema rejection.
 */
class MappingValueEvaluatorTests {

  private static ObjectMapper createJsonMapper() {
    var mapper = new ObjectMapper();
    mapper.registerModule(new Jdk8Module());
    return mapper;
  }

  private static final ObjectMapper jsonMapper = createJsonMapper();

  private static JsonNode source(String json) throws Exception {
    return jsonMapper.readTree(json);
  }

  private static final JsonNode SOURCE_VALUES;

  static {
    try {
      SOURCE_VALUES = source("{\"a\": 1}");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static final Map<String, JsonNode> NO_EXTERNAL = Map.of();

  /**
   * A permissive target schema so that any successfully evaluated expression passes validation. The
   * RCE vectors are expected to fail at evaluation time, not at validation time.
   */
  private static final JsonSchema PERMISSIVE_SCHEMA =
      JsonSchema.compile(JsonUtils.newSchemaRegistry(), "{\"type\": \"object\"}");

  @Test
  void typeReferenceRceIsRejected() throws Exception {
    var mapping = source("{\"b\": \"T(java.lang.Runtime).getRuntime().exec('id')\"}");
    assertThrows(
        Exception.class,
        () -> generateMappedValues(SOURCE_VALUES, NO_EXTERNAL, mapping, PERMISSIVE_SCHEMA));
  }

  @Test
  void constructorRceIsRejected() throws Exception {
    var mapping = source("{\"b\": \"new ProcessBuilder(new String[]{'id'}).start()\"}");
    assertThrows(
        Exception.class,
        () -> generateMappedValues(SOURCE_VALUES, NO_EXTERNAL, mapping, PERMISSIVE_SCHEMA));
  }

  @Test
  void getClassForNameRceIsRejected() throws Exception {
    // getClass() is inherited from Object and is reachable on any SpEL root variable. The
    // WhitelistedMethodResolver refuses to resolve methods whose declaring class is Object (or
    // anything other than JsonNode / WrappedJsonNode), so the chain to Class.forName(...) is
    // broken at the first hop.
    var mapping =
        source(
            "{\"b\": \"#source.getClass().forName('java.lang.Runtime').getMethod('exec',"
                + " T(java.lang.String)).invoke(null, 'id')\"}");
    assertThrows(
        Exception.class,
        () -> generateMappedValues(SOURCE_VALUES, NO_EXTERNAL, mapping, PERMISSIVE_SCHEMA));
  }

  @Test
  void getClassForNameViaGetClassIsRejected() throws Exception {
    // Even if the attacker reaches a Class object, forName() is a method on Class (not JsonNode),
    // so the WhitelistedMethodResolver refuses to resolve it.
    var mapping = source("{\"b\": \"#source.node.getClass().forName('java.lang.Runtime')\"}");
    assertThrows(
        Exception.class,
        () -> generateMappedValues(SOURCE_VALUES, NO_EXTERNAL, mapping, PERMISSIVE_SCHEMA));
  }
}
