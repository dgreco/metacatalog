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
  DEPENDS_ON(Category.ENTITY),

  /** Indicates that the source is required by the target (inverse of DEPENDS_ON). */
  IS_REQUIRED_BY(Category.ENTITY),

  /** Indicates that the source has the target as a component part. */
  HAS_PART(Category.ENTITY),

  /** Indicates that the source is a part of the target (inverse of HAS_PART). */
  IS_PART_OF(Category.ENTITY),

  /** Indicates that the source is mapped to the target. */
  MAPPED_TO(Category.MAPPING),

  /** Indicates that the source is mapped by the target (inverse of MAPPED_TO). */
  IS_MAPPED_BY(Category.MAPPING);

  /**
   * Whether this relationship type is backed by the mapping relationship table or the entity
   * relationship table.
   */
  public enum Category {
    ENTITY,
    MAPPING
  }

  private final Category category;

  RelationType(Category category) {
    this.category = category;
  }

  /**
   * Returns the category of this relationship type.
   *
   * @return the category
   */
  public Category category() {
    return category;
  }

  /**
   * Returns the inverse of this relationship type.
   *
   * @return the inverse relationship type
   */
  public RelationType inverse() {
    return switch (this) {
      case DEPENDS_ON -> IS_REQUIRED_BY;
      case IS_REQUIRED_BY -> DEPENDS_ON;
      case HAS_PART -> IS_PART_OF;
      case IS_PART_OF -> HAS_PART;
      case MAPPED_TO -> IS_MAPPED_BY;
      case IS_MAPPED_BY -> MAPPED_TO;
    };
  }

  /**
   * Checks whether this relationship type has an inverse.
   *
   * <p>Every standard relation type currently has an inverse, but this method exists so that a
   * future relation type without an inverse can be added without touching call sites.
   *
   * @return true if an inverse relationship exists, false otherwise
   */
  public boolean hasInverse() {
    return true;
  }

  /**
   * Parses the given name into a {@link RelationType}, throwing a {@link IllegalArgumentException}
   * with a clean, user-facing message instead of the default {@code "No enum constant
   * <package.RelationType>"} that leaks the enum's fully qualified class name.
   *
   * @param name the relation type name (case-sensitive)
   * @return the matching relation type
   * @throws IllegalArgumentException if no relation type with the given name exists
   */
  public static RelationType parse(String name) {
    try {
      return RelationType.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unsupported relationship type: '"
              + name
              + "'. Valid values: DEPENDS_ON, IS_REQUIRED_BY, HAS_PART, IS_PART_OF, MAPPED_TO, IS_MAPPED_BY");
    }
  }
}
