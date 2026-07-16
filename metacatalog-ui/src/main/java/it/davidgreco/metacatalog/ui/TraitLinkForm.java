package it.davidgreco.metacatalog.ui;

/**
 * Form backing bean for creating a relationship between two {@link
 * it.davidgreco.metacatalog.entity.Trait}s from the UI.
 *
 * <p>The relationship is directed from {@code sourceTrait} to {@code targetTrait} with the given
 * {@code relationshipType} (a {@link it.davidgreco.metacatalog.entity.RelationType} name); the
 * inverse relationship is created automatically by the service.
 */
public class TraitLinkForm {

  private String sourceTrait;
  private String relationshipType;
  private String targetTrait;

  public String getSourceTrait() {
    return sourceTrait;
  }

  public void setSourceTrait(String sourceTrait) {
    this.sourceTrait = sourceTrait;
  }

  public String getRelationshipType() {
    return relationshipType;
  }

  public void setRelationshipType(String relationshipType) {
    this.relationshipType = relationshipType;
  }

  public String getTargetTrait() {
    return targetTrait;
  }

  public void setTargetTrait(String targetTrait) {
    this.targetTrait = targetTrait;
  }
}
