package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.model.Entity;
import java.util.ArrayList;
import java.util.List;

/**
 * Read model for one entity rendered in the instances list/table.
 *
 * @param id the unique identifier of the entity
 * @param entityType the name of the entity type
 * @param entityTypeVersionId the id of the entity type version the entity is pinned to
 * @param name the entity name extracted from the {@code name} property in values, or the entity id
 */
public record InstanceRowView(
    String id, String entityType, String entityTypeVersionId, String name) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Builds {@link InstanceRowView} rows for the given entities, extracting a human-readable name
   * from the entity's {@code name} property when present, falling back to the entity id.
   */
  public static List<InstanceRowView> listFrom(List<Entity> entities) {
    var views = new ArrayList<InstanceRowView>();
    for (var entity : entities) {
      String id = entity.getId().orElse(null);
      String name = id;
      if (entity.getValues() != null) {
        try {
          JsonNode values = MAPPER.readTree(entity.getValues());
          if (values != null && values.has("name") && values.get("name").isTextual()) {
            name = values.get("name").asText();
          }
        } catch (Exception ignored) {
        }
      }
      views.add(
          new InstanceRowView(id, entity.getEntityType(), entity.getEntityTypeVersionId(), name));
    }
    return views;
  }
}
