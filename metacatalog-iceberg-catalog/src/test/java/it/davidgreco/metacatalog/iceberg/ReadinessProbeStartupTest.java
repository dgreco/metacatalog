package it.davidgreco.metacatalog.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.IcebergCatalogApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Pins what the Compose healthchecks rely on, because getting it wrong is invisible in the build
 * and expensive in the demo.
 *
 * <p>{@code depends_on: service_healthy} is what makes the one-shot loaders wait for an app before
 * posting a model built on the traits {@code ImmutableModelInstaller} creates. Left to itself,
 * plain {@code /actuator/health} does not express that wait: it is UP as soon as Tomcat is
 * listening, which happens during context refresh, while {@code ApplicationRunner}s — the installer
 * among them — run afterwards. The readiness probe is the one that flips only once they are done.
 *
 * <p>So the test probes the application from <em>inside</em> its own startup, from a runner, and
 * pins what is true there. The third test covers the failure that would hurt most — a readiness
 * path that is not exposed at all never turns the container healthy, breaking the whole stack
 * rather than racing occasionally.
 *
 * <p>Note what {@code management.endpoint.health.probes.enabled} does beyond exposing the two
 * paths: it also folds the readiness indicator into the <em>aggregate</em>, so plain {@code
 * /actuator/health} is 503 during startup as well. That is why the Kubernetes {@code startupProbe},
 * which polls the aggregate, already expresses the same wait — Boot turns probes on by itself when
 * it detects Kubernetes, and this setting is what gives Compose the behaviour k8s always had.
 */
class ReadinessProbeStartupTest {

  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4");
  static ConfigurableApplicationContext context;
  static Path warehouse;
  static int port;

  static final AtomicReference<String> healthDuringStartup = new AtomicReference<>();
  static final AtomicReference<String> readinessDuringStartup = new AtomicReference<>();

  /**
   * Deliberately carries no {@code @Configuration} / {@code @Component}: it sits under the package
   * {@link IcebergCatalogApplication} scans, so a stereotype would attach this runner to every
   * other test's context too. Passed explicitly as a source instead.
   */
  static class StartupProbeCapture {

    /**
     * No {@code @Order}: the point holds wherever in the runner phase this lands, since the whole
     * phase precedes {@code ApplicationReadyEvent} and the readiness flip that comes with it.
     */
    @Bean
    ApplicationRunner captureProbesDuringStartup(ServletWebServerApplicationContext self) {
      return args -> {
        var runningPort = self.getWebServer().getPort();
        healthDuringStartup.set(probe(runningPort, "/actuator/health"));
        readinessDuringStartup.set(probe(runningPort, "/actuator/health/readiness"));
      };
    }
  }

  @BeforeAll
  static void beforeAll() throws IOException {
    postgres.start();
    var flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();

    warehouse = Files.createTempDirectory("iceberg-readiness-warehouse");
    context =
        new SpringApplicationBuilder(IcebergCatalogApplication.class, StartupProbeCapture.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--application.config.iceberg.warehouse=" + warehouse.toUri());
    port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
  }

  @AfterAll
  static void afterAll() throws IOException {
    if (context != null) context.close();
    postgres.stop();
    if (warehouse != null) Files.deleteIfExists(warehouse);
  }

  /**
   * Enabling the probes does not only add two paths: readiness joins the aggregate, so the plain
   * endpoint refuses during startup too. Worth pinning because the Kubernetes {@code startupProbe}
   * polls this one, and because it is the half that would silently revert — someone removing {@code
   * probes.enabled} gets a plain endpoint that is UP the moment Tomcat listens, which is the race
   * this whole test exists for.
   */
  @Test
  void plainHealthAlsoRefusesWhileApplicationRunnersAreStillRunning() {
    assertTrue(
        healthDuringStartup.get().startsWith("503"),
        "expected the aggregate to refuse during startup but got " + healthDuringStartup.get());
  }

  /** The path the Compose healthchecks name, for the same reason. */
  @Test
  void readinessIsStillDownWhileApplicationRunnersAreStillRunning() {
    assertTrue(
        readinessDuringStartup.get().startsWith("503"),
        "expected readiness to refuse traffic during startup but got "
            + readinessDuringStartup.get());
  }

  /**
   * And that the path the healthcheck names actually exists — the probes are opt-in, so without
   * {@code management.endpoint.health.probes.enabled} this 404s and a container wired to it never
   * becomes healthy at all.
   */
  @Test
  void readinessIsExposedAndUpOnceStartupHasFinished() {
    var response = probe(port, "/actuator/health/readiness");
    assertTrue(response.startsWith("200"), "readiness probe not exposed: " + response);
    assertTrue(response.contains("\"status\":\"UP\""), response);
  }

  private static String probe(int port, String path) {
    try {
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                  HttpResponse.BodyHandlers.ofString());
      return response.statusCode() + ":" + response.body();
    } catch (IOException e) {
      return "failed:" + e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Guards the assertion above from passing on a typo'd path, which would also 404. */
  @Test
  void theProbeHelperReportsAnUnknownPathAsSuch() {
    assertEquals(404, Integer.parseInt(probe(port, "/actuator/health/nosuchprobe").split(":")[0]));
  }
}
