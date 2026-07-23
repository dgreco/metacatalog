package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.openapi.model.TraitRelationship;
import java.util.ArrayList;
import java.util.List;

/**
 * Read model for one directed trait relationship rendered on the dashboard.
 *
 * <p>Because relationships are stored bidirectionally, the dashboard lists only the canonical
 * (primary) direction; the {@code relationType} therefore is always one of the primary relation
 * types ({@code DEPENDS_ON}, {@code HAS_PART}, {@code MAPPED_TO}).
 *
 * @param source the name of the source trait
 * @param relationType the primary relation type from source to target
 * @param target the name of the target trait
 */
public record TraitLinkView(String source, RelationType relationType, String target) {

  /**
   * Builds {@link TraitLinkView} rows from the REST API {@link TraitRelationship} DTOs, keeping
   * only the primary-direction relationships so each bidirectional link appears exactly once.
   */
  public static List<TraitLinkView> listFrom(List<TraitRelationship> relationships) {
    var views = new ArrayList<TraitLinkView>();
    for (var rel : relationships) {
      var rt = RelationType.parse(rel.getRelationType().orElse(null));
      if (CatalogGraphService.PRIMARY_RELATION_TYPES.contains(rt)) {
        views.add(
            new TraitLinkView(
                rel.getSourceTrait().orElse(null), rt, rel.getTargetTrait().orElse(null)));
      }
    }
    return views;
  }
}
