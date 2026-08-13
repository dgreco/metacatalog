package it.davidgreco.metacatalog.iceberg.rest;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.UncheckedIOException;
import org.apache.iceberg.rest.RESTSerializers;

/**
 * The Jackson 2 {@link ObjectMapper} for the Iceberg REST wire format, configured exactly like
 * iceberg-core's package-private {@code RESTObjectMapper}: field visibility, kebab-case naming,
 * lenient on unknown properties, plus {@link RESTSerializers#registerAll(ObjectMapper)}.
 *
 * <p>Controllers exchange bodies as {@code String} and (de)serialize through this class, so Spring
 * Boot's own (Jackson 3) message converters never touch iceberg types.
 */
public final class IcebergJson {

  private static final ObjectMapper MAPPER = newMapper();

  private static ObjectMapper newMapper() {
    var mapper = new ObjectMapper();
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    mapper.setPropertyNamingStrategy(new PropertyNamingStrategies.KebabCaseStrategy());
    RESTSerializers.registerAll(mapper);
    return mapper;
  }

  public static <T> T fromJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid request body: " + e.getMessage(), e);
    }
  }

  public static String toJson(Object object) {
    try {
      return MAPPER.writeValueAsString(object);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new UncheckedIOException(new java.io.IOException(e));
    }
  }

  private IcebergJson() {}
}
