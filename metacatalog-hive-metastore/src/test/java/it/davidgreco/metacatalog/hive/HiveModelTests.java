package it.davidgreco.metacatalog.hive;

import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.SchemaValidationError;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TraitService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The model is the foundation everything else stands on, and two of its properties are easy to get
 * silently wrong: whether the declared schemas actually validate real metastore values once {@code
 * JsonUtils.mergeSchemas} has derived them, and whether the aggregate boundaries do what they are
 * drawn to do.
 */
@SpringBootTest
class HiveModelTests extends CommonServiceTestingSupport {

  public HiveModelTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  private static final String FULL_TABLE_VALUES =
      """
      {
        "name": "orders",
        "databaseName": "sales",
        "owner": "dgreco",
        "tableType": "EXTERNAL_TABLE",
        "createTime": 1700000000,
        "retention": 0,
        "parameters": { "EXTERNAL": "TRUE", "comment": "order facts" },
        "partitionKeys": [ { "name": "dt", "type": "string", "comment": "load date" } ],
        "storageDescriptor": {
          "cols": [
            { "name": "order_id", "type": "bigint" },
            { "name": "customer_id", "type": "bigint", "comment": "fk" },
            { "name": "total", "type": "decimal(10,2)" }
          ],
          "location": "s3://warehouse/sales/orders",
          "inputFormat": "org.apache.hadoop.mapred.TextInputFormat",
          "outputFormat": "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat",
          "compressed": false,
          "numBuckets": 4,
          "bucketCols": [ "customer_id" ],
          "sortCols": [ { "col": "order_id", "order": 1 } ],
          "parameters": { "skip.header.line.count": "1" },
          "serdeInfo": {
            "name": "orders",
            "serializationLib": "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe",
            "parameters": { "field.delim": "," }
          }
        }
      }
      """;

  @Test
  void testTheModelIsInstalledAndImmutable() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);

    Assertions.assertTrue(traitService.read(HiveModel.DATABASE_TRAIT).isImmutable());
    Assertions.assertTrue(traitService.read(HiveModel.TABLE_TRAIT).isImmutable());
    Assertions.assertTrue(traitService.read(HiveModel.PARTITION_TRAIT).isImmutable());
    Assertions.assertTrue(entityTypeService.read(HiveModel.DATABASE_TYPE).isImmutable());
    Assertions.assertTrue(entityTypeService.read(HiveModel.TABLE_TYPE).isImmutable());
    Assertions.assertTrue(entityTypeService.read(HiveModel.PARTITION_TYPE).isImmutable());

    // The aggregate boundaries the design depends on.
    Assertions.assertEquals(
        BuiltInTraits.AGGREGATE, traitService.read(HiveModel.TABLE_TRAIT).getFather().getName());
    Assertions.assertEquals(
        BuiltInTraits.AGGREGATE_ELEMENT,
        traitService.read(HiveModel.PARTITION_TRAIT).getFather().getName());
    // The database carries neither built-in: that is what leaves it free to contain a table it can
    // also give up again, which dropping and cross-database renaming both need.
    Assertions.assertNull(traitService.read(HiveModel.DATABASE_TRAIT).getFather());

    Assertions.assertEquals(
        List.of(HiveModel.TABLE_TRAIT),
        traitService.linked(HiveModel.DATABASE_TRAIT, RelationType.HAS_PART).stream()
            .map(Trait::getName)
            .toList());
    Assertions.assertEquals(
        List.of(HiveModel.PARTITION_TRAIT),
        traitService.linked(HiveModel.TABLE_TRAIT, RelationType.HAS_PART).stream()
            .map(Trait::getName)
            .toList());
  }

  /**
   * The schemas are inlined rather than using {@code $defs}/{@code $ref} because {@code
   * mergeSchemas} emits a fresh node and drops {@code $defs}, which would leave every reference
   * dangling. This is the test that would fail if someone factored them back out.
   */
  @Test
  void testAFullyPopulatedTableValidatesAgainstTheDerivedSchema() {
    var entityService = getApplicationContext().getBean(EntityService.class);

    var table = entityService.create(HiveModel.TABLE_TYPE, FULL_TABLE_VALUES);
    Assertions.assertEquals("orders", table.getValues().get("name").asText());
    Assertions.assertEquals(
        3,
        table.getValues().get("storageDescriptor").get("cols").size(),
        "columns were not stored");
    Assertions.assertEquals(
        "dt", table.getValues().get("partitionKeys").get(0).get("name").asText());

    entityService.delete(table.getId());
  }

  @Test
  void testTheSchemasRefuseMalformedMetadata() {
    var entityService = getApplicationContext().getBean(EntityService.class);

    // A column with no type is not a column.
    Assertions.assertThrows(
        SchemaValidationError.class,
        () ->
            entityService.create(
                HiveModel.TABLE_TYPE,
                """
                { "name": "t", "databaseName": "d",
                  "storageDescriptor": { "cols": [ { "name": "no_type" } ] } }
                """));

    // additionalProperties:false is what makes a typo a failure rather than silent data loss.
    Assertions.assertThrows(
        SchemaValidationError.class,
        () ->
            entityService.create(
                HiveModel.TABLE_TYPE,
                """
                { "name": "t", "databaseName": "d", "storageDesciptor": {} }
                """));

    // tableType is an enum, not free text.
    Assertions.assertThrows(
        SchemaValidationError.class,
        () ->
            entityService.create(
                HiveModel.TABLE_TYPE,
                """
                { "name": "t", "databaseName": "d", "tableType": "SOMETHING_ELSE" }
                """));
  }

  /**
   * The aggregate model earning its place: a partition cannot be detached from its table, and
   * dropping the table takes the partition with it. A stock metastore has to remember to cascade.
   */
  @Test
  void testAPartitionCannotBeStrandedFromItsTable() {
    var entityService = getApplicationContext().getBean(EntityService.class);

    var table =
        entityService.create(
            HiveModel.TABLE_TYPE,
            """
            { "name": "events", "databaseName": "logs",
              "partitionKeys": [ { "name": "dt", "type": "string" } ] }
            """);
    var partition =
        entityService.create(
            HiveModel.PARTITION_TYPE,
            """
            { "databaseName": "logs", "tableName": "events", "values": ["2026-08-21"] }
            """);
    entityService.link(table.getId(), RelationType.HAS_PART, partition.getId());

    Assertions.assertThrows(
        ServiceError.class,
        () -> entityService.unlink(table.getId(), RelationType.HAS_PART, partition.getId()),
        "unlinking a partition would strand it, and the aggregate model must refuse");

    var aggregateService =
        getApplicationContext().getBean(it.davidgreco.metacatalog.service.AggregateService.class);
    aggregateService.delete(table.getId());

    Assertions.assertTrue(
        entityService.list(HiveModel.PARTITION_TYPE, "$ ? (@.tableName == \"events\")").isEmpty(),
        "the partition should have gone with its table");
    Assertions.assertEquals(Optional.empty(), findById(entityService, table.getId()));
  }

  private static Optional<Object> findById(EntityService entityService, String id) {
    try {
      return Optional.of(entityService.read(id));
    } catch (RuntimeException notFound) {
      return Optional.empty();
    }
  }
}
