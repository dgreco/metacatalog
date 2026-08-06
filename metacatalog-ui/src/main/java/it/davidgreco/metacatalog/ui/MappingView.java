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
 * @param sourceVersion the version of the source type the mapping was defined against, or null
 * @param targetVersion the version of the target type the mapping was defined against, or null
 * @param mappingValues the mapping expressions as a JSON string
 * @param entityPathReferences the entity path references as a JSON string
 */
public record MappingView(
    String id,
    String source,
    String target,
    Integer sourceVersion,
    Integer targetVersion,
    String mappingValues,
    String entityPathReferences) {

  public static List<MappingView> listFrom(List<Mapping> mappings) {
    var views = new ArrayList<MappingView>();
    for (var mapping : mappings) {
      views.add(
          new MappingView(
              mapping.getId().orElse(null),
              mapping.getSourceEntityType(),
              mapping.getTargetEntityType(),
              mapping.getSourceEntityTypeVersion().orElse(null),
              mapping.getTargetEntityTypeVersion().orElse(null),
              mapping.getMappingValues(),
              mapping.getEntityPathReferences()));
    }
    return views;
  }
}
