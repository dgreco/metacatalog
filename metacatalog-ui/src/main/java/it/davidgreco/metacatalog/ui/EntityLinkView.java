package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.model.EntityRelationship;
import java.util.ArrayList;
import java.util.List;

/**
 * Read model for one directed entity relationship rendered on the entity-link form.
 *
 * <p>Because relationships are stored bidirectionally, the form lists only the canonical (primary)
 * direction; the {@code relationType} is always one of the primary relation types ({@code
 * DEPENDS_ON}, {@code HAS_PART}, {@code MAPPED_TO}).
 *
 * @param sourceId the id of the source entity
 * @param sourceName a human-readable name for the source entity
 * @param relationType the primary relation type from source to target
 * @param targetId the id of the target entity
 * @param targetName a human-readable name for the target entity
 * @param role the role of the current entity in this relationship ("source" or "target")
 */
public record EntityLinkView(
    String sourceId,
    String sourceName,
    String relationType,
    String targetId,
    String targetName,
    String role) {

  /**
   * Builds {@link EntityLinkView} rows from the {@link EntityRelationship} DTOs returned by {@code
   * GET /entity/relationships}, keeping only the primary-direction relationships so each
   * bidirectional link appears exactly once.
   *
   * @param relationships the relationships as returned by the REST API
   * @param instanceRows the instance rows for name resolution (id -> name)
   * @param entityId the current entity id — used to set {@code role} and {@code direction}
   */
  public static List<EntityLinkView> listFrom(
      List<EntityRelationship> relationships, List<InstanceRowView> instanceRows, String entityId) {
    var views = new ArrayList<EntityLinkView>();
    for (var rel : relationships) {
      var rt = rel.getRelationType().orElse(null);
      if (rt != null && CatalogGraphService.PRIMARY_RELATION_TYPE_NAMES.contains(rt)) {
        var srcId = rel.getSourceEntityId().orElse(null);
        var tgtId = rel.getTargetEntityId().orElse(null);
        if (srcId == null || tgtId == null) continue;
        var isSource = srcId.equals(entityId);
        var isTarget = tgtId.equals(entityId);
        if (!isSource && !isTarget) continue;
        var role = isSource ? "source" : "target";
        views.add(
            new EntityLinkView(
                srcId, nameOf(instanceRows, srcId), rt, tgtId, nameOf(instanceRows, tgtId), role));
      }
    }
    return views;
  }

  private static String nameOf(List<InstanceRowView> rows, String id) {
    for (var row : rows) {
      if (row.id().equals(id)) {
        return row.name();
      }
    }
    return id;
  }
}
