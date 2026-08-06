package it.davidgreco.metacatalog;

import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.MappingService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Browser-level smoke tests for the plain-JS front end, which has no JS test harness of its own —
 * this is the only place scripts like {@code mapping-values-editor.js} and {@code
 * instance-values-form.js} actually execute under test.
 *
 * <p>The full application boots against a Testcontainers PostgreSQL and a headless Chrome runs in a
 * second container, reaching the app through {@code host.testcontainers.internal}. Test data is
 * seeded through the core services of the booted context; the pages under test still consume it
 * through the REST API like any browser would.
 */
class UiJavascriptSmokeTest {

  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.1");

  static BrowserWebDriverContainer<?> chrome;

  static ConfigurableApplicationContext context;

  static WebDriver driver;

  static String baseUrl;

  @BeforeAll
  static void beforeAll() {
    postgres.start();

    // The SPARQL endpoint is disabled: in a full reactor build the metacatalog-sparql dependency
    // is its UNSHADED target/classes (relocation of org.jgrapht happens only when the jar is
    // packaged), so Ontop would clash with the modern JGraphT the core module uses. The UI pages
    // under test do not involve SPARQL.
    context =
        SpringApplication.run(
            Application.class,
            "--server.port=0",
            "--application.sparql.enabled=false",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword());

    int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    org.testcontainers.Testcontainers.exposeHostPorts(port);
    baseUrl = "http://host.testcontainers.internal:" + port;

    // Pinned to the -chromium image rather than the Testcontainers default (-chrome): only the
    // chromium flavor is published multi-arch, and the default's amd64-only image dies with
    // "exec format error" on ARM64 runners (e.g. the GitLab CI host).
    chrome =
        new BrowserWebDriverContainer<>(
                DockerImageName.parse("selenium/standalone-chromium:4.43.0")
                    .asCompatibleSubstituteFor("selenium/standalone-chrome"))
            .withCapabilities(new ChromeOptions());
    chrome.start();
    driver = new RemoteWebDriver(chrome.getSeleniumAddress(), new ChromeOptions());
  }

  @AfterAll
  static void afterAll() {
    if (driver != null) driver.quit();
    if (chrome != null) chrome.stop();
    if (context != null) context.close();
    postgres.stop();
  }

  private static WebDriverWait await() {
    return new WebDriverWait(driver, Duration.ofSeconds(20));
  }

  /**
   * The mapping form must propose the previous mapping's values when the chosen (source, target)
   * pair already has one: the schema-driven editor is prefilled with the old SpEL expressions, the
   * path references reappear, and the notice warns that submitting replaces the mapping.
   */
  @Test
  void mappingFormProposesThePreviousMappingForTheSamePair() {
    var entityTypeService = context.getBean(EntityTypeService.class);
    var mappingService = context.getBean(MappingService.class);

    entityTypeService.create(
        "PropSrcType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "a": { "type": "integer" } } }""");
    entityTypeService.create(
        "PropDstType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "b": { "type": "integer" } }, "required": ["b"] }""");

    var expression = "#source.getValue('$.a').intValue()";
    mappingService.create(
        "PropSrcType",
        "PropDstType",
        "{\"b\": \"" + expression + "\"}",
        List.of(new MappingEntityTypeRelationship.EntityPathReference("ai", "IS_REQUIRED_BY{$}")));

    driver.get(baseUrl + "/ui/mappings/new");

    new Select(driver.findElement(By.id("sourceEntityType"))).selectByVisibleText("PropSrcType");
    new Select(driver.findElement(By.id("targetEntityType"))).selectByVisibleText("PropDstType");

    // The editor is built asynchronously (the target schema is fetched over the REST API), then
    // prefilled from the existing mapping.
    await()
        .until(
            d -> {
              var inputs = d.findElements(By.cssSelector("#mapping-values-editor .mv-input"));
              return !inputs.isEmpty()
                  && expression.equals(inputs.getFirst().getDomProperty("value"));
            });

    var note = driver.findElement(By.cssSelector(".mv-proposal")).getText();
    Assertions.assertTrue(
        note.contains("PropSrcType") && note.contains("PropDstType"),
        "the proposal note must name the proposed mapping, got: " + note);
    Assertions.assertTrue(
        note.contains("replace"), "the note must warn that submitting replaces the mapping");
    Assertions.assertTrue(
        note.contains("v1"), "the note must mention the versions the mapping was defined against");

    Assertions.assertEquals(
        "ai",
        driver
            .findElement(By.cssSelector("#path-refs input[name=aliases]"))
            .getDomProperty("value"),
        "the path reference must be proposed too");
  }

  /**
   * Editing an instance pinned to an older type version must build the values form from that
   * version's schema, not the live one: fields added by later versions are absent and the page says
   * which version the instance is pinned to.
   */
  @Test
  void instanceEditorRendersThePinnedVersionSchema() {
    var entityTypeService = context.getBean(EntityTypeService.class);
    var entityService = context.getBean(EntityService.class);

    entityTypeService.create(
        "PinUiType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "a": { "type": "integer" } } }""");

    var instance =
        entityService.create(
            "PinUiType",
            """
            {"a": 7}
            """);

    entityTypeService.createVersion(
        "PinUiType",
        List.of(),
        Optional.empty(),
        """
        {
          "type": "object",
          "properties": { "a": { "type": "integer" }, "extra": { "type": "string" } }
        }
        """);

    driver.get(baseUrl + "/ui/instances/" + instance.getId() + "/edit");

    await()
        .until(
            ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#instance-values-editor .mv-input")));

    var labels =
        driver.findElements(By.cssSelector("#instance-values-editor .mv-leaf-label")).stream()
            .map(l -> l.getText().trim())
            .toList();
    Assertions.assertTrue(
        labels.stream().anyMatch(l -> l.startsWith("a")), "the v1 field must be rendered");
    Assertions.assertTrue(
        labels.stream().noneMatch(l -> l.startsWith("extra")),
        "a field added by v2 must not appear for an instance pinned to v1, got: " + labels);

    Assertions.assertEquals(
        "7",
        driver
            .findElement(By.cssSelector("#instance-values-editor .mv-input"))
            .getDomProperty("value"),
        "the stored value must be prefilled");

    Assertions.assertTrue(
        driver.getPageSource().contains("pinned to schema version 1"),
        "the page must say which version the instance is pinned to");
  }
}
