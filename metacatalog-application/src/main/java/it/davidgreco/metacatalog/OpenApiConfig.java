package it.davidgreco.metacatalog;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
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
 * <p>The ingress URL is configurable via the {@code INGRESS_URL} environment variable, allowing the
 * Swagger UI to correctly reference the external ingress endpoint in Kubernetes deployments.
 */
@Configuration
public class OpenApiConfig {

  @Value("${INGRESS_URL:}")
  private String ingressUrl;

  @Value("${INGRESS_DESCRIPTION:Metacatalog API (Ingress)}")
  private String ingressDescription;

  /**
   * Customizes the OpenAPI specification with dynamic server configuration.
   *
   * <p>If an ingress URL is configured (e.g., in Kubernetes), it will be added to the OpenAPI
   * servers list alongside the default localhost server. This ensures Swagger UI displays both the
   * local development URL and the production ingress URL.
   *
   * @return customized OpenAPI instance
   */
  @Bean
  public OpenAPI customOpenAPI() {
    final OpenAPI openAPI = new OpenAPI();
    final List<Server> servers = new ArrayList<>();
    System.out.println("Configuring OpenAPI with ingress URL: " + ingressUrl);
    // Add ingress server if configured (for Kubernetes deployments)
    if (ingressUrl != null && !ingressUrl.isBlank()) {
      final Server ingressServer = new Server();
      ingressServer.setUrl(ingressUrl);
      ingressServer.setDescription(ingressDescription);
      servers.add(ingressServer);
    }

    // Always add localhost for local development/testing
    final Server localhostServer = new Server();
    localhostServer.setUrl("http://localhost:8080");
    localhostServer.setDescription("Local development server");
    servers.add(localhostServer);

    openAPI.setServers(servers);

    return openAPI;
  }
}
