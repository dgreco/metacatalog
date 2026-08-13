package it.davidgreco.metacatalog.iceberg;

import it.davidgreco.metacatalog.iceberg.registry.IcebergRegistryService;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.FileIO;

/**
 * The commit protocol against the metacatalog registry. The base class already enforces that the
 * commit's base matches the operation's current metadata and handles metadata-file bookkeeping;
 * this class contributes the two storage-specific steps: reading the current metadata location from
 * the registry ({@link #doRefresh()}) and atomically swinging the pointer to a freshly written
 * metadata file ({@link #doCommit(TableMetadata, TableMetadata)}).
 */
class MetacatalogTableOperations extends BaseMetastoreTableOperations {

  private final IcebergRegistryService registry;
  private final FileIO fileIO;
  private final TableIdentifier identifier;
  private final String fullName;

  MetacatalogTableOperations(
      IcebergRegistryService registry,
      FileIO fileIO,
      String catalogName,
      TableIdentifier identifier) {
    this.registry = registry;
    this.fileIO = fileIO;
    this.identifier = identifier;
    this.fullName = catalogName + "." + identifier;
  }

  @Override
  public FileIO io() {
    return fileIO;
  }

  @Override
  protected String tableName() {
    return fullName;
  }

  @Override
  protected void doRefresh() {
    var metadataLocation = registry.findMetadataLocation(identifier);
    if (metadataLocation == null) {
      if (currentMetadataLocation() != null) {
        throw new NoSuchTableException("Table does not exist: %s", identifier);
      }
      disableRefresh();
      return;
    }
    refreshFromMetadataLocation(metadataLocation);
  }

  @Override
  protected void doCommit(TableMetadata base, TableMetadata metadata) {
    boolean newTable = base == null;
    var newMetadataLocation = writeNewMetadataIfRequired(newTable, metadata);
    var expectedMetadataLocation = newTable ? null : base.metadataFileLocation();
    try {
      registry.casCommit(
          identifier,
          expectedMetadataLocation,
          newMetadataLocation,
          TableMetadataParser.toJson(metadata));
    } catch (CommitFailedException
        | AlreadyExistsException
        | NoSuchTableException
        | NoSuchNamespaceException e) {
      // Clean failures: the pointer did not move, so the just-written metadata file is an
      // orphan the client is allowed to clean up and the commit can be retried.
      throw e;
    } catch (RuntimeException e) {
      // Anything else leaves the outcome unknown; the client must NOT delete the new
      // metadata file, because the pointer swap may in fact have gone through.
      throw new CommitStateUnknownException(e);
    }
  }
}
