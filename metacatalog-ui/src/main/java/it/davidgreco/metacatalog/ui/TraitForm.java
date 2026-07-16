package it.davidgreco.metacatalog.ui;

/**
 * Form backing bean for creating a {@link it.davidgreco.metacatalog.entity.Trait} from the UI.
 *
 * <p>The {@code schema} field holds the JSON Schema document assembled by the client-side schema
 * builder before the form is submitted.
 */
public class TraitForm {

  private String name;
  private String father;
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

  public String getSchema() {
    return schema;
  }

  public void setSchema(String schema) {
    this.schema = schema;
  }
}
