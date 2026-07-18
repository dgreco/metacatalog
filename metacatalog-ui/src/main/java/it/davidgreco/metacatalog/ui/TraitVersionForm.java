package it.davidgreco.metacatalog.ui;

/**
 * Form backing bean for creating a new version of a {@link it.davidgreco.metacatalog.entity.Trait}
 * from the UI.
 *
 * <p>The {@code schema} field is pre-populated with the current live trait's schema so the user can
 * edit it rather than start from scratch. The {@code name} field is read-only and carried along for
 * display; the actual identity of the trait being versioned comes from the URL path.
 */
public class TraitVersionForm {

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
