package it.davidgreco.metacatalog.sparql;

import it.unibz.inf.ontop.injection.OntopSQLOWLAPIConfiguration;
import it.unibz.inf.ontop.rdf4j.repository.OntopRepository;
import it.unibz.inf.ontop.rdf4j.repository.impl.OntopVirtualRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds and tears down the embedded Ontop virtual-knowledge-graph repository.
 *
 * <p>Ontop is loaded in-process (no separate CLI/endpoint process). The {@link
 * OntopVirtualRepository} is the RDF4J {@link org.eclipse.rdf4j.repository.Repository} binding
 * recommended for production by Ontop; it is constructed from an {@link
 * OntopSQLOWLAPIConfiguration} that wires:
 *
 * <ul>
 *   <li>the native OBDA mapping ({@code mapping.obda}) bundled under {@code classpath:ontop/},
 *   <li>the OWL ontology ({@code ontology.owl}) bundled under the same location,
 *   <li>the JDBC coordinates of the metacatalog Postgres database.
 * </ul>
 *
 * <p>Ontop does NOT accept an external {@code javax.sql.DataSource}; it opens its own JDBC
 * connections from {@code jdbcUrl}/{@code jdbcUser}/{@code jdbcPassword}. We point it at the same
 * Postgres instance Spring Data uses (read from {@code spring.datasource.*}).
 *
 * <p>The bean is gated on {@code application.sparql.enabled} (default {@code true}) so the endpoint
 * can be turned off without removing the module.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "application.sparql",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OntopRepositoryConfig {

  private static final Logger LOG = LoggerFactory.getLogger(OntopRepositoryConfig.class);

  private OntopVirtualRepository repository;

  /**
   * Eagerly constructs and initialises the Ontop repository.
   *
   * @param mappingFile classpath or filesystem path to the {@code .obda} mapping file
   * @param ontologyFile classpath or filesystem path to the {@code .owl} ontology file
   * @param jdbcUrl JDBC URL of the metacatalog Postgres database
   * @param jdbcUser JDBC username
   * @param jdbcPassword JDBC password
   * @return the initialised {@link OntopVirtualRepository} singleton
   */
  // Shutdown is handled by the @PreDestroy method below; do not also register a bean
  // destroyMethod, otherwise repository.shutDown() would be invoked twice on context close.
  @Bean
  public OntopVirtualRepository ontopVirtualRepository(
      @Value("${application.sparql.mapping:classpath:ontop/mapping.obda}") String mappingFile,
      @Value("${application.sparql.ontology:classpath:ontop/ontology.owl}") String ontologyFile,
      @Value("${spring.datasource.url}") String jdbcUrl,
      @Value("${spring.datasource.username}") String jdbcUser,
      @Value("${spring.datasource.password}") String jdbcPassword) {

    OntopSQLOWLAPIConfiguration.Builder<?> builder =
        OntopSQLOWLAPIConfiguration.defaultBuilder()
            .nativeOntopMappingFile(resolveClasspath(mappingFile))
            .ontologyFile(resolveClasspath(ontologyFile))
            .jdbcUrl(jdbcUrl)
            .jdbcUser(jdbcUser)
            .jdbcPassword(jdbcPassword);

    OntopSQLOWLAPIConfiguration config = builder.build();

    OntopVirtualRepository repo = OntopRepository.defaultRepository(config);
    repo.init(); // parse mapping, load ontology, connect to DB, classify TBox
    this.repository = repo;
    LOG.info("Ontop repository initialised");
    return repo;
  }

  @PreDestroy
  void shutdown() {
    if (repository != null) {
      LOG.info("Shutting down Ontop repository");
      repository.shutDown();
    }
  }

  /**
   * Resolves a {@code classpath:foo} pseudo-URL to a real filesystem path that Ontop's mapping /
   * ontology loaders can read. Ontop's {@code nativeOntopMappingFile(String)} and {@code
   * ontologyFile(String)} accept filesystem paths or {@code http(s):} URLs but not Spring's {@code
   * classpath:} prefix, so we unpack the resource to a temporary file on disk and hand back the
   * absolute path.
   */
  private static String resolveClasspath(String spec) {
    if (spec == null || spec.isBlank()) {
      return spec;
    }
    if (!spec.startsWith("classpath:")) {
      return spec; // already a filesystem path or URL
    }
    String resourcePath = spec.substring("classpath:".length());
    try (var in = OntopRepositoryConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("Classpath resource not found: " + resourcePath);
      }
      var suffix = resourcePath.substring(resourcePath.lastIndexOf('.'));
      var tmp = java.io.File.createTempFile("ontop-resource-", suffix);
      tmp.deleteOnExit();
      try (var out = new java.io.FileOutputStream(tmp)) {
        in.transferTo(out);
      }
      return tmp.getAbsolutePath();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed to unpack classpath resource: " + spec, e);
    }
  }
}
