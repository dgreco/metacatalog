package it.davidgreco.metacatalog.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Form backing bean for creating an {@link it.davidgreco.metacatalog.entity.EntityType} from the
 * UI.
 *
 * <p>The {@code schema} field holds the JSON Schema document assembled by the client-side schema
 * builder before the form is submitted. {@code traits} holds the names of the traits mixed into the
 * entity type.
 */
public class EntityTypeForm {

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
