import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.AlterTableRequest;
import org.apache.hadoop.hive.metastore.api.CreateDatabaseRequest;
import org.apache.hadoop.hive.metastore.api.CreateTableRequest;
import org.apache.hadoop.hive.metastore.api.DropTableRequest;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.GetTableRequest;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;

/**
 * Creates, drops and stamps the access decision on a Hive table over the metastore's Thrift
 * protocol.
 *
 * <pre>
 *   echo '{"database":"sales","table":"returns","location":"s3://...","columns":[...]}' \
 *     | java -jar hive-cli.jar create thrift://host:9083
 *   echo '{"database":"sales","table":"returns","grantee":"finance-reporting"}' \
 *     | java -jar hive-cli.jar authorize thrift://host:9083
 * </pre>
 *
 * <p>{@code create} / {@code drop} are the provisioning lifecycle; {@code authorize} / {@code
 * reject} are the authorization one, which is independent of it. The latter pair writes the
 * decision into the table's own parameters ({@code access.status}, {@code access.granted-to}), so
 * a Hive-speaking consumer reads it from the table it is already looking at rather than from a
 * side channel. Hive has no grant primitive a bare metastore can honour — authorization lives in
 * Ranger or a query engine — so a parameter is the honest thing for a metastore to record.
 *
 * <p>All four are idempotent, because a provisioning function may be retried and the demo stack
 * restarts freely: creating what exists is a no-op, dropping what is missing is a no-op, and
 * stamping the same decision twice lands the same parameters. Authorizing a table that does not
 * exist is the one failure — a grant on nothing is not a grant — while rejecting one is a no-op,
 * exactly as dropping one is.
 *
 * <p>Parquet is described the way Hive describes it — the input/output formats and the SerDe are
 * what make a table Parquet to a query engine, not the file extension. No data is written: this is
 * an external table pointing at a location a pipeline fills, which is how Hive external tables are
 * normally used.
 */
public final class HiveCli {

  private static final String PARQUET_INPUT_FORMAT =
      "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat";
  private static final String PARQUET_OUTPUT_FORMAT =
      "org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat";
  private static final String PARQUET_SERDE =
      "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe";

