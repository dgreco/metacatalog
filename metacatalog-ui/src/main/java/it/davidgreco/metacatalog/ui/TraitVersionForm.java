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

  /** The names of the traits mixed in, in the order they were selected. */
  private java.util.List<String> traits = new java.util.ArrayList<>();

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

  public java.util.List<String> getTraits() {
    return traits;
  }

  public void setTraits(java.util.List<String> traits) {
    this.traits = traits;
  }

  public String getSchema() {
    return schema;
  }

  public void setSchema(String schema) {
    this.schema = schema;
  }
}
