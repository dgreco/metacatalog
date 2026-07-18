package it.davidgreco.metacatalog.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Form backing bean for creating a new version of an {@link
 * it.davidgreco.metacatalog.entity.EntityType} from the UI.
 *
 * <p>The {@code schema}, {@code traits}, and {@code father} fields are pre-populated with the
 * current live entity type's values so the user can edit them rather than start from scratch. The
 * {@code name} field is read-only and carried along for display; the actual identity of the entity
 * type being versioned comes from the URL path.
 */
public class EntityTypeVersionForm {

  private String name;
  private String father;
  private List<String> traits = new ArrayList<>();
  private String schema;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getFather() {
    return father;
  }

  public void setFather(String father) {
    this.father = father;
  }

  public List<String> getTraits() {
    return traits;
  }

  public void setTraits(List<String> traits) {
    this.traits = traits;
  }

  public String getSchema() {
    return schema;
  }

  public void setSchema(String schema) {
    this.schema = schema;
  }
}
