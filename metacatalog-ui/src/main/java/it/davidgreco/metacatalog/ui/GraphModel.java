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
   * A graph node.
   *
   * @param id stable identifier, prefixed by kind (e.g. {@code trait:Aggregate}, {@code
   *     type:Order})
   * @param label the display name
   * @param kind {@code trait} or {@code entityType}
   */
  public record Node(String id, String label, String kind) {}

  /**
   * A directed graph edge.
   *
   * @param source the source node id
   * @param target the target node id
   * @param kind the edge category, used for colouring/legend
   * @param label the text shown for the edge
   */
  public record Edge(String source, String target, String kind, String label) {}
}
