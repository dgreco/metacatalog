package it.davidgreco.metacatalog.iceberg;

import it.davidgreco.metacatalog.iceberg.registry.IcebergRegistryService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.BaseMetastoreCatalog;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileIO;

/**
 * The Iceberg {@link org.apache.iceberg.catalog.Catalog} over the metacatalog: all registry state
 * lives in metacatalog entities via {@link IcebergRegistryService}, all metadata files live in the
 * warehouse via the configured {@link FileIO}. {@code CatalogHandlers} drives this class to serve
 * the REST protocol; {@code BaseMetastoreCatalog} supplies create/load/replace semantics on top of
 * {@link MetacatalogTableOperations}.
 */
public class MetacatalogIcebergCatalog extends BaseMetastoreCatalog implements SupportsNamespaces {

  private final IcebergRegistryService registry;
  private final FileIO fileIO;
  private final String catalogName;
  private final String warehouse;

  public MetacatalogIcebergCatalog(
      IcebergRegistryService registry, FileIO fileIO, String catalogName, String warehouse) {
    this.registry = registry;
    this.fileIO = fileIO;
    this.catalogName = catalogName;
    this.warehouse =
        warehouse.endsWith("/") ? warehouse.substring(0, warehouse.length() - 1) : warehouse;
  }

  @Override
  public String name() {
    return catalogName;
  }

  @Override
  protected TableOperations newTableOps(TableIdentifier identifier) {
    return new MetacatalogTableOperations(registry, fileIO, catalogName, identifier);
  }

  @Override
  protected String defaultWarehouseLocation(TableIdentifier identifier) {
    var sb = new StringBuilder(warehouse);
    for (String level : identifier.namespace().levels()) {
      sb.append('/').append(level);
    }
    return sb.append('/').append(identifier.name()).toString();
  }

  @Override
  public List<TableIdentifier> listTables(Namespace namespace) {
    return registry.listTables(namespace);
  }

  @Override
  public boolean dropTable(TableIdentifier identifier, boolean purge) {
    var lastMetadataLocation = registry.dropTable(identifier);
    if (lastMetadataLocation == null) {
      return false;
    }
    if (purge) {
      // The DB row is gone; remove everything the last metadata version reaches (data files,
      // manifests, previous metadata) from the warehouse.
      var lastMetadata = TableMetadataParser.read(fileIO, lastMetadataLocation);
      CatalogUtil.dropTableData(fileIO, lastMetadata);
    }
    return true;
  }

  @Override
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    registry.renameTable(from, to);
  }

  @Override
  public boolean tableExists(TableIdentifier identifier) {
    return registry.tableExists(identifier);
  }

  @Override
  public void createNamespace(Namespace namespace, Map<String, String> metadata) {
    registry.createNamespace(namespace, metadata);
  }

  @Override
  public List<Namespace> listNamespaces(Namespace namespace) {
    return registry.listNamespaces(namespace);
  }

  @Override
  public Map<String, String> loadNamespaceMetadata(Namespace namespace) {
    return registry.loadNamespaceProperties(namespace);
  }

  @Override
  public boolean dropNamespace(Namespace namespace) {
    return registry.dropNamespace(namespace);
  }

  @Override
  public boolean setProperties(Namespace namespace, Map<String, String> properties) {
    registry.updateNamespaceProperties(namespace, List.of(), properties);
    return true;
  }

  @Override
  public boolean removeProperties(Namespace namespace, Set<String> properties) {
    registry.updateNamespaceProperties(namespace, List.copyOf(properties), Map.of());
    return true;
  }

  @Override
  public boolean namespaceExists(Namespace namespace) {
    return registry.namespaceExists(namespace);
  }
}
