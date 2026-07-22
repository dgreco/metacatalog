package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.service.MappingService;
import java.util.ArrayList;
import java.util.List;

/**
 * Read model for one mapping entity type relationship rendered on the dashboard.
 *
 * <p>The {@code mappingValues} and {@code entityPathReferences} are carried as pretty-printed JSON
 * strings so the template can render them verbatim in a {@code <pre>} block, mirroring how schemas
 * are shown for traits and entity types.
 *
 * @param id the unique identifier of the mapping relationship
 * @param source the name of the source entity type
 * @param target the name of the target entity type
 * @param mappingValues the mapping expressions as a pretty-printed JSON string
 * @param entityPathReferences the entity path references as a pretty-printed JSON string
 */
public record MappingView(
    String id, String source, String target, String mappingValues, String entityPathReferences) {

  /**
   * Builds {@link MappingView} rows for every mapping returned by {@code mappingService}, pretty-
   * printing the JSON fields so the template can render them verbatim.
   */
  public static List<MappingView> listFrom(MappingService mappingService, ObjectMapper jsonMapper) {
    var views = new ArrayList<MappingView>();
    for (var mapping : mappingService.list()) {
      views.add(
          new MappingView(
              mapping.getId(),
              mapping.getSource().getName(),
              mapping.getTarget().getName(),
              mapping.getMappingValues().toPrettyString(),
              jsonMapper.valueToTree(mapping.getEntityPathReferences()).toPrettyString()));
    }
    return views;
  }
}
