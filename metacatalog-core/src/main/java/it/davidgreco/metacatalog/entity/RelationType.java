package it.davidgreco.metacatalog.entity;

/**
 * Defines the types of relationships that can exist between entities, entity types, or traits.
 *
 * <p>Each relationship type has an inverse relationship, allowing bidirectional traversal of the
 * relationship graph. For example, if entity A DEPENDS_ON entity B, then entity B IS_REQUIRED_BY
 * entity A.
 */
public enum RelationType {
  /** Indicates that the source depends on the target. */
  DEPENDS_ON,

  /** Indicates that the source is required by the target (inverse of DEPENDS_ON). */
  IS_REQUIRED_BY,

  /** Indicates that the source has the target as a component part. */
  HAS_PART,

  /** Indicates that the source is a part of the target (inverse of HAS_PART). */
  IS_PART_OF,

  /** Indicates that the source is mapped to the target. */
  MAPPED_TO,

  /** Indicates that the source is mapped by the target (inverse of MAPPED_TO). */
  IS_MAPPED_BY;

  /**
   * Returns the inverse of this relationship type.
   *
   * @return the inverse relationship type
   * @throws IllegalArgumentException if no inverse exists for this type
   */
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

  /**
   * Checks whether this relationship type has an inverse.
   *
   * @return true if an inverse relationship exists, false otherwise
   */
  public boolean hasInverse() {
    return switch (this) {
      case DEPENDS_ON, IS_REQUIRED_BY, IS_PART_OF, IS_MAPPED_BY, HAS_PART, MAPPED_TO -> true;
      default -> false;
    };
  }
}
