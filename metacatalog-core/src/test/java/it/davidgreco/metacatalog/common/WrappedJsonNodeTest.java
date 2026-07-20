package it.davidgreco.metacatalog.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WrappedJsonNode} typed JSON Path extraction. */
class WrappedJsonNodeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static WrappedJsonNode wrap(String json) throws Exception {
    return new WrappedJsonNode(MAPPER.readTree(json));
  }

  @Test
  void extractsStringValue() throws Exception {
    var node = wrap("{\"name\":\"widget\"}");
    assertEquals("widget", node.getValue(String.class, "$.name"));
  }

  @Test
  void extractsIntegerAndLong() throws Exception {
    var node = wrap("{\"count\":42}");
    assertEquals(42, node.getValue(Integer.class, "$.count"));
    assertEquals(42L, node.getValue(Long.class, "$.count"));
  }

  @Test
  void extractsFloatWithoutClassCastException() throws Exception {
    // Regression: the Float branch previously boxed a double and threw ClassCastException.
    var node = wrap("{\"ratio\":1.5}");
    assertEquals(1.5f, node.getValue(Float.class, "$.ratio"));
    assertEquals(1.5d, node.getValue(Double.class, "$.ratio"));
  }

  @Test
  void extractsBoolean() throws Exception {
    var node = wrap("{\"enabled\":true}");
    assertEquals(Boolean.TRUE, node.getValue(Boolean.class, "$.enabled"));
  }

  @Test
  void throwsDescriptiveErrorOnTypeMismatch() throws Exception {
    var node = wrap("{\"name\":\"widget\"}");
    // Asking for a number where the value is a string must fail loudly, not with a raw CCE.
    assertThrows(IllegalArgumentException.class, () -> node.getValue(Integer.class, "$.name"));
  }

  @Test
  void throwsOnMissingValue() throws Exception {
    var node = wrap("{\"name\":\"widget\"}");
    // A missing definite path must not silently return null; JsonPath / the type guard both raise
    // a RuntimeException rather than a blind ClassCastException/NPE.
    assertThrows(RuntimeException.class, () -> node.getValue(String.class, "$.missing"));
  }
}
