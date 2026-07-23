package it.davidgreco.metacatalog.ui;

/**
 * Form-backing bean for the instance manager create/edit form.
 *
 * <p>The {@code entityType} is the entity type name and {@code values} is the raw JSON string built
 * by the client-side schema-driven editor (see {@code instance-values-form.js}).
 */
public class InstanceForm {

  private String entityType;
  private String values;

  /**
   * @return the entity type name
   */
  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String entityType) {
    this.entityType = entityType;
  }

  /**
   * @return a JSON string containing the instance values
   */
  public String getValues() {
    return values;
  }

  public void setValues(String values) {
    this.values = values;
  }
}
