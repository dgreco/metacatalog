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
 * @param provisionable whether the entity can be provisioned and unprovisioned
 * @param authorizable whether the entity can be authorized and rejected
 */
public record InstanceRowView(
    String id,
    String entityType,
    String entityTypeVersionId,
    String name,
    boolean aggregateRoot,
    boolean provisionable,
    boolean authorizable) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Builds {@link InstanceRowView} rows for the given entities, extracting a human-readable name
   * from the entity's {@code name} property when present, falling back to the entity id.
   *
   * <p>An entity is an aggregate root when its type is one: aggregate root types are precisely the
   * types that are never contained in another, so no instance of one can be part of a larger
   * aggregate. It is provisionable when its type carries the {@code Provisionable} trait, and
   * authorizable when it carries {@code Authorizable} — questions only the API can answer, since
   * deciding either needs the type and trait inheritance chains. The two are independent: a type
   * may offer one, both or neither, so they are two separate sets rather than one flag. Pass empty
   * sets where the distinctions do not matter.
   *
   * @param entities the entities to render
   * @param aggregateRootTypeNames the names of the aggregate root types
   * @param provisionableTypeNames the names of the provisionable types
   * @param authorizableTypeNames the names of the authorizable types
   */
  public static List<InstanceRowView> listFrom(
      List<Entity> entities,
      Set<String> aggregateRootTypeNames,
      Set<String> provisionableTypeNames,
      Set<String> authorizableTypeNames) {
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
              aggregateRootTypeNames.contains(entity.getEntityType()),
              provisionableTypeNames.contains(entity.getEntityType()),
              authorizableTypeNames.contains(entity.getEntityType())));
    }
    return views;
  }
}
