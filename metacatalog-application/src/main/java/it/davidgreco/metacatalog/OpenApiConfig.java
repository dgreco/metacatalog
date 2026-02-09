package it.davidgreco.metacatalog;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
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

    // Set basic info
    openAPI.info(new Info().title("Metacatalog API").version("1.0"));

    final List<Server> servers = new ArrayList<>();
    System.out.println("Configuring OpenAPI with ingress URL: " + ingressUrl);

    // Add ingress server if configured (for Kubernetes deployments)
    if (ingressUrl != null && !ingressUrl.isBlank()) {
      final Server ingressServer = new Server();
      ingressServer.setUrl(ingressUrl);
      ingressServer.setDescription(ingressDescription);
      servers.add(ingressServer);
      System.out.println("Added ingress server: " + ingressUrl);
    }

    // Always add localhost for local development/testing
    final Server localhostServer = new Server();
    localhostServer.setUrl("http://localhost:8080");
    localhostServer.setDescription("Local development server");
    servers.add(localhostServer);

    if (!servers.isEmpty()) {
      openAPI.servers(servers);
    }

    System.out.println("Total servers configured: " + servers.size());

    return openAPI;
  }

  /**
   * GroupedOpenApi with custom server URLs.
   *
   * <p>Using GroupedOpenApi ensures the OpenApiCustomizer is properly invoked by SpringDoc.
   *
   * @return GroupedOpenApi configuration
   */
  @Bean
  public GroupedOpenApi publicApi() {
    System.out.println("Creating GroupedOpenApi with server customizer");
    return GroupedOpenApi.builder()
        .group("public")
        .pathsToMatch("/**")
        .addOpenApiCustomizer(customServerCustomizer())
        .build();
  }

  /**
   * OpenApiCustomizer to ensure servers are preserved after SpringDoc processing.
   *
   * <p>This customizer runs after SpringDoc builds the OpenAPI spec and forcibly sets the servers
   * list to prevent auto-detection from overriding our custom configuration.
   *
   * @return OpenApiCustomizer that sets server URLs
   */
  private OpenApiCustomizer customServerCustomizer() {
    return openApi -> {
      final List<Server> servers = new ArrayList<>();

      // Add ingress server if configured
      if (ingressUrl != null && !ingressUrl.isBlank()) {
        final Server ingressServer = new Server();
        ingressServer.setUrl(ingressUrl);
        ingressServer.setDescription(ingressDescription);
        servers.add(ingressServer);
        System.out.println("Customizer: Added ingress server: " + ingressUrl);
      }

      // Add localhost server
      final Server localhostServer = new Server();
      localhostServer.setUrl("http://localhost:8080");
      localhostServer.setDescription("Local development server");
      servers.add(localhostServer);

      openApi.setServers(servers);
      System.out.println("Customizer: Set " + servers.size() + " servers");
    };
  }
}
