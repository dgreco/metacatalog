package it.davidgreco.metacatalog.ui;

/**
 * Form backing bean for creating a relationship between two entities from the UI.
 *
 * <p>The relationship is directed from {@code sourceEntityId} to {@code targetEntityId} with the
 * given {@code relationshipType} (a {@link it.davidgreco.metacatalog.entity.RelationType} name);
 * the inverse relationship is created automatically by the service.
 */
public class EntityLinkForm {

  private String sourceEntityId;
  private String relationshipType;
  private String targetEntityId;

  public String getSourceEntityId() {
    return sourceEntityId;
  }

  public void setSourceEntityId(String sourceEntityId) {
    this.sourceEntityId = sourceEntityId;
  }

  public String getRelationshipType() {
    return relationshipType;
  }

  public void setRelationshipType(String relationshipType) {
    this.relationshipType = relationshipType;
  }

  public String getTargetEntityId() {
    return targetEntityId;
  }

  public void setTargetEntityId(String targetEntityId) {
    this.targetEntityId = targetEntityId;
  }
}
