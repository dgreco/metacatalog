package it.davidgreco.metacatalog.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Drives a <em>running</em> metastore with {@link HiveMetaStoreClient} — the client Spark, Trino,
 * Flink and Hive all use.
 *
 * <p>This is the check the Maven build cannot make. {@code HiveMetaStoreClient} calls {@code
 * Subject.getSubject()}, removed in JDK 24 and impossible to re-enable on JDK 24+, so the in-build
 * end-to-end test has to use Thrift's generated client instead. That proves the wire protocol; it
 * does not prove the wrapper is satisfied, and the wrapper does considerably more — it negotiates
 * configuration on connect, wraps calls in retries, and reaches for operations a hand-written
 * client never would.
 *
 * <p>Point it at a stack started with {@code make up-hive-d}; the URI comes from {@code
 * HIVE_METASTORE_URI}, defaulting to the published port on localhost.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HiveMetaStoreClientSmokeTest {

  private static IMetaStoreClient client;

  @BeforeAll
  static void connect() throws Exception {
    var uri = System.getenv().getOrDefault("HIVE_METASTORE_URI", "thrift://localhost:9083");
    var conf = MetastoreConf.newMetastoreConf();
    MetastoreConf.setVar(conf, MetastoreConf.ConfVars.THRIFT_URIS, uri);
    MetastoreConf.setLongVar(conf, MetastoreConf.ConfVars.THRIFT_CONNECTION_RETRIES, 3);
    MetastoreConf.setBoolVar(conf, MetastoreConf.ConfVars.USE_THRIFT_SASL, false);
    conf.set("hive.metastore.client.capability.check", "false");
    client = new HiveMetaStoreClient(conf);
  }

  @AfterAll
  static void disconnect() {
    if (client != null) client.close();
  }

  private static Table table(String databaseName, String name) {
    var sd = new StorageDescriptor();
    sd.setCols(
        List.of(
            new FieldSchema("id", "bigint", "primary key"),
            new FieldSchema("amount", "decimal(10,2)", null),
            new FieldSchema("note", "string", null)));
    sd.setLocation("s3://warehouse/" + databaseName + "/" + name);
    sd.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
    sd.setOutputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    var serde = new SerDeInfo();
    serde.setName(name);
    serde.setSerializationLib("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
    serde.setParameters(new HashMap<>(Map.of("field.delim", ",")));
    sd.setSerdeInfo(serde);

    var table = new Table();
    table.setDbName(databaseName);
    table.setTableName(name);
    table.setOwner("smoke");
    table.setTableType("EXTERNAL_TABLE");
    table.setSd(sd);
    table.setPartitionKeys(List.of(new FieldSchema("dt", "string", null)));
    table.setParameters(new HashMap<>(Map.of("EXTERNAL", "TRUE")));
    return table;
  }

  @Test
  @Order(1)
  void theClientConnectsAndListsDatabases() throws Exception {
    // Connecting at all is most of the point: the handshake calls operations a hand-written
    // client never would.
    assertTrue(client.getAllDatabases() != null);
  }

  @Test
  @Order(2)
  void databases() throws Exception {
    var database = new Database();
    database.setName("smoke_db");
    database.setDescription("created by the client smoke test");
    database.setLocationUri("s3://warehouse/smoke_db");
    database.setParameters(new HashMap<>(Map.of("origin", "smoke")));
    client.createDatabase(database);

    // getAllDatabases sends a catalog-qualified pattern (@hive#), not a plain one — a server that
    // does not decode it returns an empty catalog with no error at all.
    assertTrue(client.getAllDatabases().contains("smoke_db"));
    assertEquals("created by the client smoke test", client.getDatabase("smoke_db").getDescription());
    assertEquals(List.of("smoke_db"), client.getDatabases("smoke_*"));
    assertThrows(NoSuchObjectException.class, () -> client.getDatabase("no_such_db"));
  }

  @Test
  @Order(3)
  void tables() throws Exception {
    client.createTable(table("smoke_db", "payments"));

    var read = client.getTable("smoke_db", "payments");
    assertEquals("payments", read.getTableName());
    assertEquals("EXTERNAL_TABLE", read.getTableType());
    assertEquals(
        List.of("id", "amount", "note"),
        read.getSd().getCols().stream().map(FieldSchema::getName).toList());
    assertEquals("decimal(10,2)", read.getSd().getCols().get(1).getType());
    assertEquals(List.of("dt"), read.getPartitionKeys().stream().map(FieldSchema::getName).toList());
    assertEquals(List.of("payments"), client.getAllTables("smoke_db"));
    assertTrue(client.tableExists("smoke_db", "payments"));

    // The batch read, which is its own protocol operation.
    var batch = client.getTableObjectsByName("smoke_db", List.of("payments", "missing"));
    assertEquals(1, batch.size(), "the batch read skips what is missing rather than failing");
    assertEquals("payments", batch.getFirst().getTableName());

    // getSchema and getFields route through the environment-context variants, not the flat ones a
    // hand-written client would call — which is how the demo found them missing. The schema is the
    // columns plus the partition keys; the fields are the columns alone.
    assertEquals(
        List.of("id", "amount", "note", "dt"),
        client.getSchema("smoke_db", "payments").stream().map(FieldSchema::getName).toList());
    assertEquals(
        List.of("id", "amount", "note"),
        client.getFields("smoke_db", "payments").stream().map(FieldSchema::getName).toList());
  }

  @Test
  @Order(4)
  void partitions() throws Exception {
    for (String dt : List.of("2026-08-20", "2026-08-21")) {
      var partition = new Partition();
      partition.setDbName("smoke_db");
      partition.setTableName("payments");
      partition.setValues(List.of(dt));
      var sd = new StorageDescriptor(client.getTable("smoke_db", "payments").getSd());
      sd.setLocation("s3://warehouse/smoke_db/payments/dt=" + dt);
      partition.setSd(sd);
      client.add_partition(partition);
    }

    assertEquals(2, client.listPartitions("smoke_db", "payments", (short) -1).size());
    assertEquals(
        List.of("dt=2026-08-20", "dt=2026-08-21"),
        client.listPartitionNames("smoke_db", "payments", (short) -1).stream().sorted().toList());
    assertEquals(
        List.of("2026-08-20"),
        client.getPartition("smoke_db", "payments", List.of("2026-08-20")).getValues());

    client.dropPartition("smoke_db", "payments", List.of("2026-08-20"), false);
    assertEquals(1, client.listPartitions("smoke_db", "payments", (short) -1).size());
  }

  @Test
  @Order(5)
  void teardown() throws Exception {
    client.dropTable("smoke_db", "payments", false, true);
    assertFalse(client.tableExists("smoke_db", "payments"));
    client.dropDatabase("smoke_db", false, true, false);
    assertFalse(client.getAllDatabases().contains("smoke_db"));
  }
}
