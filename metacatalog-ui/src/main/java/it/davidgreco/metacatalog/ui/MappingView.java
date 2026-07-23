package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.model.Mapping;
import java.util.ArrayList;
import java.util.List;

/**
 * Read model for one mapping rendered on the dashboard.
 *
 * @param id the unique identifier of the mapping relationship
 * @param source the name of the source entity type
 * @param target the name of the target entity type
 * @param mappingValues the mapping expressions as a JSON string
 * @param entityPathReferences the entity path references as a JSON string
 */
public record MappingView(
    String id, String source, String target, String mappingValues, String entityPathReferences) {

  public static List<MappingView> listFrom(List<Mapping> mappings) {
    var views = new ArrayList<MappingView>();
    for (var mapping : mappings) {
      views.add(
          new MappingView(
              mapping.getId().orElse(null),
              mapping.getSourceEntityType(),
              mapping.getTargetEntityType(),
              mapping.getMappingValues(),
              mapping.getEntityPathReferences()));
    }
    return views;
  }
}
