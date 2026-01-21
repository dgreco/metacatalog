package it.davidgreco.metacatalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Main Spring Boot application class for the Metacatalog service.
 *
 * <p>This application provides a comprehensive metadata management system for managing entities,
 * entity types, traits, and their relationships. It exposes a REST API for CRUD operations and
 * supports:
 *
 * <ul>
 *   <li>Transaction management for data integrity
 *   <li>Scheduled tasks for automatic entity mapping
 *   <li>Configuration properties for customizing behavior
 * </ul>
 *
 * @see CoreConfigProperties
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableConfigurationProperties(CoreConfigProperties.class)
@EnableScheduling
public class Application {

  /** Protected constructor to prevent instantiation. */
  protected Application() {}

  /**
   * Application entry point.
   *
   * @param args command-line arguments passed to the application
   */
  public static void main(final String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
