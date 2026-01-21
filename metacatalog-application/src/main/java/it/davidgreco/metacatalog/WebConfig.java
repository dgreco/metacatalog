package it.davidgreco.metacatalog;

import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for serving static resources.
 *
 * <p>This configuration class sets up resource handlers to serve Javadoc documentation at the
 * {@code /javadoc/} endpoint. The generated Javadoc HTML files are served from the classpath at
 * {@code /static/javadoc/}.
 *
 * <p>Cache headers are configured for optimal performance:
 *
 * <ul>
 *   <li>Max age of 1 hour for browser caching
 *   <li>Caching enabled for all Javadoc resources
 * </ul>
 *
 * @see WebMvcConfigurer
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  /**
   * Adds resource handlers for serving static Javadoc documentation.
   *
   * <p>Maps the URL pattern {@code /javadoc/**} to serve files from {@code
   * classpath:/static/javadoc/}. Cache control is set to 1 hour for optimal performance.
   *
   * @param registry the resource handler registry to configure
   */
  @Override
  public void addResourceHandlers(final ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/javadoc/**")
        .addResourceLocations("classpath:/static/javadoc/apidocs/")
        .setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic());
  }
}
