package it.davidgreco.metacatalog.iceberg;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the Iceberg REST catalog, bound under {@code application.config.iceberg}.
 *
 * @param warehouse the warehouse root URI where table metadata (and data) files live, e.g. {@code
 *     file:///var/lib/iceberg/warehouse} or {@code s3://bucket/warehouse}
 * @param ioImpl the {@link org.apache.iceberg.io.FileIO} implementation class; defaults to the
 *     built-in {@link LocalFileIO} which handles {@code file://} locations without Hadoop
 * @param prefix the URL prefix clients must use after {@code /v1/}, advertised via {@code
 *     /v1/config}
 * @param catalogName the catalog name used in metadata locations and logs
 * @param commitLockRetries how many times a commit retries acquiring the per-table advisory lock
 *     before failing with {@code CommitFailedException}
 * @param commitLockBackoff the pause between advisory-lock acquisition attempts
 * @param ioProperties extra properties passed verbatim to the FileIO (e.g. {@code s3.endpoint},
 *     {@code s3.access-key-id})
 */
@ConfigurationProperties("application.config.iceberg")
public record IcebergCatalogProperties(
    String warehouse,
    String ioImpl,
    String prefix,
    String catalogName,
    int commitLockRetries,
    Duration commitLockBackoff,
    Map<String, String> ioProperties) {

  public IcebergCatalogProperties {
    if (ioImpl == null || ioImpl.isBlank()) ioImpl = LocalFileIO.class.getName();
    if (prefix == null || prefix.isBlank()) prefix = "metacatalog";
    if (catalogName == null || catalogName.isBlank()) catalogName = "metacatalog";
    if (commitLockRetries <= 0) commitLockRetries = 5;
    if (commitLockBackoff == null) commitLockBackoff = Duration.ofMillis(100);
    if (ioProperties == null) ioProperties = Map.of();
  }
}
