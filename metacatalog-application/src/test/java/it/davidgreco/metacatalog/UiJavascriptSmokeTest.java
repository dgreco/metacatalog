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
import org.openqa.selenium.StaleElementReferenceException;
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

  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

  static BrowserWebDriverContainer<?> chrome;

  static ConfigurableApplicationContext context;

  static WebDriver driver;

  static String baseUrl;

  @BeforeAll
  static void beforeAll() {
    postgres.start();

    // The SPARQL endpoint is disabled: in a reactor build that stops before `package` the
    // metacatalog-sparql dependency is its target/classes, without the Ontop the shaded jar
    // bundles (the application excludes Ontop's own jars), so the endpoint could not start. The UI
    // pages under test do not involve SPARQL.
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
                DockerImageName.parse("selenium/standalone-chromium:4.49.0")
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

  /**
   * A wait that also tolerates a stale element reference.
   *
   * <p>{@link WebDriverWait} ignores {@code NotFoundException} out of the box but not {@code
   * StaleElementReferenceException}, and several conditions here look an element up and then read
   * it — {@code findElements(...)} followed by {@code getText()}. The pages under test reload on
   * submit, so the element can be replaced between those two calls and the whole wait fails on what
   * is really a "try again" signal. Ignoring it lets the next poll re-find the element, which is
   * exactly what the condition already does.
   */
  private static WebDriverWait await() {
    var wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    // Called as a statement, not chained: ignoring() mutates the wait but is declared to return
    // FluentWait, which would widen the helper's return type for every caller.
    wait.ignoring(StaleElementReferenceException.class);
    return wait;
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

    // The read-only view page offers the same values as JSON and YAML: clicking the YAML tab
    // hides the builder and shows the YAML rendering.
    driver.get(baseUrl + "/ui/instances/" + instance.getId());
    await().until(ExpectedConditions.elementToBeClickable(By.cssSelector("[data-view='yaml']")));
    driver.findElement(By.cssSelector("[data-view='yaml']")).click();
    var yamlPanel = driver.findElement(By.cssSelector("[data-view-panel='yaml']"));
    await().until(d -> yamlPanel.isDisplayed());
    Assertions.assertTrue(
        yamlPanel.getText().contains("a: 7"), "the YAML tab must render the stored values");
    Assertions.assertFalse(
        driver.findElement(By.cssSelector("[data-view-panel='builder']")).isDisplayed(),
        "the builder panel must hide while the YAML view is active");
  }

  /**
   * Success flashes confirm an action that already happened, so they dismiss themselves: a few
   * seconds after the page loads, {@code flash.js} fades the banner out and removes it from the DOM
   * with no user interaction. (Error banners carry information the user still has to act on and are
   * exempt — only {@code .flash} is dismissed.)
   */
  @Test
  void successFlashMessagesDismissThemselves() {
    var entityTypeService = context.getBean(EntityTypeService.class);
    var entityService = context.getBean(EntityService.class);

    entityTypeService.create(
        "FlashUiType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "a": { "type": "integer" } } }""");
    entityService.create("FlashUiType", "{\"a\": 1}");

    driver.get(baseUrl + "/ui/instances?type=FlashUiType");
    await()
        .until(
            ExpectedConditions.elementToBeClickable(
                By.cssSelector("form[data-confirm] .link-remove")));
    driver.findElement(By.cssSelector("form[data-confirm] .link-remove")).click();
    await().until(ExpectedConditions.alertIsPresent());
    driver.switchTo().alert().accept();

    // The redirected list page shows the success flash...
    await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".flash")));
    Assertions.assertEquals(
        "Entity deleted.", driver.findElement(By.cssSelector(".flash")).getText());

    // ...and a few seconds later it is gone on its own.
    await().until(ExpectedConditions.numberOfElementsToBe(By.cssSelector(".flash"), 0));
  }

  /**
   * The instances search must offer a guided way to write the query path once a type is chosen: the
   * condition builder lists the type's schema fields, composes a jsonpath predicate into the query
   * input, combines further conditions with and / or / not (parenthesizing the existing filter when
   * an {@code &&} lands on a top-level {@code ||}), and the search actually filters the list.
   */
  @Test
  void instancesSearchBuilderComposesJsonpathFromTheTypeSchema() {
    var entityTypeService = context.getBean(EntityTypeService.class);
    var entityService = context.getBean(EntityService.class);

    entityTypeService.create(
        "SearchUiType",
        List.of(),
        Optional.empty(),
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" }, "size": { "type": "integer" } }
        }
        """);
    entityService.create("SearchUiType", "{\"name\": \"big\", \"size\": 500}");
    entityService.create("SearchUiType", "{\"name\": \"small\", \"size\": 5}");

    driver.get(baseUrl + "/ui/instances?type=SearchUiType");

    // With a type selected, the builder appears listing the schema's fields.
    await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#query-builder")));
    var fieldSelect = new Select(driver.findElement(By.id("qp-field")));
    var fieldLabels = fieldSelect.getOptions().stream().map(o -> o.getText()).toList();
    Assertions.assertTrue(
        fieldLabels.containsAll(List.of("name", "size")),
        "the builder must list the type's schema fields, got: " + fieldLabels);

    // size > 100 → the numeric operators appear and the composed predicate lands in the input.
    fieldSelect.selectByVisibleText("size");
    new Select(driver.findElement(By.id("qp-op"))).selectByVisibleText(">");
    driver.findElement(By.id("qp-value")).sendKeys("100");
    driver.findElement(By.id("qp-add")).click();
    Assertions.assertEquals(
        "$ ? (@.size > 100)",
        driver.findElement(By.id("query")).getDomProperty("value"),
        "the builder must write the jsonpath predicate into the query input");

    // A second condition merges into the same root filter with the default "and".
    fieldSelect.selectByVisibleText("name");
    new Select(driver.findElement(By.id("qp-op"))).selectByVisibleText("equals");
    driver.findElement(By.id("qp-value")).sendKeys("big");
    driver.findElement(By.id("qp-add")).click();
    Assertions.assertEquals(
        "$ ? (@.size > 100 && @.name == \"big\")",
        driver.findElement(By.id("query")).getDomProperty("value"),
        "a second condition must merge into the existing filter");

    // "or" joins with ||.
    new Select(driver.findElement(By.id("qp-join"))).selectByVisibleText("or");
    fieldSelect.selectByVisibleText("name");
    new Select(driver.findElement(By.id("qp-op"))).selectByVisibleText("equals");
    driver.findElement(By.id("qp-value")).sendKeys("small");
    driver.findElement(By.id("qp-add")).click();
    Assertions.assertEquals(
        "$ ? (@.size > 100 && @.name == \"big\" || @.name == \"small\")",
        driver.findElement(By.id("query")).getDomProperty("value"),
        "an or-condition must join with ||");

    // An "and" onto a filter with a top-level || parenthesizes the existing filter first, so the
    // new condition applies to the whole disjunction rather than its last branch.
    new Select(driver.findElement(By.id("qp-join"))).selectByVisibleText("and");
    fieldSelect.selectByVisibleText("size");
    new Select(driver.findElement(By.id("qp-op"))).selectByVisibleText("<");
    driver.findElement(By.id("qp-value")).sendKeys("10");
    driver.findElement(By.id("qp-add")).click();
    Assertions.assertEquals(
        "$ ? ((@.size > 100 && @.name == \"big\" || @.name == \"small\") && @.size < 10)",
        driver.findElement(By.id("query")).getDomProperty("value"),
        "an and-condition onto a top-level || must parenthesize the existing filter");

    // Searching applies the composed filter server-side: only "small" satisfies it. The wait
    // demands exactly one matching row — the pre-search page lists both instances, so a weaker
    // condition could pass before the reload lands.
    driver.findElement(By.cssSelector(".filter-bar button[type=submit]")).click();
    await()
        .until(
            d -> {
              var trs = d.findElements(By.cssSelector("main table tbody tr"));
              return trs.size() == 1 && trs.getFirst().getText().contains("small");
            });

    // "not" negates a condition: !(name == "small") matches only "big" — a result set distinct
    // from the previous search's, so the wait below cannot pass against the pre-reload page. The
    // page reloaded on search, so the controls are re-located and the query rebuilt from scratch.
    driver.findElement(By.id("query")).clear();
    driver.findElement(By.id("qp-not")).click();
    new Select(driver.findElement(By.id("qp-field"))).selectByVisibleText("name");
    new Select(driver.findElement(By.id("qp-op"))).selectByVisibleText("equals");
    driver.findElement(By.id("qp-value")).sendKeys("small");
    driver.findElement(By.id("qp-add")).click();
    Assertions.assertEquals(
        "$ ? (!(@.name == \"small\"))",
        driver.findElement(By.id("query")).getDomProperty("value"),
        "a not-condition must be wrapped in !(...)");

    driver.findElement(By.cssSelector(".filter-bar button[type=submit]")).click();
    await()
        .until(
            d -> {
              var trs = d.findElements(By.cssSelector("main table tbody tr"));
              return trs.size() == 1 && trs.getFirst().getText().contains("big");
            });
  }
}
