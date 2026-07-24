package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
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
 */
public record EntityLinkView(
    String sourceId,
    String sourceName,
    RelationType relationType,
    String targetId,
    String targetName) {

  /**
   * Builds {@link EntityLinkView} rows from the domain {@link EntityRelationship}s, keeping only
   * the primary-direction relationships so each bidirectional link appears exactly once.
   *
   * @param relationships the raw relationships from {@link
   *     it.davidgreco.metacatalog.service.EntityService}
   * @param instanceRows the instance rows for name resolution (id -> name)
   */
  public static List<EntityLinkView> listFrom(
      List<EntityRelationship> relationships, List<InstanceRowView> instanceRows) {
    var views = new ArrayList<EntityLinkView>();
    for (var rel : relationships) {
      var rt = rel.getRelationType();
      if (CatalogGraphService.PRIMARY_RELATION_TYPES.contains(rt)) {
        var srcId = rel.getSource().getId();
        var tgtId = rel.getTarget().getId();
        views.add(
            new EntityLinkView(
                srcId, nameOf(instanceRows, srcId), rt, tgtId, nameOf(instanceRows, tgtId)));
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
