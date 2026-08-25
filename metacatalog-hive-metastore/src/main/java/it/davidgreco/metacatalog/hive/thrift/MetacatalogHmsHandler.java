package it.davidgreco.metacatalog.hive.thrift;

import com.facebook.fb303.fb_status;
import it.davidgreco.metacatalog.hive.registry.HiveErrors;
import it.davidgreco.metacatalog.hive.registry.HiveRegistryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hive.metastore.api.AddPartitionsRequest;
import org.apache.hadoop.hive.metastore.api.AddPartitionsResult;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.AlterDatabaseRequest;
import org.apache.hadoop.hive.metastore.api.AlterTableRequest;
import org.apache.hadoop.hive.metastore.api.AlterTableResponse;
import org.apache.hadoop.hive.metastore.api.CreateDatabaseRequest;
import org.apache.hadoop.hive.metastore.api.CreateTableRequest;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.DropDatabaseRequest;
import org.apache.hadoop.hive.metastore.api.DropPartitionRequest;
import org.apache.hadoop.hive.metastore.api.DropTableRequest;
import org.apache.hadoop.hive.metastore.api.EnvironmentContext;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.GetDatabaseRequest;
import org.apache.hadoop.hive.metastore.api.GetFieldsRequest;
import org.apache.hadoop.hive.metastore.api.GetFieldsResponse;
import org.apache.hadoop.hive.metastore.api.GetPartitionRequest;
import org.apache.hadoop.hive.metastore.api.GetPartitionResponse;
import org.apache.hadoop.hive.metastore.api.GetSchemaRequest;
import org.apache.hadoop.hive.metastore.api.GetSchemaResponse;
import org.apache.hadoop.hive.metastore.api.GetTableRequest;
import org.apache.hadoop.hive.metastore.api.GetTableResult;
import org.apache.hadoop.hive.metastore.api.GetTablesRequest;
import org.apache.hadoop.hive.metastore.api.GetTablesResult;
import org.apache.hadoop.hive.metastore.api.InvalidOperationException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PartitionsRequest;
import org.apache.hadoop.hive.metastore.api.PartitionsResponse;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.thrift.TException;
import org.springframework.stereotype.Component;

