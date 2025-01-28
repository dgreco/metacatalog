package it.davidgreco.metacatalog.entity;

public enum RelationType {
  DEPENDS_ON,
  IS_REQUIRED_BY, // Inverse of DEPENDS_ON
  HAS_PART,
  IS_PART_OF, // Inverse of HAS_PART
  MAPPED_TO,
  IS_MAPPED_BY, // Inverse of MAPPED_TO
}
