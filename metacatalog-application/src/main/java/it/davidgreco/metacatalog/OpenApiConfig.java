package it.davidgreco.metacatalog;

import io.swagger.v3.oas.models.servers.Server;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

/**
 * OpenAPI configuration for customizing Swagger UI.
 *
 * <p>This configuration class customizes the OpenAPI documentation by:
 *
 * <ul>
 *   <li>Dynamically adding server URLs based on configuration (ingress URL for Kubernetes)
 *   <li>Supporting both local development and production environments
 *   <li>Injecting a "Back to UI" link into the Swagger page so users can return to the main Meta
 *       Catalog UI after exploring the API
 * </ul>
 *
 * <p>The ingress URL is configurable via the {@code INGRESS_URL} environment variable, allowing the
 * Swagger UI to correctly reference the external ingress endpoint in Kubernetes deployments.
 */
@Configuration
public class OpenApiConfig {

  private final String ingressUrl;
  private final String ingressDescription;

  public OpenApiConfig(
      @Value("${INGRESS_URL:}") String ingressUrl,
      @Value("${INGRESS_DESCRIPTION:Metacatalog API (Ingress)}") String ingressDescription) {
    this.ingressUrl = ingressUrl;
    this.ingressDescription = ingressDescription;
  }

  /**
   * A {@link SwaggerIndexTransformer} that delegates to springdoc's default {@link
   * SwaggerIndexPageTransformer} and then injects a "Back to UI" link into the Swagger page so the
   * user can return to the main Meta Catalog UI after opening the API explorer.
   *
   * <p>The link is styled to match the UI topbar buttons and is pinned to the top-right corner of
   * the Swagger page. It points at {@code /ui} (the UI home) with a {@code target="_top"} so it
   * escapes any iframe the Swagger UI might be embedded in.
   *
   * <p>Springdoc registers its own {@code SwaggerIndexPageTransformer} bean with
   * {@code @ConditionalOnMissingBean}, so this bean takes precedence and the default is skipped —
   * but we still construct a {@link SwaggerIndexPageTransformer} delegate and call its {@code
   * transform(...)} so all default springdoc behaviour (CSRF token injection, config URLs, syntax
   * highlighting, etc.) is preserved.
   *
   * @param swaggerUiConfig springdoc's Swagger UI config properties
   * @param swaggerUiOAuthProperties springdoc's Swagger UI OAuth properties
   * @param swaggerWelcomeCommon springdoc's welcome page helper
   * @param objectMapperProvider springdoc's object-mapper provider
   * @return the custom index-page transformer
   */
  @Bean
  public SwaggerIndexTransformer swaggerIndexTransformer(
      final SwaggerUiConfigProperties swaggerUiConfig,
      final SwaggerUiOAuthProperties swaggerUiOAuthProperties,
      final SwaggerWelcomeCommon swaggerWelcomeCommon,
      final ObjectMapperProvider objectMapperProvider) {
    final var delegate =
        new SwaggerIndexPageTransformer(
            swaggerUiConfig, swaggerUiOAuthProperties, swaggerWelcomeCommon, objectMapperProvider);
    return new SwaggerIndexTransformer() {
      @Override
      public Resource transform(
          final jakarta.servlet.http.HttpServletRequest request,
          final Resource resource,
          final ResourceTransformerChain transformerChain)
          throws IOException {
        final Resource transformed = delegate.transform(request, resource, transformerChain);
        // Only inject into the HTML index page — reading binary resources (fonts, images)
        // as a UTF-8 string and re-encoding them would corrupt the bytes.
        final String filename = transformed.getFilename();
        if (filename == null || !filename.endsWith(".html")) {
          return transformed;
        }
        final String html;
        try (InputStream in = transformed.getInputStream()) {
          html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final String injected =
            html.replace(
                    "</head>",
                    "<style>\n"
                        + "  .mc-back-to-ui{position:fixed;top:10px;right:12px;z-index:10000;"
                        + "font-family:system-ui,sans-serif;font-size:13px;font-weight:600;"
                        + "padding:6px 14px;border-radius:6px;text-decoration:none;"
                        + "background:#3b82f6;color:#fff;box-shadow:0 1px 3px rgba(0,0,0,.25);"
                        + "border:1px solid #2563eb;}\n"
                        + "  .mc-back-to-ui:hover{background:#2563eb;}\n"
                        + "</style>\n"
                        + "</head>")
                .replace(
                    "</body>",
                    "<a class=\"mc-back-to-ui\" href=\"/ui\" target=\"_top\">← Back to UI</a>\n"
                        + "</body>");
        return new TransformedResource(transformed, injected.getBytes(StandardCharsets.UTF_8));
      }
    };
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
      }

      // Add localhost server
      final Server localhostServer = new Server();
      localhostServer.setUrl("http://localhost:8080");
      localhostServer.setDescription("Local development server");
      servers.add(localhostServer);

      openApi.setServers(servers);
    };
  }
}
