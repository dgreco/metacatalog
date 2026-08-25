package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.openapi.model.Entity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Read model for one entity rendered in the instances list/table.
 *
 * @param id the unique identifier of the entity
 * @param entityType the name of the entity type
 * @param entityTypeVersionId the id of the entity type version the entity is pinned to
 * @param name the entity name extracted from the {@code name} property in values, or the entity id
 * @param actions the aggregate actions this row offers, in declaration order
 */
public record InstanceRowView(
    String id,
    String entityType,
    String entityTypeVersionId,
    String name,
    List<AggregateAction> actions) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Rows for a page that offers no aggregate actions — the entity-link picker and the aggregate
   * form, which need the id and the display name and nothing else.
   *
   * @param entities the entities to render
   */
  public static List<InstanceRowView> listFrom(List<Entity> entities) {
    return listFrom(entities, TypeCapabilities.NONE);
  }

  /**
   * Builds {@link InstanceRowView} rows for the given entities, extracting a human-readable name
   * from the entity's {@code name} property when present, falling back to the entity id, and
   * attaching the {@link AggregateAction}s the entity's type qualifies for.
   *
   * <p>Which capabilities a type has is a question only the API can answer, since deciding any of
   * them needs the type and trait inheritance chains. They are three independent answers — a type
   * may be provisionable, authorizable, both or neither, and being an aggregate root implies
   * neither — so the row's actions are the union of what each capability grants, not a single "has
   * a lifecycle" flag.
   *
   * @param entities the entities to render
   * @param capabilities which type names hold which capabilities
   */
  public static List<InstanceRowView> listFrom(
      List<Entity> entities, TypeCapabilities capabilities) {
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
              capabilities.actionsFor(entity.getEntityType())));
    }
    return views;
  }

  /**
   * The type names holding each capability, as the API reports them.
   *
   * @param aggregateRoot names of the types that root an aggregate
   * @param provisionable names of the types that can be provisioned
   * @param authorizable names of the types that can be authorized
   */
  public record TypeCapabilities(
      Set<String> aggregateRoot, Set<String> provisionable, Set<String> authorizable) {

    /** No type has any capability: for pages that offer no aggregate actions at all. */
    public static final TypeCapabilities NONE = new TypeCapabilities(Set.of(), Set.of(), Set.of());

    /** The actions a row of this type offers, in {@link AggregateAction} declaration order. */
    List<AggregateAction> actionsFor(String entityTypeName) {
      return EnumSet.allOf(AggregateAction.class).stream()
          .filter(action -> holds(action.getCapability()).contains(entityTypeName))
          .toList();
    }

    private Set<String> holds(AggregateAction.Capability capability) {
      return switch (capability) {
        case AGGREGATE_ROOT -> aggregateRoot;
        case PROVISIONABLE -> provisionable;
        case AUTHORIZABLE -> authorizable;
      };
    }
  }
}
