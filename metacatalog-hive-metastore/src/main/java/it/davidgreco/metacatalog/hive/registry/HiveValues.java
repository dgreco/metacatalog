package it.davidgreco.metacatalog.hive.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Order;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;

/**
 * Converts between the Thrift structs the protocol speaks and the JSON an entity's values are.
 *
 * <p>Nulls are omitted rather than written as JSON null: the derived schemas type every declared
 * property, so a {@code null} where a string is expected is a validation failure, and Thrift leaves
 * most fields unset most of the time.
 */
final class HiveValues {

  private HiveValues() {}

  // -----------------------------------------------------------------------------------------
  // Thrift -> JSON
  // -----------------------------------------------------------------------------------------

  static ObjectNode fromDatabase(ObjectNode node, Database database) {
    put(node, "name", database.getName());
    put(node, "description", database.getDescription());
    put(node, "locationUri", database.getLocationUri());
    put(node, "ownerName", database.getOwnerName());
    put(node, "ownerType", database.getOwnerType() == null ? null : database.getOwnerType().name());
    putMap(node, "parameters", database.getParameters());
    return node;
  }

  static ObjectNode fromTable(ObjectNode node, Table table) {
    put(node, "name", table.getTableName());
    put(node, "databaseName", table.getDbName());
    put(node, "owner", table.getOwner());
    put(node, "ownerType", table.getOwnerType() == null ? null : table.getOwnerType().name());
    put(node, "tableType", table.getTableType());
    put(node, "viewOriginalText", table.getViewOriginalText());
    put(node, "viewExpandedText", table.getViewExpandedText());
    node.put("createTime", table.getCreateTime());
    node.put("lastAccessTime", table.getLastAccessTime());
    node.put("retention", table.getRetention());
    putMap(node, "parameters", table.getParameters());
    if (table.getPartitionKeys() != null && !table.getPartitionKeys().isEmpty()) {
      node.set("partitionKeys", fields(node, table.getPartitionKeys()));
    }
    if (table.getSd() != null) {
      node.set("storageDescriptor", storageDescriptor(node, table.getSd()));
    }
    return node;
  }

  static ObjectNode fromPartition(ObjectNode node, Partition partition) {
    put(node, "databaseName", partition.getDbName());
    put(node, "tableName", partition.getTableName());
    var values = node.arrayNode();
    if (partition.getValues() != null) partition.getValues().forEach(values::add);
    node.set("values", values);
    node.put("createTime", partition.getCreateTime());
    node.put("lastAccessTime", partition.getLastAccessTime());
    putMap(node, "parameters", partition.getParameters());
    if (partition.getSd() != null) {
      node.set("storageDescriptor", storageDescriptor(node, partition.getSd()));
    }
    return node;
  }

  private static ObjectNode storageDescriptor(ObjectNode parent, StorageDescriptor sd) {
    var node = parent.objectNode();
    if (sd.getCols() != null && !sd.getCols().isEmpty()) {
      node.set("cols", fields(parent, sd.getCols()));
    }
    put(node, "location", sd.getLocation());
    put(node, "inputFormat", sd.getInputFormat());
    put(node, "outputFormat", sd.getOutputFormat());
    node.put("compressed", sd.isCompressed());
    node.put("numBuckets", sd.getNumBuckets());
    if (sd.getBucketCols() != null && !sd.getBucketCols().isEmpty()) {
      var bucketCols = node.arrayNode();
      sd.getBucketCols().forEach(bucketCols::add);
      node.set("bucketCols", bucketCols);
    }
    if (sd.getSortCols() != null && !sd.getSortCols().isEmpty()) {
      var sortCols = node.arrayNode();
      for (Order order : sd.getSortCols()) {
        var entry = node.objectNode();
        entry.put("col", order.getCol());
        entry.put("order", order.getOrder());
        sortCols.add(entry);
      }
      node.set("sortCols", sortCols);
    }
    putMap(node, "parameters", sd.getParameters());
    if (sd.getSerdeInfo() != null) {
      var serde = node.objectNode();
      put(serde, "name", sd.getSerdeInfo().getName());
      put(serde, "serializationLib", sd.getSerdeInfo().getSerializationLib());
      putMap(serde, "parameters", sd.getSerdeInfo().getParameters());
      node.set("serdeInfo", serde);
    }
    return node;
  }

  private static ArrayNode fields(ObjectNode parent, List<FieldSchema> schemas) {
    var array = parent.arrayNode();
    for (FieldSchema field : schemas) {
      var entry = parent.objectNode();
      entry.put("name", field.getName());
      entry.put("type", field.getType());
      put(entry, "comment", field.getComment());
      array.add(entry);
    }
    return array;
  }

