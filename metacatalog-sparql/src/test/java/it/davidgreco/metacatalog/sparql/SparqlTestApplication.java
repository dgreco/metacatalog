package it.davidgreco.metacatalog.sparql;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot entry point for the sparql module's integration tests. The module itself is a
 * library (no main class); this class gives {@code @SpringBootTest} a configuration to load.
 */
@SpringBootApplication
class SparqlTestApplication {}
