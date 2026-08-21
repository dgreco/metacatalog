package it.davidgreco.metacatalog.hive;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Hive metastore application, bound from {@code application.config.hive}.
 *
 * <p>Defaults are applied in the compact constructor rather than by the binder, so a property left
 * out of the YAML — or bound to an empty value, which is what an empty inline map renders as —
 * still yields a usable record.
 *
 * @param thriftPort the port the Thrift metastore listens on; 9083 is the Hive default and clients
 *     assume it
 * @param minWorkerThreads the Thrift server's minimum worker pool size
 * @param maxWorkerThreads the Thrift server's maximum worker pool size, and so the ceiling on
 *     concurrent client connections
 * @param commitLockRetries how many times to retry a contended advisory lock before failing the
 *     operation
 * @param commitLockBackoff how long to wait between those retries
 */
@ConfigurationProperties(prefix = "application.config.hive")
public record HiveMetastoreProperties(
    int thriftPort,
    int minWorkerThreads,
    int maxWorkerThreads,
    int commitLockRetries,
    Duration commitLockBackoff) {

  public HiveMetastoreProperties {
    if (thriftPort <= 0) thriftPort = 9083;
    if (minWorkerThreads <= 0) minWorkerThreads = 4;
    if (maxWorkerThreads <= 0) maxWorkerThreads = 64;
    if (commitLockRetries <= 0) commitLockRetries = 5;
    if (commitLockBackoff == null) commitLockBackoff = Duration.ofMillis(100);
  }
}
