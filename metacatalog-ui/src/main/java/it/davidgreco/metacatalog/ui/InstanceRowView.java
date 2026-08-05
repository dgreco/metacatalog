package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.model.Entity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Read model for one entity rendered in the instances list/table.
 *
 * @param id the unique identifier of the entity
 * @param entityType the name of the entity type
 * @param entityTypeVersionId the id of the entity type version the entity is pinned to
 * @param name the entity name extracted from the {@code name} property in values, or the entity id
 * @param aggregateRoot whether the entity is the root of an aggregate, and can therefore be deleted
 *     as a whole
 */
public record InstanceRowView(
    String id, String entityType, String entityTypeVersionId, String name, boolean aggregateRoot) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Builds {@link InstanceRowView} rows for the given entities, extracting a human-readable name
   * from the entity's {@code name} property when present, falling back to the entity id.
   *
   * <p>An entity is an aggregate root when its type is one: aggregate root types are precisely the
   * types that are never contained in another, so no instance of one can be part of a larger
   * aggregate. Pass an empty set where the distinction does not matter.
   *
   * @param entities the entities to render
   * @param aggregateRootTypeNames the names of the aggregate root types
   */
  public static List<InstanceRowView> listFrom(
      List<Entity> entities, Set<String> aggregateRootTypeNames) {
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
          new InstanceRowView(
              id,
              entity.getEntityType(),
              entity.getEntityTypeVersionId(),
              name,
              aggregateRootTypeNames.contains(entity.getEntityType())));
    }
    return views;
  }
}
