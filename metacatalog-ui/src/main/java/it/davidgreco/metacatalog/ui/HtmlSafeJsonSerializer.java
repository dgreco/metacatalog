package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serializes objects to JSON that is safe to embed directly inside an HTML {@code <script>} block.
 *
 * <p>The serializer uses a dedicated {@link ObjectMapper} (a copy of the shared {@code
 * jsonFactory}) configured with a {@link HtmlScriptCharacterEscapes} that forces Unicode escape
 * sequences for {@code <}, {@code >} and {@code &}, and escapes the forward slash so the literal
 * sequence {@code </script>} can never appear in the output. This is the standard defence for
 * embedding JSON inside an HTML {@code <script>} block (see OWASP XSS Prevention Cheat Sheet, Rule
 * 3.1).
 *
 * <p>Extracted from {@link CatalogGraphService} so the HTML-output-encoding concern is reusable and
 * independently testable, and so {@link CatalogGraphService} can focus on graph assembly.
 */
@Component
public class HtmlSafeJsonSerializer {

  private static final Logger log = LoggerFactory.getLogger(HtmlSafeJsonSerializer.class);

  private final ObjectMapper htmlSafeMapper;

  public HtmlSafeJsonSerializer(ObjectMapper jsonMapper) {
    ObjectMapper copy = jsonMapper.copy();
    copy.getFactory().setCharacterEscapes(new HtmlScriptCharacterEscapes());
    this.htmlSafeMapper = copy;
  }

  /**
   * Serializes the given object to HTML-safe JSON, returning {@code "{}"} if serialization fails.
   *
   * @param value the object to serialize
   * @return the HTML-safe JSON string, or {@code "{}"} if serialization fails
   */
  public String write(Object value) {
    return write(value, "{}");
  }

  /**
   * Serializes the given object to HTML-safe JSON, returning the supplied {@code fallback} if
   * serialization fails.
   *
   * <p>The fallback is caller-supplied so this serializer stays decoupled from any specific output
   * shape (e.g. {@code GraphModel}); the caller knows what an empty result should look like for its
   * own model.
   *
   * @param value the object to serialize
   * @param fallback the JSON string to return if serialization fails
   * @return the HTML-safe JSON string, or {@code fallback} if serialization fails
   */
  public String write(Object value, String fallback) {
    try {
      return htmlSafeMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize value to HTML-safe JSON; returning fallback", e);
      return fallback;
    }
  }

  /**
   * {@link CharacterEscapes} that forces JSON Unicode escape sequences for {@code <}, {@code >} and
   * {@code &}, and escapes the forward slash so {@code </script>} can never appear literally in the
   * serialized JSON.
   */
  private static final class HtmlScriptCharacterEscapes extends CharacterEscapes {
    private static final int[] ASCII_ESCAPES;

    static {
      int[] esc = CharacterEscapes.standardAsciiEscapesForJSON();
      esc['<'] = CharacterEscapes.ESCAPE_CUSTOM;
      esc['>'] = CharacterEscapes.ESCAPE_CUSTOM;
      esc['&'] = CharacterEscapes.ESCAPE_CUSTOM;
      esc['/'] = CharacterEscapes.ESCAPE_CUSTOM;
      ASCII_ESCAPES = esc;
    }

    @Override
    public int[] getEscapeCodesForAscii() {
      return ASCII_ESCAPES;
    }

    @Override
    public SerializableString getEscapeSequence(int ch) {
      return switch (ch) {
        case '<' -> new SerializedString("\\u003c");
        case '>' -> new SerializedString("\\u003e");
        case '&' -> new SerializedString("\\u0026");
        case '/' -> new SerializedString("\\/");
        default -> null;
      };
    }
  }
}
