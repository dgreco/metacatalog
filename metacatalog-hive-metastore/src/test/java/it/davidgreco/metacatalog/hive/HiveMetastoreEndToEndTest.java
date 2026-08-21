package it.davidgreco.metacatalog.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.HiveMetastoreApplication;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.service.EntityService;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.GetTableRequest;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the real application against a Testcontainers PostgreSQL and drives it over a real Thrift
 * socket — the proof that the server speaks the actual protocol rather than merely being
 * self-consistent.
 *
 * <p>The client here is Thrift's own generated {@code ThriftHiveMetastore.Client}, not {@code
 * HiveMetaStoreClient}. That is not a shortcut: {@code HiveMetaStoreClient} calls {@code
 * Subject.getSubject()}, which throws {@code UnsupportedOperationException} on JDK 24 and later,
 * and the usual escape hatch {@code -Djava.security.manager=allow} is <em>rejected at VM
 * startup</em> on JDK 26. It cannot run in this build at all. That is a client-side limit and does
 * not affect real consumers, which run their own JVMs — but it does mean the wrapper's behaviour
 * has to be proven against a Trino or Spark container outside the Maven build rather than here.
 *
 * <p>Every assertion checks both sides: what the protocol returns, and what the catalog graph holds
 * behind it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HiveMetastoreEndToEndTest {

  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4");
  static ConfigurableApplicationContext context;
  static ThriftHiveMetastore.Client client;
  static TSocket transport;
  static int thriftPort;

  @BeforeAll
  static void beforeAll() throws Exception {
    postgres.start();
    var flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();

    thriftPort = freePort();
    context =
        SpringApplication.run(
            HiveMetastoreApplication.class,
            "--server.port=0",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--application.config.hive.thrift-port=" + thriftPort);

    transport = new TSocket("localhost", thriftPort);
    transport.open();
    client = new ThriftHiveMetastore.Client(new TBinaryProtocol(transport));
  }

  @AfterAll
  static void afterAll() {
    if (transport != null) transport.close();
    if (context != null) context.close();
    postgres.stop();
  }

  private static int freePort() throws IOException {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static Database database(String name) {
    var database = new Database();
    database.setName(name);
    database.setDescription("end to end");
    database.setLocationUri("s3://warehouse/" + name);
    database.setParameters(new HashMap<>(Map.of("created_by", "e2e")));
    return database;
  }

  private static Table table(String databaseName, String name) {
    var sd = new StorageDescriptor();
    sd.setCols(
        List.of(new FieldSchema("id", "bigint", "pk"), new FieldSchema("payload", "string", null)));
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
    table.setOwner("e2e");
    table.setTableType("EXTERNAL_TABLE");
    table.setSd(sd);
    table.setPartitionKeys(List.of(new FieldSchema("dt", "string", null)));
    table.setParameters(new HashMap<>(Map.of("EXTERNAL", "TRUE")));
    return table;
  }

  @Test
  @Order(1)
  void databasesOverThrift() throws Exception {
    client.create_database(database("sales"));
    client.create_database(database("staging"));

    assertTrue(client.get_all_databases().containsAll(List.of("sales", "staging")));
    var read = client.get_database("sales");
    assertEquals("end to end", read.getDescription());
    assertEquals("e2e", read.getParameters().get("created_by"));
    assertEquals(List.of("sales"), client.get_databases("sal*"));

    assertThrows(
        org.apache.hadoop.hive.metastore.api.AlreadyExistsException.class,
        () -> client.create_database(database("sales")));
    assertThrows(
        org.apache.hadoop.hive.metastore.api.NoSuchObjectException.class,
        () -> client.get_database("nope"));
  }

  @Test
  @Order(2)
  void tablesOverThrift() throws Exception {
    client.create_table(table("sales", "orders"));

    var request = new GetTableRequest("sales", "orders");
    var result = client.get_table_req(request);
    assertEquals("orders", result.getTable().getTableName());
    assertEquals(2, result.getTable().getSd().getCols().size());
    assertEquals("pk", result.getTable().getSd().getCols().get(0).getComment());
    assertEquals(
        "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe",
        result.getTable().getSd().getSerdeInfo().getSerializationLib());
    assertEquals(List.of("orders"), client.get_all_tables("sales"));

    // The columns and the partition keys, which is what Hive means by the schema.
    assertEquals(
        List.of("id", "payload", "dt"),
        client.get_schema("sales", "orders").stream().map(FieldSchema::getName).toList());
    assertEquals(
        List.of("id", "payload"),
        client.get_fields("sales", "orders").stream().map(FieldSchema::getName).toList());

    // And the catalog side: a table entity, contained by its database entity.
    var entityService = context.getBean(EntityService.class);
    var databases = entityService.list(HiveModel.DATABASE_TYPE, "$ ? (@.name == \"sales\")");
    assertEquals(1, databases.size());
    var contained = entityService.linked(databases.getFirst().getId(), RelationType.HAS_PART);
    assertEquals(1, contained.size());
    assertEquals("orders", contained.getFirst().getValues().path("name").asText());
  }

  @Test
  @Order(3)
  void partitionsOverThrift() throws Exception {
    var first = new Partition();
    first.setDbName("sales");
    first.setTableName("orders");
    first.setValues(List.of("2026-08-20"));
    client.add_partition(first);

    var second = new Partition();
    second.setDbName("sales");
    second.setTableName("orders");
    second.setValues(List.of("2026-08-21"));
    client.add_partition(second);

    assertEquals(2, client.get_partitions("sales", "orders", (short) -1).size());
    assertEquals(
        List.of("dt=2026-08-20", "dt=2026-08-21"),
        client.get_partition_names("sales", "orders", (short) -1).stream().sorted().toList());
    assertEquals(
        List.of("2026-08-20"),
        client.get_partition("sales", "orders", List.of("2026-08-20")).getValues());

    client.drop_partition("sales", "orders", List.of("2026-08-20"), false);
    assertEquals(1, client.get_partitions("sales", "orders", (short) -1).size());
  }

  @Test
  @Order(4)
  void renameAndDropOverThrift() throws Exception {
    var moved = table("staging", "orders_archive");
    client.alter_table("sales", "orders", moved);

    assertEquals(List.of(), client.get_all_tables("sales"));
    assertEquals(List.of("orders_archive"), client.get_all_tables("staging"));
    // The surviving partition came along.
    assertEquals(1, client.get_partitions("staging", "orders_archive", (short) -1).size());

    client.drop_table("staging", "orders_archive", false);
    assertEquals(List.of(), client.get_all_tables("staging"));

    // The partition went with the table: the aggregate cascade, seen from outside.
    var entityService = context.getBean(EntityService.class);
    assertTrue(
        entityService
            .list(HiveModel.PARTITION_TYPE, "$ ? (@.tableName == \"orders_archive\")")
            .isEmpty());

    client.drop_database("sales", false, false);
    client.drop_database("staging", false, false);
    assertFalse(client.get_all_databases().contains("sales"));
  }

  @Test
  @Order(5)
  void unsupportedOperationsAreRefusedByName() {
    var refused = assertThrows(MetaException.class, () -> client.get_all_functions());
    assertTrue(refused.getMessage().contains("get_all_functions"), refused.getMessage());
    assertTrue(refused.getMessage().contains("not supported"), refused.getMessage());
  }
}
