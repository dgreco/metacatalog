package it.davidgreco.metacatalog.iceberg;

/**
 * Names and schemas of the immutable metacatalog model backing the Iceberg REST catalog.
 *
 * <p>An Iceberg namespace identifier is a list of levels, and a level may itself contain dots; the
 * only character guaranteed illegal inside a level is the unit separator {@code \u001F}, which is
 * also what the REST protocol uses on the wire. The canonical lookup key of a namespace is
 * therefore its levels joined with {@code \u001F}; the dotted {@code name} exists only for display
 * in the metacatalog UI.
 */
public final class IcebergModel {

  public static final String NAMESPACE_TRAIT = "IcebergNamespaceTrait";
  public static final String TABLE_TRAIT = "IcebergTableTrait";
  public static final String NAMESPACE_TYPE = "IcebergNamespace";
  public static final String TABLE_TYPE = "IcebergTable";

  /** Joins namespace levels into the canonical lookup key. */
  public static final String KEY_SEPARATOR = "\u001F";

  public static final String NAMESPACE_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "key": {"type": "string"},
          "name": {"type": "string"},
          "levels": {"type": "array", "items": {"type": "string"}, "minItems": 1},
          "properties": {"type": "object"}
        },
        "required": ["key", "name", "levels"]
      }
      """;

  public static final String TABLE_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "name": {"type": "string"},
          "namespaceKey": {"type": "string"},
          "metadataLocation": {"type": "string"},
          "previousMetadataLocation": {"type": ["string", "null"]},
          "metadata": {"type": "object"}
        },
        "required": ["name", "namespaceKey", "metadataLocation"]
      }
      """;

  private IcebergModel() {}
}