  // -----------------------------------------------------------------------------------------
  // JSON -> Thrift
  // -----------------------------------------------------------------------------------------

  static Database toDatabase(JsonNode values) {
    var database = new Database();
    database.setName(text(values, "name"));
    database.setDescription(text(values, "description"));
    database.setLocationUri(text(values, "locationUri"));
    database.setOwnerName(text(values, "ownerName"));
    var ownerType = text(values, "ownerType");
    if (ownerType != null) database.setOwnerType(PrincipalType.valueOf(ownerType));
    database.setParameters(map(values, "parameters"));
    return database;
  }

  static Table toTable(JsonNode values) {
    var table = new Table();
    table.setTableName(text(values, "name"));
    table.setDbName(text(values, "databaseName"));
    table.setOwner(text(values, "owner"));
    var ownerType = text(values, "ownerType");
    if (ownerType != null) table.setOwnerType(PrincipalType.valueOf(ownerType));
    table.setTableType(text(values, "tableType"));
    table.setViewOriginalText(text(values, "viewOriginalText"));
    table.setViewExpandedText(text(values, "viewExpandedText"));
    table.setCreateTime(values.path("createTime").asInt());
    table.setLastAccessTime(values.path("lastAccessTime").asInt());
    table.setRetention(values.path("retention").asInt());
    table.setParameters(map(values, "parameters"));
    table.setPartitionKeys(toFields(values.path("partitionKeys")));
    table.setSd(toStorageDescriptor(values.path("storageDescriptor")));
    return table;
  }

  static Partition toPartition(JsonNode values) {
    var partition = new Partition();
    partition.setDbName(text(values, "databaseName"));
    partition.setTableName(text(values, "tableName"));
    var partitionValues = new ArrayList<String>();
    values.path("values").forEach(value -> partitionValues.add(value.asText()));
    partition.setValues(partitionValues);
    partition.setCreateTime(values.path("createTime").asInt());
    partition.setLastAccessTime(values.path("lastAccessTime").asInt());
    partition.setParameters(map(values, "parameters"));
    partition.setSd(toStorageDescriptor(values.path("storageDescriptor")));
    return partition;
  }

  private static StorageDescriptor toStorageDescriptor(JsonNode node) {
    var sd = new StorageDescriptor();
    sd.setCols(toFields(node.path("cols")));
    sd.setLocation(text(node, "location"));
    sd.setInputFormat(text(node, "inputFormat"));
    sd.setOutputFormat(text(node, "outputFormat"));
    sd.setCompressed(node.path("compressed").asBoolean());
    sd.setNumBuckets(node.path("numBuckets").asInt());
    var bucketCols = new ArrayList<String>();
    node.path("bucketCols").forEach(value -> bucketCols.add(value.asText()));
    sd.setBucketCols(bucketCols);
    var sortCols = new ArrayList<Order>();
    node.path("sortCols")
        .forEach(
            entry ->
                sortCols.add(new Order(entry.path("col").asText(), entry.path("order").asInt())));
    sd.setSortCols(sortCols);
    sd.setParameters(map(node, "parameters"));
    if (node.has("serdeInfo")) {
      var serdeNode = node.get("serdeInfo");
      var serde = new SerDeInfo();
      serde.setName(text(serdeNode, "name"));
      serde.setSerializationLib(text(serdeNode, "serializationLib"));
      serde.setParameters(map(serdeNode, "parameters"));
      sd.setSerdeInfo(serde);
    }
    return sd;
  }

  static List<FieldSchema> toFields(JsonNode array) {
    var fields = new ArrayList<FieldSchema>();
    array.forEach(
        entry ->
            fields.add(
                new FieldSchema(
                    entry.path("name").asText(),
                    entry.path("type").asText(),
                    text(entry, "comment"))));
    return fields;
  }

  // -----------------------------------------------------------------------------------------

  private static void put(ObjectNode node, String field, String value) {
    if (value != null) node.put(field, value);
  }

  private static void putMap(ObjectNode node, String field, Map<String, String> values) {
    if (values == null || values.isEmpty()) return;
    var map = node.objectNode();
    values.forEach((key, value) -> map.put(key, value == null ? "" : value));
    node.set(field, map);
  }

  private static String text(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Map<String, String> map(JsonNode node, String field) {
    Map<String, String> values = new LinkedHashMap<>();
    var map = node.get(field);
    if (map != null) {
      map.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asText()));
    }
    return values;
  }
}
