package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.model.Trait;
import java.util.ArrayList;
import java.util.List;

/**
 * Read model for one trait rendered in the dashboard table.
 *
 * @param name the trait name
 * @param version the trait version number
 * @param father the name of the parent trait this inherits from, or {@code null}
 * @param schema the trait's JSON schema as a string, or {@code null}
 */
public record TraitRowView(String name, int version, String father, String schema) {

  public static List<TraitRowView> listFrom(List<Trait> traits) {
    var views = new ArrayList<TraitRowView>();
    for (var t : traits) {
      views.add(
          new TraitRowView(
              t.getName(),
              t.getVersion().orElse(0),
              t.getInheritsFrom().orElse(null),
              t.getSchema().orElse(null)));
    }
    return views;
  }
}
