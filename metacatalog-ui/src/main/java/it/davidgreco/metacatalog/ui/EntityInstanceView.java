package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.Entity;
import java.util.ArrayList;
import java.util.List;

/**
 * Read model for one entity instance rendered in a list or table.
 *
 * @param id the unique identifier of the entity
 * @param entityType the name of the entity type
 * @param entityTypeVersionId the entity type version used for validation
 * @param name the entity name extracted from the {@code name} property in values, or "unnamed"
 */
public record EntityInstanceView(
    String id, String entityType, String entityTypeVersionId, String name) {

  /**
   * Builds {@link EntityInstanceView} rows for the given entities, extracting a human-readable name
   * from the entity's {@code name} property when present.
   */
  public static List<EntityInstanceView> listFrom(List<Entity> entities, ObjectMapper jsonMapper) {
    var views = new ArrayList<EntityInstanceView>();
    for (var entity : entities) {
      String name = "unnamed";
      JsonNode values = jsonMapper.valueToTree(entity.getValues());
      if (values != null && values.has("name") && values.get("name").isTextual()) {
        name = values.get("name").asText();
      }
      views.add(
          new EntityInstanceView(
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
