package it.davidgreco.metacatalog.ui;

/**
 * Form backing bean for creating or updating an {@link it.davidgreco.metacatalog.entity.Entity}
 * from the UI.
 *
 * <p>The {@code values} field holds the entity attributes as a JSON string validated against the
 * selected entity type's derived schema.
 */
public class EntityInstanceForm {

  private String entityType;
  private String values;

  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String entityType) {
    this.entityType = entityType;
  }

  public String getValues() {
    return values;
  }

  public void setValues(String values) {
    this.values = values;
  }
}
