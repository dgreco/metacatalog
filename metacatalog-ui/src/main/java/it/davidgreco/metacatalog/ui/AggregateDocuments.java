package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;

/**
 * Conversions between the two forms an aggregate document takes in the UI: the JSON a page builds
 * and submits, and the YAML the aggregate endpoints consume and serve.
 *
 * <p>Shared by the generic aggregate pages and the asset pages, which author and show the same
 * documents: an asset is an aggregate, so its builder, its preview and its download are the
 * aggregate's.
 */
final class AggregateDocuments {

  private final ObjectMapper jsonMapper;
  private final ObjectMapper yamlMapper;

  AggregateDocuments(ObjectMapper jsonMapper, ObjectMapper yamlMapper) {
    this.jsonMapper = jsonMapper;
    this.yamlMapper = yamlMapper;
  }

  /**
   * An aggregate read back from the API: the YAML it was served as, and the same document as a
   * tree.
   *
   * @param yaml the document as YAML, without the leading document marker
   * @param tree the document parsed
   */
  record Read(String yaml, JsonNode tree) {

    /** The document as pretty-printed JSON. */
    String json(ObjectMapper jsonMapper) throws IOException {
      return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    }
  }

  /**
   * Converts the JSON document a page submits into the YAML the aggregate endpoints consume.
   * Parsing through Jackson also rejects a malformed document up-front, so the user gets a form
   * error rather than an opaque failure from the loader.
   *
   * @param document the aggregate document as JSON
   * @return the same document as YAML
   * @throws IllegalArgumentException if the document is empty
   */
  byte[] toYaml(String document) throws IOException {
    if (document == null || document.isBlank()) {
      throw new IllegalArgumentException("The aggregate document is empty.");
    }
    var tree = jsonMapper.readTree(document);
    var bos = new ByteArrayOutputStream();
    yamlMapper.writeValue(bos, tree);
    return bos.toByteArray();
  }

  /** The YAML a page shows for the JSON document it holds: {@link #toYaml}, as displayable text. */
  String preview(String document) throws IOException {
    return withoutMarker(new String(toYaml(document), StandardCharsets.UTF_8));
  }

  /**
   * Reads the YAML file {@code GET /aggregate/{id}/yaml} serves.
   *
   * @param resource the body the API returned
   */
  Read read(Resource resource) throws IOException {
    byte[] bytes;
    try (var in = resource.getInputStream()) {
      bytes = in.readAllBytes();
    }
    return new Read(
        withoutMarker(new String(bytes, StandardCharsets.UTF_8)), yamlMapper.readTree(bytes));
  }

  /** Drops the {@code ---} a YAML writer starts a document with, which a page has no use for. */
  static String withoutMarker(String yaml) {
    return yaml.startsWith("---") ? yaml.substring(3).stripLeading() : yaml;
  }
}
