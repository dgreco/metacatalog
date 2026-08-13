package it.davidgreco.metacatalog.iceberg;

import it.davidgreco.metacatalog.iceberg.registry.IcebergRegistryService;
import java.util.HashMap;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.io.FileIO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the singleton Iceberg catalog and its {@link FileIO} from the configuration. */
@Configuration
public class CatalogConfig {

  @Bean(destroyMethod = "close")
  public FileIO fileIO(IcebergCatalogProperties properties) {
    var ioProperties = new HashMap<>(properties.ioProperties());
    ioProperties.putIfAbsent(CatalogProperties.WAREHOUSE_LOCATION, properties.warehouse());
    return CatalogUtil.loadFileIO(properties.ioImpl(), ioProperties, null);
  }

  @Bean
  public MetacatalogIcebergCatalog icebergCatalog(
      IcebergRegistryService registry, FileIO fileIO, IcebergCatalogProperties properties) {
    return new MetacatalogIcebergCatalog(
        registry, fileIO, properties.catalogName(), properties.warehouse());
  }
}
