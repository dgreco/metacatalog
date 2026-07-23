package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.model.EntityType;
import java.util.ArrayList;
import java.util.List;

/**
 * Read model for one entity type rendered in the dashboard table.
 *
 * @param name the entity type name
 * @param version the entity type version number
 * @param father the name of the parent entity type this inherits from, or {@code null}
 * @param traits the names of the traits applied to this entity type
 * @param schema the entity type's JSON schema as a string
 */
public record EntityTypeRowView(
    String name, int version, String father, List<String> traits, String schema) {

  public static List<EntityTypeRowView> listFrom(List<EntityType> entityTypes) {
    var views = new ArrayList<EntityTypeRowView>();
    for (var et : entityTypes) {
      views.add(
          new EntityTypeRowView(
              et.getName(),
              et.getVersion().orElse(0),
              et.getInheritsFrom().orElse(null),
              et.getTraits() == null ? List.of() : et.getTraits(),
              et.getSchema()));
    }
    return views;
  }
}
