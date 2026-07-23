package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.Entity;
import java.util.ArrayList;
import java.util.List;

/**
 * Read model for one entity rendered in the instances list/table.
 *
 * @param id the unique identifier of the entity
 * @param entityType the name of the entity type
 * @param entityTypeVersionId the entity type version id used for validation, or "latest"
 * @param name the entity name extracted from the {@code name} property in values, or the entity id
 */
public record InstanceRowView(
    String id, String entityType, String entityTypeVersionId, String name) {

  /**
   * Builds {@link InstanceRowView} rows for the given entities, extracting a human-readable name
   * from the entity's {@code name} property when present, falling back to the entity id.
   */
  public static List<InstanceRowView> listFrom(List<Entity> entities, ObjectMapper jsonMapper) {
    var views = new ArrayList<InstanceRowView>();
    for (var entity : entities) {
      String name = entity.getId();
      JsonNode values = jsonMapper.valueToTree(entity.getValues());
      if (values != null && values.has("name") && values.get("name").isTextual()) {
        name = values.get("name").asText();
      }
      views.add(
          new InstanceRowView(
              entity.getId(),
              entity.getEntityType().getName(),
              entity.getEntityTypeVersion() != null
                  ? entity.getEntityTypeVersion().getId()
                  : "latest",
              name));
    }
    return views;
  }
}
