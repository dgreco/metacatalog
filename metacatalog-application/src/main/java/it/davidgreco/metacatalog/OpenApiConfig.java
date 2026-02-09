package it.davidgreco.metacatalog;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI configuration for customizing Swagger UI.
 *
 * <p>This configuration class customizes the OpenAPI documentation by:
 *
 * <ul>
 *   <li>Dynamically adding server URLs based on configuration (ingress URL for Kubernetes)
 *   <li>Supporting both local development and production environments
 * </ul>
 *
 * <p>The ingress URL is configurable via the {@code springdoc.server.url} property, allowing the
 * Swagger UI to correctly reference the external ingress endpoint in Kubernetes deployments.
 */
@Configuration
public class OpenApiConfig {

  @Value("${springdoc.server.url:#{null}}")
  private String serverUrl;

  @Value("${springdoc.server.description:Metacatalog API}")
  private String serverDescription;

  /**
   * Customizes the OpenAPI specification with dynamic server configuration.
   *
   * <p>If a server URL is configured (e.g., ingress URL in Kubernetes), it will be added to the
   * OpenAPI servers list. This ensures Swagger UI displays the correct base URL for API requests.
   *
   * @return customized OpenAPI instance
   */
  @Bean
  public OpenAPI customOpenAPI() {
    final OpenAPI openAPI = new OpenAPI();

    if (serverUrl != null && !serverUrl.isBlank()) {
      final Server server = new Server();
      server.setUrl(serverUrl);
      server.setDescription(serverDescription);
      openAPI.setServers(List.of(server));
    }

    return openAPI;
  }
}
