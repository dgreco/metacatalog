package it.davidgreco.metacatalog.entity;

public enum RelationType {
  DEPENDS_ON,
  IS_REQUIRED_BY, // Inverse of DEPENDS_ON
  HAS_PART,
  IS_PART_OF, // Inverse of HAS_PART
  MAPPED_TO,
  IS_MAPPED_BY; // Inverse of MAPPED_TO

  public RelationType inverse() {
    switch (this) {
      case DEPENDS_ON:
        return IS_REQUIRED_BY;
      case IS_REQUIRED_BY:
        return DEPENDS_ON;
      case HAS_PART:
        return IS_PART_OF;
      case IS_PART_OF:
        return HAS_PART;
      case MAPPED_TO:
        return IS_MAPPED_BY;
      case IS_MAPPED_BY:
        return MAPPED_TO;
      default:
        throw new IllegalArgumentException("No inverse relation type for " + this);
    }
  }

  public boolean hasInverse() {
    return switch (this) {
      case DEPENDS_ON, IS_REQUIRED_BY, IS_PART_OF, IS_MAPPED_BY, HAS_PART, MAPPED_TO -> true;
      default -> false;
    };
  }
}