  private HiveCli() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println(
          "usage: hive-cli <create|drop|authorize|reject> <thrift://host:port>  (JSON on stdin)");
      System.exit(2);
    }
    var operation = args[0];
    var uri = java.net.URI.create(args[1]);
    var values = new ObjectMapper().readTree(System.in.readAllBytes());

    var database = text(values, "database");
    var table = text(values, "table");
    if (database == null || table == null) {
      fail("the port must carry a 'database' and a 'table'");
    }

    try (var transport = new TSocket(uri.getHost(), uri.getPort())) {
      transport.open();
      var client = new ThriftHiveMetastore.Client(new TBinaryProtocol(transport));
      switch (operation) {
        case "create" -> create(client, values, database, table);
        case "drop" -> drop(client, database, table);
        case "authorize" -> authorize(client, values, database, table);
        case "reject" -> reject(client, values, database, table);
        default -> fail("unknown operation: " + operation);
      }
    }
  }

  private static void create(
      ThriftHiveMetastore.Client client, JsonNode values, String database, String table)
      throws Exception {
    var request = new CreateDatabaseRequest();
    request.setDatabaseName(database);
    request.setDescription(text(values, "databaseDescription"));
    request.setLocationUri(text(values, "databaseLocation"));
    try {
      client.create_database_req(request);
      System.out.println("created database " + database);
    } catch (AlreadyExistsException alreadyThere) {
      System.out.println("database " + database + " already exists");
    }

    if (tableExists(client, database, table)) {
      System.out.println("table " + database + "." + table + " already exists");
      return;
    }

    var sd = new StorageDescriptor();
    sd.setCols(columns(values.path("columns")));
    sd.setLocation(text(values, "location"));
    sd.setInputFormat(PARQUET_INPUT_FORMAT);
    sd.setOutputFormat(PARQUET_OUTPUT_FORMAT);
    sd.setCompressed(false);
    var serde = new SerDeInfo();
    serde.setName(table);
    serde.setSerializationLib(PARQUET_SERDE);
    serde.setParameters(new LinkedHashMap<>(Map.of("serialization.format", "1")));
    sd.setSerdeInfo(serde);

    var definition = new Table();
    definition.setDbName(database);
    definition.setTableName(table);
    definition.setOwner("metacatalog");
    definition.setTableType("EXTERNAL_TABLE");
    definition.setSd(sd);
    definition.setPartitionKeys(columns(values.path("partitionKeys")));
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("EXTERNAL", "TRUE");
    parameters.put("provisioned_by", "metacatalog");
    if (text(values, "description") != null) {
      parameters.put("comment", text(values, "description"));
    }
    definition.setParameters(parameters);

    var create = new CreateTableRequest();
    create.setTable(definition);
    client.create_table_req(create);
    System.out.println("created table " + database + "." + table + " at " + sd.getLocation());
  }

  private static void drop(ThriftHiveMetastore.Client client, String database, String table)
      throws Exception {
    if (!tableExists(client, database, table)) {
      System.out.println("table " + database + "." + table + " already gone");
      return;
    }
    var request = new DropTableRequest();
    request.setDbName(database);
    request.setTableName(table);
    request.setDeleteData(false);
    client.drop_table_req(request);
    System.out.println("dropped table " + database + "." + table);
    // The database is deliberately left: sibling ports of the same data product share it, and the
    // metastore refuses to drop a non-empty one anyway.
  }

  /** Grants the port's consumer access to the table, recorded in the table's own parameters. */
  private static void authorize(
      ThriftHiveMetastore.Client client, JsonNode values, String database, String table)
      throws Exception {
    var existing = loadTable(client, database, table);
    if (existing == null) {
      fail(
          "table %s.%s does not exist; provision the data product before authorizing it"
              .formatted(database, table));
      return;
    }
    var grantee = text(values, "grantee");
    decide(client, values, database, table, existing, "AUTHORIZED", grantee);
    System.out.println("granted " + grantee + " access to " + database + "." + table);
  }

  /** Withdraws that access. A table that does not exist has nothing to withdraw. */
  private static void reject(
      ThriftHiveMetastore.Client client, JsonNode values, String database, String table)
      throws Exception {
    var existing = loadTable(client, database, table);
    if (existing == null) {
      System.out.println("table " + database + "." + table + " does not exist; nothing to withdraw");
      return;
    }
    decide(client, values, database, table, existing, "REJECTED", null);
    System.out.println("withdrew access to " + database + "." + table);
  }

  /**
   * Records the decision in the table's parameters and writes the table back. A {@code null}
   * grantee removes the name rather than writing one: on reject it no longer holds. The status is
   * always set, never removed — REJECTED is an answer, and a table with no access parameter at all
   * is one nobody has decided about.
   *
   * <p>The two parameter names come from the request rather than from constants here. The Iceberg
   * script writes the same two names as table <em>properties</em>, and the mixed demo only means
   * anything while both catalogs answer the access question identically — a Java constant on this
   * side could drift from the shell one on the other with nothing failing, leaving the two
   * catalogs' consumers quietly disagreeing. They are declared once, in {@code access-decision.sh},
   * and handed to both sides.
   */
  private static void decide(
      ThriftHiveMetastore.Client client,
      JsonNode values,
      String database,
      String table,
      Table existing,
      String status,
      String grantee)
      throws Exception {
    var statusKey = required(values, "accessStatusKey");
    var granteeKey = required(values, "accessGrantedToKey");
    var parameters = parametersOf(existing);
    parameters.put(statusKey, status);
    if (grantee == null) {
      parameters.remove(granteeKey);
    } else {
      parameters.put(granteeKey, grantee);
    }
    alter(client, database, table, existing, parameters);
  }

  /**
   * Writes the table back with new parameters. Hive has no "set one parameter" call: {@code
   * alter_table} replaces the row wholesale, so the table has to be read first and handed back
   * with everything else intact — which is why this takes the loaded table rather than building
   * a fresh one.
   */
  private static void alter(
      ThriftHiveMetastore.Client client,
      String database,
      String table,
      Table existing,
      Map<String, String> parameters)
      throws Exception {
    existing.setParameters(parameters);
    var request = new AlterTableRequest();
    request.setDbName(database);
    request.setTableName(table);
    request.setTable(existing);
    client.alter_table_req(request);
  }

  private static Map<String, String> parametersOf(Table table) {
    return table.getParameters() == null
        ? new LinkedHashMap<>()
        : new LinkedHashMap<>(table.getParameters());
  }

  private static boolean tableExists(
      ThriftHiveMetastore.Client client, String database, String table) throws Exception {
    return loadTable(client, database, table) != null;
  }

  /** The table, or {@code null} if the metastore does not have it. */
  private static Table loadTable(
      ThriftHiveMetastore.Client client, String database, String table) throws Exception {
    try {
      return client.get_table_req(new GetTableRequest(database, table)).getTable();
    } catch (NoSuchObjectException absent) {
      return null;
    }
  }

  private static List<FieldSchema> columns(JsonNode array) {
    var fields = new ArrayList<FieldSchema>();
    array.forEach(
        column ->
            fields.add(
                new FieldSchema(
                    column.path("name").asText(),
                    column.path("type").asText(),
                    column.hasNonNull("comment") ? column.get("comment").asText() : null)));
    return fields;
  }

  private static String text(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  /**
   * A field the caller is contractually required to supply. Only the provisioning scripts invoke
   * this CLI, and they always send it; a missing one means the script and this jar have gone out of
   * step, which is worth saying rather than silently writing a parameter named "null".
   */
  private static String required(JsonNode node, String field) throws IOException {
    var value = text(node, field);
    if (value == null) {
      fail("the request must carry a '" + field + "'; provision-hive-table.sh supplies it");
    }
    return value;
  }

  private static void fail(String message) throws IOException {
    System.err.println(message);
    System.exit(1);
  }
}
