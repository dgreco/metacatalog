package it.davidgreco.metacatalog;

import it.davidgreco.metacatalog.hive.HiveMetastoreProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * The Hive Metastore application: an independent deployable speaking the Apache Hive Metastore
 * Thrift protocol, storing its databases, tables and partitions as metacatalog entities through the
 * core service layer against the same database as the main application.
 *
 * <p>It is a <b>separate implementation</b> of the metastore, not a view over the Iceberg registry
 * next door. It declares its own model and owns its own entities; the two catalogs coexist in one
 * database and one graph, and either side's objects can be linked to anything else in the catalog
 * because both are, in the end, just entities.
 *
 * <p>Two ports, for two unrelated reasons. Thrift on 9083 — the metastore default, so clients need
 * no unusual configuration — is the protocol. HTTP on 8282 exists only to serve the actuator
 * readiness probe, which Compose gates dependent services on.
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
@EnableConfigurationProperties({CoreConfigProperties.class, HiveMetastoreProperties.class})
public class HiveMetastoreApplication {

  protected HiveMetastoreApplication() {}

  public static void main(final String[] args) {
    SpringApplication.run(HiveMetastoreApplication.class, args);
  }
}
