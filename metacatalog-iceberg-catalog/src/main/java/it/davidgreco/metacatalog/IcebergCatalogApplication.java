package it.davidgreco.metacatalog;

import it.davidgreco.metacatalog.iceberg.IcebergCatalogProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * The Iceberg REST catalog application: an independent deployable exposing the standard Apache
 * Iceberg REST catalog API on its own port, storing its registry data (namespaces, tables) as
 * metacatalog entities through the core service layer against the same database as the main
 * application.
 *
 * <p>Lives in the {@code it.davidgreco.metacatalog} package root so component scanning picks up
 * {@code CoreConfig}, the repositories, the JPA entities and the {@code ImmutableModelInstaller}
 * from the core jar — the same convention every other module's application class follows.
 *
 * <p>Deliberately does <b>not</b> enable scheduling: the mapping updater is owned by the main
 * application. This app writes lifecycle events like any other service-layer client but never runs
 * the scheduler itself.
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableConfigurationProperties({CoreConfigProperties.class, IcebergCatalogProperties.class})
public class IcebergCatalogApplication {

  protected IcebergCatalogApplication() {}

  public static void main(final String[] args) {
    SpringApplication.run(IcebergCatalogApplication.class, args);
  }
}
