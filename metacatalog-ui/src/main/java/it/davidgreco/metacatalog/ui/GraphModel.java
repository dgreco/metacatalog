package it.davidgreco.metacatalog.ui;

import java.util.List;

/**
 * Serializable model of the whole catalog rendered as a graph on the {@code /ui/graph} page.
 *
 * <p>Nodes are traits and entity types; edges capture inheritance ({@code extends}), trait
 * membership ({@code has-trait}), trait relationships (the primary relation types) and entity-type
 * mappings ({@code MAPPED_TO}). The model is serialized to JSON and embedded in the page for a
 * client-side force-directed renderer.
 */
public record GraphModel(List<Node> nodes, List<Edge> edges) {

  /**
   * A graph node, carrying the full detail shown in its hover popup.
   *
   * @param id stable identifier, prefixed by kind (e.g. {@code trait:Aggregate}, {@code
   *     type:Order})
   * @param label the display name
   * @param kind {@code trait} or {@code entityType}
   * @param father the parent type/trait name, or {@code null} if none
   * @param traits the associated trait names (entity types only), or {@code null}
   * @param schema the effective JSON schema as a pretty-printed string, or {@code null}
   */
  public record Node(
      String id, String label, String kind, String father, List<String> traits, String schema) {}

  /**
   * A directed graph edge. Mapping edges additionally carry the mapping detail shown in their hover
   * popup; those fields are {@code null} for every other edge kind.
   *
   * @param source the source node id
   * @param target the target node id
   * @param kind the edge category, used for colouring/legend
   * @param label the text shown for the edge
   * @param mappingValues the mapping expressions as a pretty-printed JSON string, or {@code null}
   * @param entityPathReferences the entity path references as a pretty-printed JSON string, or
   *     {@code null}
   */
  public record Edge(
      String source,
      String target,
      String kind,
      String label,
      String mappingValues,
      String entityPathReferences) {}
}