/**
 * The operations this metastore actually answers, named exactly as the Thrift protocol names them
 * so {@link HmsIfaceProxy} can dispatch to them.
 *
 * <p>It translates in both directions and nothing more: the registry does the work, and this class
 * turns {@link HiveErrors} into the protocol's checked exception vocabulary. Anything absent from
 * here is refused by name, which is a better answer than a plausible-looking wrong one — a
 * metastore that quietly returned empty statistics or an empty partition list would have clients
 * making decisions on it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetacatalogHmsHandler {

  private final HiveRegistryService registry;

  // ---------------------------------------------------------------------------------------------
  // Connection handshake
  // ---------------------------------------------------------------------------------------------

  /** Clients read config through the metastore; none of it is settable here. */
  public String getMetaConf(String key) {
    return "";
  }

  public void setMetaConf(String key, String value) {
    // Accepted and ignored: a client setting a client-side default must not fail its connection.
  }

  public String getName() {
    return "metacatalog-hive-metastore";
  }

  public String getVersion() {
    return "1.0";
  }

  /**
   * fb303 liveness. The enum, not its ordinal: dispatch is by name through a dynamic proxy, so the
   * value this returns is handed to Thrift as-is, and an {@code int} where the interface declares
   * {@code fb_status} reaches the client as a failure rather than a status.
   */
  public fb_status getStatus() {
    return fb_status.ALIVE;
  }

  public String getStatusDetails() {
    return "metacatalog Hive metastore";
  }

  public void flushCache() {
    // Nothing is cached: every read goes to the database.
  }

  // ---------------------------------------------------------------------------------------------
  // Databases
  // ---------------------------------------------------------------------------------------------

  public void create_database(Database database) throws TException {
    run(() -> registry.createDatabase(database));
  }

  public Database get_database(String name) throws TException {
    var databaseName = HiveCatalogNames.database(name);
    return get(() -> registry.getDatabase(databaseName));
  }

  public List<String> get_all_databases() throws TException {
    return get(registry::getAllDatabases);
  }

  public List<String> get_databases(String pattern) throws TException {
    return matching(get(registry::getAllDatabases), HiveCatalogNames.pattern(pattern));
  }

  public void alter_database(String name, Database database) throws TException {
    var databaseName = HiveCatalogNames.database(name);
    run(() -> registry.alterDatabase(databaseName, database));
  }

  /**
   * @param deleteData is about the warehouse files, which this metastore does not own; the pointer
   *     is dropped either way, which is all it can honestly do
   */
  public void drop_database(String name, boolean deleteData, boolean cascade) throws TException {
    var databaseName = HiveCatalogNames.database(name);
    run(() -> registry.dropDatabase(databaseName, cascade));
  }

  // ---------------------------------------------------------------------------------------------
  // Tables
  // ---------------------------------------------------------------------------------------------

  public void create_table(Table table) throws TException {
    run(() -> registry.createTable(table));
  }

  public void create_table_with_environment_context(Table table, EnvironmentContext context)
      throws TException {
    run(() -> registry.createTable(table));
  }

  // -------------------------------------------------------------------------------------------
  // The request-object forms.
  //
  // Hive 4 moved most operations to a request struct, and HiveMetaStoreClient — the wrapper Spark,
  // Trino, Flink and Hive itself use — calls these, not the flat variants below. Thrift's own
  // generated client calls the flat ones, which is why the in-build end-to-end test was green
  // while a real client got "not supported" for create_database, create_table and get_database.
  // Both forms are kept: the flat ones are still on the interface and older clients still call
  // them.
  // -------------------------------------------------------------------------------------------

  public void create_database_req(CreateDatabaseRequest request) throws TException {
    var database = new Database();
    database.setName(request.getDatabaseName());
    database.setDescription(request.getDescription());
    database.setLocationUri(request.getLocationUri());
    database.setOwnerName(request.getOwnerName());
    database.setOwnerType(request.getOwnerType());
    database.setParameters(request.getParameters());
    run(() -> registry.createDatabase(database));
  }

  public Database get_database_req(GetDatabaseRequest request) throws TException {
    return get(() -> registry.getDatabase(request.getName()));
  }

  public void alter_database_req(AlterDatabaseRequest request) throws TException {
    run(() -> registry.alterDatabase(request.getOldDbName(), request.getNewDb()));
  }

  public void drop_database_req(DropDatabaseRequest request) throws TException {
    run(() -> registry.dropDatabase(request.getName(), request.isCascade()));
  }

  public void create_table_req(CreateTableRequest request) throws TException {
    run(() -> registry.createTable(request.getTable()));
  }

  /**
   * Note the return: unlike the flat {@code alter_table}, the request-object form Hive 4 clients
   * actually call carries a result struct. Returning {@code void} here made the proxy hand Thrift a
   * {@code null} success field, and every client-side {@code alterTable} failed with {@code
   * TApplicationException: alter_table_req failed: unknown result}. The response itself is empty —
   * Hive's own metastore returns a bare one too — but the reply has to contain it.
   */
  public AlterTableResponse alter_table_req(AlterTableRequest request) throws TException {
    run(() -> registry.alterTable(request.getDbName(), request.getTableName(), request.getTable()));
    return new AlterTableResponse();
  }

  public void drop_table_req(DropTableRequest request) throws TException {
    run(() -> registry.dropTable(request.getDbName(), request.getTableName()));
  }

  public AddPartitionsResult add_partitions_req(AddPartitionsRequest request) throws TException {
    for (Partition partition : request.getParts()) {
      run(() -> registry.addPartition(partition));
    }
    var result = new AddPartitionsResult();
    // Only when asked: the client discards it otherwise, and the parts are what it just sent.
    if (request.isNeedResult()) {
      result.setPartitions(request.getParts());
    }
    return result;
  }

  public GetPartitionResponse get_partition_req(GetPartitionRequest request) throws TException {
    var response = new GetPartitionResponse();
    response.setPartition(
        get(
            () ->
                registry.getPartition(
                    request.getDbName(), request.getTblName(), request.getPartVals())));
    return response;
  }

  public PartitionsResponse get_partitions_req(PartitionsRequest request) throws TException {
    var response = new PartitionsResponse();
    response.setPartitions(
        get(
            () ->
                registry.getPartitions(
                    request.getDbName(), request.getTblName(), request.getMaxParts())));
    return response;
  }

  /**
   * What {@code listPartitionNames} calls; the flat {@code get_partition_names} is the old form.
   */
  public List<String> fetch_partition_names_req(PartitionsRequest request) throws TException {
    return partitionNames(request.getDbName(), request.getTblName(), request.getMaxParts());
  }

  public boolean drop_partition_req(DropPartitionRequest request) throws TException {
    run(
        () ->
            registry.dropPartition(
                request.getDbName(), request.getTblName(), request.getPartVals()));
    return true;
  }

  /**
   * Hive 4 replaced {@code get_table(db, name)} with this request-object form, and it is what
   * current clients call. There is no plain {@code get_table} on the interface any more, so
   * offering one would be a method nothing ever reaches.
   */
  public GetTableResult get_table_req(GetTableRequest request) throws TException {
    return new GetTableResult(
        get(() -> registry.getTable(request.getDbName(), request.getTblName())));
  }

  /** Likewise the batch read: {@code get_table_objects_by_name} became this. */
  public GetTablesResult get_table_objects_by_name_req(GetTablesRequest request) throws TException {
    var tables = new ArrayList<Table>();
    for (String name : request.getTblNames()) {
      // Hive's contract for the batch read is to skip what is missing rather than fail it all.
      try {
        tables.add(get(() -> registry.getTable(request.getDbName(), name)));
      } catch (NoSuchObjectException e) {
        log.debug("Skipping {}.{} in a batch read: {}", request.getDbName(), name, e.getMessage());
      }
    }
    return new GetTablesResult(tables);
  }

  public List<String> get_all_tables(String databaseName) throws TException {
    var database = HiveCatalogNames.database(databaseName);
    return get(() -> registry.getTables(database));
  }

  public List<String> get_tables(String databaseName, String pattern) throws TException {
    var database = HiveCatalogNames.database(databaseName);
    return matching(get(() -> registry.getTables(database)), HiveCatalogNames.pattern(pattern));
  }

  public void alter_table(String databaseName, String name, Table table) throws TException {
    run(() -> registry.alterTable(databaseName, name, table));
  }

  public void alter_table_with_environment_context(
      String databaseName, String name, Table table, EnvironmentContext context) throws TException {
    run(() -> registry.alterTable(databaseName, name, table));
  }

  public void drop_table(String databaseName, String name, boolean deleteData) throws TException {
    run(() -> registry.dropTable(databaseName, name));
  }

  public void drop_table_with_environment_context(
      String databaseName, String name, boolean deleteData, EnvironmentContext context)
      throws TException {
    run(() -> registry.dropTable(databaseName, name));
  }

  public List<FieldSchema> get_fields(String databaseName, String name) throws TException {
    return fieldsOf(databaseName, name);
  }

  /** The environment-context and request-object forms, which the real client calls. */
  public List<FieldSchema> get_fields_with_environment_context(
      String databaseName, String name, EnvironmentContext context) throws TException {
    return fieldsOf(databaseName, name);
  }

  public GetFieldsResponse get_fields_req(GetFieldsRequest request) throws TException {
    var response = new GetFieldsResponse();
    response.setFields(fieldsOf(request.getDbName(), request.getTblName()));
    return response;
  }

  private List<FieldSchema> fieldsOf(String databaseName, String name) throws TException {
    var database = HiveCatalogNames.database(databaseName);
    var table = get(() -> registry.getTable(database, name));
    return table.getSd() == null ? List.of() : table.getSd().getCols();
  }

  /** The schema is the columns plus the partition keys, which is how Hive defines it. */
  public List<FieldSchema> get_schema(String databaseName, String name) throws TException {
    return schemaOf(databaseName, name);
  }

  public List<FieldSchema> get_schema_with_environment_context(
      String databaseName, String name, EnvironmentContext context) throws TException {
    return schemaOf(databaseName, name);
  }

  public GetSchemaResponse get_schema_req(GetSchemaRequest request) throws TException {
    var response = new GetSchemaResponse();
    response.setFields(schemaOf(request.getDbName(), request.getTblName()));
    return response;
  }

  private List<FieldSchema> schemaOf(String databaseName, String name) throws TException {
    var database = HiveCatalogNames.database(databaseName);
    var table = get(() -> registry.getTable(database, name));
    var schema = new ArrayList<FieldSchema>();
    if (table.getSd() != null && table.getSd().getCols() != null) {
      schema.addAll(table.getSd().getCols());
    }
    if (table.getPartitionKeys() != null) {
      schema.addAll(table.getPartitionKeys());
    }
    return schema;
  }

  // ---------------------------------------------------------------------------------------------
  // Partitions
  // ---------------------------------------------------------------------------------------------

  public Partition add_partition(Partition partition) throws TException {
    run(() -> registry.addPartition(partition));
    return partition;
  }

  public Partition add_partition_with_environment_context(
      Partition partition, EnvironmentContext context) throws TException {
    run(() -> registry.addPartition(partition));
    return partition;
  }

  public int add_partitions(List<Partition> partitions) throws TException {
    for (Partition partition : partitions) {
      run(() -> registry.addPartition(partition));
    }
    return partitions.size();
  }

  public Partition get_partition(String databaseName, String tableName, List<String> values)
      throws TException {
    return get(() -> registry.getPartition(databaseName, tableName, values));
  }

  public List<Partition> get_partitions(String databaseName, String tableName, short max)
      throws TException {
    return get(() -> registry.getPartitions(databaseName, tableName, max));
  }

  public List<String> get_partition_names(String databaseName, String tableName, short max)
      throws TException {
    return partitionNames(databaseName, tableName, max);
  }

  /** Shared by the flat and request-object forms. */
  private List<String> partitionNames(String databaseName, String tableName, short max)
      throws TException {
    var database = HiveCatalogNames.database(databaseName);
    var table = get(() -> registry.getTable(database, tableName));
    var partitions = get(() -> registry.getPartitions(database, tableName, max));
    var names = new ArrayList<String>();
    for (Partition partition : partitions) {
      names.add(partitionName(table, partition));
    }
    return names;
  }

  public void alter_partition(String databaseName, String tableName, Partition partition)
      throws TException {
    run(() -> registry.alterPartition(databaseName, tableName, partition));
  }

  public boolean drop_partition(
      String databaseName, String tableName, List<String> values, boolean deleteData)
      throws TException {
    run(() -> registry.dropPartition(databaseName, tableName, values));
    return true;
  }

  /** The Hive {@code key=value/key=value} form, derived from the table's partition keys. */
  private static String partitionName(Table table, Partition partition) {
    var keys = table.getPartitionKeys();
    var values = partition.getValues();
    var name = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) name.append('/');
      name.append(i < keys.size() ? keys.get(i).getName() : "col" + i)
          .append('=')
          .append(values.get(i));
    }
    return name.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Error translation
  // ---------------------------------------------------------------------------------------------

  private void run(Runnable action) throws TException {
    get(
        () -> {
          action.run();
          return null;
        });
  }

  /**
   * The registry throws unchecked errors so that Spring's default rollback rules apply; this is
   * where they become the protocol's vocabulary.
   */
  private <T> T get(Supplier<T> action) throws TException {
    try {
      return action.get();
    } catch (HiveErrors.NotFound e) {
      throw new NoSuchObjectException(e.getMessage());
    } catch (HiveErrors.AlreadyExists e) {
      throw new AlreadyExistsException(e.getMessage());
    } catch (HiveErrors.InvalidOperation e) {
      throw new InvalidOperationException(e.getMessage());
    } catch (HiveErrors.Contended e) {
      // Retryable: clients treat MetaException from a metastore as worth another attempt.
      throw new MetaException(e.getMessage());
    } catch (RuntimeException e) {
      log.error("Hive metastore operation failed", e);
      throw new MetaException(
          e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  /** Hive patterns are {@code *}-globs, optionally {@code |}-separated. */
  private static List<String> matching(List<String> names, String pattern) {
    if (pattern == null || pattern.isEmpty() || "*".equals(pattern)) return names;
    var alternatives = pattern.split("\\|");
    var regex = new StringBuilder();
    for (int i = 0; i < alternatives.length; i++) {
      if (i > 0) regex.append('|');
      regex.append(globToRegex(alternatives[i]));
    }
    var compiled = Pattern.compile(regex.toString());
    return names.stream().filter(name -> compiled.matcher(name).matches()).toList();
  }

  private static String globToRegex(String glob) {
    var regex = new StringBuilder();
    for (char c : glob.toCharArray()) {
      if (c == '*') regex.append(".*");
      else regex.append(Pattern.quote(String.valueOf(c)));
    }
    return regex.toString();
  }

  /** Exposed for the guard test, which checks these names still exist on the live interface. */
  public Map<String, java.lang.reflect.Method> supportedOperations() {
    return HmsIfaceProxy.supportedMethods(this);
  }
}
