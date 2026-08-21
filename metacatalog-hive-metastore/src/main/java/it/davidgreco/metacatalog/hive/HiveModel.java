package it.davidgreco.metacatalog.hive;

/**
 * The names and JSON Schemas of the Hive metastore's model.
 *
 * <p>The schemas are what a stock metastore does not have. Hive keeps this metadata in a set of
 * loosely-typed relational tables where nothing stops a storage descriptor from being incoherent;
 * here every write is validated against the declared schema before it lands, because that is what
 * {@code EntityService} does with any entity's values.
 *
 * <p>Two constraints shape how these are written, both imposed by {@code JsonUtils.mergeSchemas},
 * which is what turns a trait's schema into the derived schema entities are actually validated
 * against:
 *
 * <ul>
 *   <li>It emits a fresh node carrying only {@code type}, {@code properties}, {@code required} and
 *       {@code additionalProperties} — <b>{@code $defs} does not survive</b>. Shared shapes are
 *       therefore inlined at every use, composed here from Java constants so there is still one
 *       source of truth. Do not "tidy" them back into {@code $defs}/{@code $ref}: the references
 *       would dangle in the derived schema and every write would fail validation.
 *   <li>It stamps {@code additionalProperties: false}, so an entity's values must contain exactly
 *       the declared properties. Anything the metastore needs to persist has to appear here.
 * </ul>
 */
public final class HiveModel {

  private HiveModel() {}

  /** Separator joining a database and table name into a single lock/lookup key. */
  public static final String KEY_SEPARATOR = ".";

  public static final String DATABASE_TRAIT = "HiveDatabaseTrait";
  public static final String TABLE_TRAIT = "HiveTableTrait";
  public static final String PARTITION_TRAIT = "HivePartitionTrait";

  public static final String DATABASE_TYPE = "HiveDatabase";
  public static final String TABLE_TYPE = "HiveTable";
  public static final String PARTITION_TYPE = "HivePartition";

  /** A Hive {@code FieldSchema} list — table columns and partition keys are both this shape. */
  private static final String FIELD_LIST =
      """
      {
        "type": "array",
        "items": {
          "type": "object",
          "required": ["name", "type"],
          "properties": {
            "name": { "type": "string" },
            "type": { "type": "string" },
            "comment": { "type": "string" }
          },
          "additionalProperties": false
        }
      }
      """;

  /** A map of string to string, which is what every Hive {@code parameters} field is. */
  private static final String STRING_MAP =
      """
      { "type": "object", "additionalProperties": { "type": "string" } }
      """;

  /** A Hive {@code StorageDescriptor}, carried by both tables and partitions. */
  private static final String STORAGE_DESCRIPTOR =
      """
      {
        "type": "object",
        "properties": {
          "cols": %s,
          "location": { "type": "string" },
          "inputFormat": { "type": "string" },
          "outputFormat": { "type": "string" },
          "compressed": { "type": "boolean" },
          "numBuckets": { "type": "integer" },
          "bucketCols": { "type": "array", "items": { "type": "string" } },
          "sortCols": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["col", "order"],
              "properties": {
                "col": { "type": "string" },
                "order": { "type": "integer" }
              },
              "additionalProperties": false
            }
          },
          "parameters": %s,
          "serdeInfo": {
            "type": "object",
            "properties": {
              "name": { "type": "string" },
              "serializationLib": { "type": "string" },
              "parameters": %s
            },
            "additionalProperties": false
          }
        },
        "additionalProperties": false
      }
      """
          .formatted(FIELD_LIST, STRING_MAP, STRING_MAP);

  /**
   * A Hive database. {@code name} is the identity the protocol addresses it by; uniqueness is
   * enforced by {@code HiveRegistryService}, since entities carry no name column.
   */
  public static final String DATABASE_SCHEMA =
      """
      {
        "type": "object",
        "required": ["name"],
        "properties": {
          "name": { "type": "string" },
          "description": { "type": "string" },
          "locationUri": { "type": "string" },
          "ownerName": { "type": "string" },
          "ownerType": { "type": "string" },
          "parameters": %s
        }
      }
      """
          .formatted(STRING_MAP);

  /**
   * A Hive table. Columns live inline in the storage descriptor rather than as entities of their
   * own: {@code alter_table} replaces a Hive column list wholesale, unlike an Iceberg schema, which
   * is immutable per id and therefore worth keeping as append-only history.
   */
  public static final String TABLE_SCHEMA =
      """
      {
        "type": "object",
        "required": ["name", "databaseName"],
        "properties": {
          "name": { "type": "string" },
          "databaseName": { "type": "string" },
          "owner": { "type": "string" },
          "ownerType": { "type": "string" },
          "tableType": {
            "type": "string",
            "enum": ["MANAGED_TABLE", "EXTERNAL_TABLE", "VIRTUAL_VIEW", "INDEX_TABLE", "MATERIALIZED_VIEW"]
          },
          "createTime": { "type": "integer" },
          "lastAccessTime": { "type": "integer" },
          "retention": { "type": "integer" },
          "viewOriginalText": { "type": "string" },
          "viewExpandedText": { "type": "string" },
          "parameters": %s,
          "partitionKeys": %s,
          "storageDescriptor": %s
        }
      }
      """
          .formatted(STRING_MAP, FIELD_LIST, STORAGE_DESCRIPTOR);

  /**
   * A Hive partition, identified within its table by the ordered {@code values} matching the
   * table's {@code partitionKeys}.
   */
  public static final String PARTITION_SCHEMA =
      """
      {
        "type": "object",
        "required": ["databaseName", "tableName", "values"],
        "properties": {
          "databaseName": { "type": "string" },
          "tableName": { "type": "string" },
          "values": { "type": "array", "items": { "type": "string" } },
          "createTime": { "type": "integer" },
          "lastAccessTime": { "type": "integer" },
          "parameters": %s,
          "storageDescriptor": %s
        }
      }
      """
          .formatted(STRING_MAP, STORAGE_DESCRIPTOR);
}
