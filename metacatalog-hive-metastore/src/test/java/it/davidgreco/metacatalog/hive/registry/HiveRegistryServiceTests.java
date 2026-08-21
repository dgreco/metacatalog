package it.davidgreco.metacatalog.hive.registry;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.hive.CommonServiceTestingSupport;
import it.davidgreco.metacatalog.hive.HiveModel;
import it.davidgreco.metacatalog.service.EntityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The registry against a real database. The assertions deliberately look at both sides: what the
 * protocol would see, and what the catalog graph actually holds — the containment links are the
 * reason for putting this metadata in the metacatalog at all, so a test that only checked the
 * Thrift structs would miss the point.
 */
@SpringBootTest
class HiveRegistryServiceTests extends CommonServiceTestingSupport {

  public HiveRegistryServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  private HiveRegistryService registry() {
    return getApplicationContext().getBean(HiveRegistryService.class);
  }

  private EntityService entities() {
    return getApplicationContext().getBean(EntityService.class);
  }

  private static Database database(String name) {
    var database = new Database();
    database.setName(name);
    database.setDescription("test database");
    database.setLocationUri("s3://warehouse/" + name);
    database.setParameters(new java.util.HashMap<>(Map.of("owner", "dgreco")));
    return database;
  }

  private static Table table(String databaseName, String name) {
    var sd = new StorageDescriptor();
    sd.setCols(
        List.of(new FieldSchema("id", "bigint", "the id"), new FieldSchema("v", "string", null)));
    sd.setLocation("s3://warehouse/" + databaseName + "/" + name);
    sd.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
    sd.setOutputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    var serde = new SerDeInfo();
    serde.setName(name);
    serde.setSerializationLib("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
    sd.setSerdeInfo(serde);

    var table = new Table();
    table.setDbName(databaseName);
    table.setTableName(name);
    table.setOwner("dgreco");
    table.setTableType("EXTERNAL_TABLE");
    table.setSd(sd);
    table.setPartitionKeys(List.of(new FieldSchema("dt", "string", null)));
    table.setParameters(new java.util.HashMap<>(Map.of("EXTERNAL", "TRUE")));
    return table;
  }

  private static Partition partition(String databaseName, String tableName, String dt) {
    var partition = new Partition();
    partition.setDbName(databaseName);
    partition.setTableName(tableName);
    partition.setValues(List.of(dt));
    var sd = new StorageDescriptor();
    sd.setLocation("s3://warehouse/" + databaseName + "/" + tableName + "/dt=" + dt);
    partition.setSd(sd);
    return partition;
  }

  @Test
  void testDatabaseLifecycle() {
    var registry = registry();
    registry.createDatabase(database("sales"));

    var read = registry.getDatabase("sales");
    Assertions.assertEquals("sales", read.getName());
    Assertions.assertEquals("test database", read.getDescription());
    Assertions.assertEquals("dgreco", read.getParameters().get("owner"));
    Assertions.assertTrue(registry.getAllDatabases().contains("sales"));

    Assertions.assertThrows(
        HiveErrors.AlreadyExists.class, () -> registry.createDatabase(database("sales")));

    var altered = database("sales");
    altered.setDescription("changed");
    registry.alterDatabase("sales", altered);
    Assertions.assertEquals("changed", registry.getDatabase("sales").getDescription());

    // Renaming a database would orphan every table still naming the old one.
    var renamed = database("sales_renamed");
    Assertions.assertThrows(
        HiveErrors.InvalidOperation.class, () -> registry.alterDatabase("sales", renamed));

    registry.dropDatabase("sales", false);
    Assertions.assertThrows(HiveErrors.NotFound.class, () -> registry.getDatabase("sales"));
  }

  @Test
  void testTableLifecycleAndItsContainment() {
    var registry = registry();
    registry.createDatabase(database("logs"));
    registry.createTable(table("logs", "events"));

    var read = registry.getTable("logs", "events");
    Assertions.assertEquals("events", read.getTableName());
    Assertions.assertEquals(2, read.getSd().getCols().size());
    Assertions.assertEquals("bigint", read.getSd().getCols().get(0).getType());
    Assertions.assertEquals("the id", read.getSd().getCols().get(0).getComment());
    Assertions.assertEquals("dt", read.getPartitionKeys().get(0).getName());
    Assertions.assertEquals(
        "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe",
        read.getSd().getSerdeInfo().getSerializationLib());
    Assertions.assertEquals(List.of("events"), registry.getTables("logs"));
    Assertions.assertTrue(registry.tableExists("logs", "events"));

    // The catalog side: the table really hangs off its database in the graph.
    var databaseEntity = onlyEntity(HiveModel.DATABASE_TYPE, "$ ? (@.name == \"logs\")");
    var contained = entities().linked(databaseEntity.getId(), RelationType.HAS_PART);
    Assertions.assertEquals(1, contained.size());
    Assertions.assertEquals("events", contained.getFirst().getValues().path("name").asText());

    Assertions.assertThrows(
        HiveErrors.AlreadyExists.class, () -> registry.createTable(table("logs", "events")));

    registry.dropTable("logs", "events");
    Assertions.assertFalse(registry.tableExists("logs", "events"));
    registry.dropDatabase("logs", false);
  }

  @Test
  void testRenamingATableAcrossDatabasesMovesItsContainment() {
    var registry = registry();
    registry.createDatabase(database("from_db"));
    registry.createDatabase(database("to_db"));
    registry.createTable(table("from_db", "moving"));
    registry.addPartition(partition("from_db", "moving", "2026-08-21"));

    var moved = table("to_db", "moved");
    registry.alterTable("from_db", "moving", moved);

    Assertions.assertFalse(registry.tableExists("from_db", "moving"));
    Assertions.assertTrue(registry.tableExists("to_db", "moved"));
    Assertions.assertEquals(List.of(), registry.getTables("from_db"));
    Assertions.assertEquals(List.of("moved"), registry.getTables("to_db"));

    // The containment moved with it — possible only because a database is not an aggregate.
    var fromEntity = onlyEntity(HiveModel.DATABASE_TYPE, "$ ? (@.name == \"from_db\")");
    var toEntity = onlyEntity(HiveModel.DATABASE_TYPE, "$ ? (@.name == \"to_db\")");
    Assertions.assertTrue(entities().linked(fromEntity.getId(), RelationType.HAS_PART).isEmpty());
    Assertions.assertEquals(1, entities().linked(toEntity.getId(), RelationType.HAS_PART).size());

    // And the partition followed its table's new identity.
    var partitions = registry.getPartitions("to_db", "moved", -1);
    Assertions.assertEquals(1, partitions.size());
    Assertions.assertEquals(List.of("2026-08-21"), partitions.getFirst().getValues());

    registry.dropDatabase("to_db", true);
    registry.dropDatabase("from_db", false);
  }

  @Test
  void testPartitionLifecycleAndCascade() {
    var registry = registry();
    registry.createDatabase(database("parts"));
    registry.createTable(table("parts", "daily"));
    registry.addPartition(partition("parts", "daily", "2026-08-20"));
    registry.addPartition(partition("parts", "daily", "2026-08-21"));

    Assertions.assertEquals(2, registry.getPartitions("parts", "daily", -1).size());
    Assertions.assertEquals(1, registry.getPartitions("parts", "daily", 1).size());
    Assertions.assertEquals(
        List.of("2026-08-20"),
        registry.getPartition("parts", "daily", List.of("2026-08-20")).getValues());
    Assertions.assertThrows(
        HiveErrors.AlreadyExists.class,
        () -> registry.addPartition(partition("parts", "daily", "2026-08-20")));
    Assertions.assertThrows(
        HiveErrors.NotFound.class,
        () -> registry.getPartition("parts", "daily", List.of("1999-01-01")));

    var altered = partition("parts", "daily", "2026-08-20");
    altered.setParameters(new java.util.HashMap<>(Map.of("numRows", "42")));
    registry.alterPartition("parts", "daily", altered);
    Assertions.assertEquals(
        "42",
        registry
            .getPartition("parts", "daily", List.of("2026-08-20"))
            .getParameters()
            .get("numRows"));

    // A single partition can be dropped deliberately, even though the public API refuses to
    // detach one, because the metastore owns these entities.
    registry.dropPartition("parts", "daily", List.of("2026-08-20"));
    Assertions.assertEquals(1, registry.getPartitions("parts", "daily", -1).size());

    // Dropping the table takes the remaining partition with it: the aggregate cascade.
    registry.dropTable("parts", "daily");
    Assertions.assertTrue(
        entities().list(HiveModel.PARTITION_TYPE, "$ ? (@.tableName == \"daily\")").isEmpty());
    registry.dropDatabase("parts", false);
  }

  @Test
  void testANonEmptyDatabaseNeedsCascade() {
    var registry = registry();
    registry.createDatabase(database("busy"));
    registry.createTable(table("busy", "t1"));
    registry.createTable(table("busy", "t2"));

    Assertions.assertThrows(
        HiveErrors.InvalidOperation.class, () -> registry.dropDatabase("busy", false));

    registry.dropDatabase("busy", true);
    Assertions.assertFalse(registry.getAllDatabases().contains("busy"));
    Assertions.assertTrue(
        entities().list(HiveModel.TABLE_TYPE, "$ ? (@.databaseName == \"busy\")").isEmpty());
  }

  /**
   * Entities carry no name column and no uniqueness constraint on their values, so nothing in the
   * database stops two callers creating the same table. The advisory lock plus the re-check under
   * it is what does.
   */
  @Test
  void testConcurrentCreatesOfTheSameTableProduceOneTable() throws Exception {
    var registry = registry();
    registry.createDatabase(database("race"));

    var competitors = 4;
    var start = new CountDownLatch(1);
    var failures = new AtomicInteger();
    var errors = new ArrayList<Throwable>();
    try (var pool = Executors.newFixedThreadPool(competitors)) {
      for (var i = 0; i < competitors; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                registry.createTable(table("race", "contended"));
              } catch (Throwable t) {
                failures.incrementAndGet();
                synchronized (errors) {
                  errors.add(t);
                }
              }
              return null;
            });
      }
      start.countDown();
      pool.shutdown();
      Assertions.assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
    }

    Assertions.assertEquals(
        competitors - 1, failures.get(), "exactly one caller should have created the table");
    Assertions.assertEquals(
        1,
        entities().list(HiveModel.TABLE_TYPE, "$ ? (@.name == \"contended\")").size(),
        "the table must exist exactly once");

    registry.dropDatabase("race", true);
  }

  private Entity onlyEntity(String type, String jsonPath) {
    var found = entities().list(type, jsonPath);
    Assertions.assertEquals(1, found.size(), "expected exactly one " + type + " for " + jsonPath);
    return found.getFirst();
  }
}
