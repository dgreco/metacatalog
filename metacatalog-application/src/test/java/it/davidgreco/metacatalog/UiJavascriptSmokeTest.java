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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.selenium.BrowserWebDriverContainer;
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

  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

  static BrowserWebDriverContainer chrome;

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
        new BrowserWebDriverContainer(
            DockerImageName.parse("selenium/standalone-chromium:4.49.0")
                .asCompatibleSubstituteFor("selenium/standalone-chrome"));
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

    driver.get(baseUrl + "/ui/admin/mappings/new");

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

    driver.get(baseUrl + "/ui/admin/instances/" + instance.getId() + "/edit");

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
    driver.get(baseUrl + "/ui/admin/instances/" + instance.getId());
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
   * Saving an instance from the values builder must send back what the user sees, not what the
   * builder happens to have rendered. An access policy is the case that broke it: a grant with no
   * {@code resources} still gets a rendered (empty) {@code resources} group, which used to be
   * submitted as {@code "resources": {"entityTypes": []}} and refused by the schema's {@code
   * minItems: 1}; and the free-form {@code resources.values} map, rendered as a text input, came
   * back as the string {@code "[object Object]"}. Either made a mere rename fail with "Schema
   * validation failed".
   */
  @Test
  void instanceEditorSavesAnAccessPolicyUnchangedExceptForTheEditedField() throws Exception {
    var entityTypeService = context.getBean(EntityTypeService.class);
    var entityService = context.getBean(EntityService.class);
    var json = new com.fasterxml.jackson.databind.ObjectMapper();

    entityTypeService.create(
        "PolicyUiType",
        List.of("Authorizable"),
        Optional.empty(),
        """
        { "type": "object", "properties": { "name": { "type": "string" } } }""");
    var policy =
        """
        {
          "name": "before",
          "principals": [ { "id": "analysts", "type": "GROUP" } ],
          "permissions": [ { "name": "READ" } ],
          "grants": [
            { "principals": ["analysts"], "permissions": ["READ"] },
            { "principals": ["analysts"], "permissions": ["READ"],
              "resources": { "values": { "table": "orders" } } }
          ]
        }""";
    var instance = entityService.create("PolicyUiType", policy);

    driver.get(baseUrl + "/ui/admin/instances/" + instance.getId() + "/edit");
    await()
        .until(
            ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#instance-values-editor > .mv-leaf .mv-input")));
    var nameInput =
        driver
            .findElements(By.cssSelector("#instance-values-editor > .mv-leaf .mv-leaf-label"))
            .stream()
            .filter(l -> l.getText().trim().startsWith("name"))
            .findFirst()
            .orElseThrow()
            .findElement(By.cssSelector("input"));
    nameInput.clear();
    nameInput.sendKeys("after");
    driver.findElement(By.cssSelector("#instance-form .form-actions button[type=submit]")).click();

    await()
        .until(
            d ->
                !d.findElements(By.cssSelector(".flash")).isEmpty()
                    || !d.findElements(By.cssSelector(".error")).isEmpty());
    var errors = driver.findElements(By.cssSelector(".error"));
    Assertions.assertTrue(
        errors.isEmpty(),
        "saving must succeed, got: " + errors.stream().map(e -> e.getText()).toList());

    var expected = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(policy);
    expected.put("name", "after");
    Assertions.assertEquals(
        expected,
        entityService.read(instance.getId()).getValues(),
        "only the edited field may change");
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

    driver.get(baseUrl + "/ui/admin/instances?type=FlashUiType");
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

    driver.get(baseUrl + "/ui/admin/instances?type=SearchUiType");

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

  /**
   * The asset model the asset pages are tested against, created once: a table is an asset, a domain
   * is a plain type, and a table may depend on a domain — nothing else on anything. Each test adds
   * its own assets on top.
   */
  private static void ensureAssetModel() {
    var traitService = context.getBean(it.davidgreco.metacatalog.service.TraitService.class);
    var entityTypeService = context.getBean(EntityTypeService.class);
    if (traitService.exists("SmNamed")) return;

    traitService.create(
        "SmNamed",
        Optional.of(
            """
            { "type": "object", "properties": { "name": { "type": "string" } } }"""),
        Optional.empty());
    traitService.create("SmDomain", Optional.empty(), Optional.empty());
    traitService.create("SmTable", Optional.empty(), Optional.empty());
    traitService.link(
        "SmTable", it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON, "SmDomain");
    var empty =
        """
        { "type": "object", "properties": {} }""";
    entityTypeService.create(
        "SmColumnType", List.of("AggregateElement", "SmNamed"), Optional.empty(), empty);
    entityTypeService.create(
        "SmDomainType", List.of("SmNamed", "SmDomain"), Optional.empty(), empty);
    entityTypeService.create(
        "SmTableType", List.of("Asset", "SmNamed", "SmTable"), Optional.empty(), empty);
  }

  private static org.openqa.selenium.WebElement graphNode(String entityId) {
    return driver.findElement(
        By.cssSelector("#asset-graph .gc-node[data-node-id='" + entityId + "']"));
  }

  /** Carries one graph node onto another with the pointer, as a user would. */
  private static void dragOnto(
      org.openqa.selenium.WebElement source, org.openqa.selenium.WebElement target) {
    new org.openqa.selenium.interactions.Actions(driver)
        .moveToElement(source)
        .clickAndHold()
        // A first small move, so the gesture is past the threshold that tells a drag from a click.
        .moveByOffset(8, 8)
        .moveToElement(target)
        .release()
        .perform();
  }

  /**
   * Dropping an asset onto something in the dependency graph makes it depend on it — when the model
   * allows it. Here the other end is a plain entity, which is on the board because an asset may
   * depend on it, and which cannot itself depend on anything. The drop is a real pointer gesture:
   * this is the one place {@code graph-core.js}'s drag handling and {@code asset-catalog.js}'s
   * rules run together, against the real API.
   */
  @Test
  void anAssetIsMadeToDependOnAnEntityByDraggingItOntoItInTheGraph() {
    ensureAssetModel();
    var entityService = context.getBean(EntityService.class);
    var domain = entityService.create("SmDomainType", "{\"name\": \"sm-sales\"}");
    var table = entityService.create("SmTableType", "{\"name\": \"sm-orders\"}");

    driver.get(baseUrl + "/ui/assets?view=graph");
    await()
        .until(
            ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#asset-graph .gc-node[data-node-id='" + table.getId() + "']")));

    // A domain is depended on; it does not depend. Only an asset can be dropped onto something.
    dragOnto(graphNode(domain.getId()), graphNode(table.getId()));
    await()
        .until(
            d ->
                d.findElements(By.cssSelector("#catalog-notices .error")).stream()
                    .anyMatch(e -> e.getText().contains("Only an asset depends on something")));
    Assertions.assertTrue(
        entityService
            .linked(domain.getId(), it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON)
            .isEmpty(),
        "a refused drop must not link anything");

    dragOnto(graphNode(table.getId()), graphNode(domain.getId()));
    await()
        .until(
            d ->
                d.findElements(By.cssSelector("#catalog-notices .flash")).stream()
                    .anyMatch(e -> e.getText().contains("now depends on")));
    Assertions.assertEquals(
        List.of(domain.getId()),
        entityService
            .linked(table.getId(), it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON)
            .stream()
            .map(it.davidgreco.metacatalog.entity.Entity::getId)
            .toList());
    Assertions.assertFalse(
        driver.findElements(By.cssSelector("#asset-graph .gc-edge-dependency")).isEmpty(),
        "the new dependency must be drawn");

    // The list is the same model: the dependency made in the graph is there without a reload. It
    // lists assets only — the domain is what an asset depends on, never a row of its own.
    driver.findElement(By.cssSelector(".view-switch [data-view='list']")).click();
    var row =
        driver.findElement(By.cssSelector("#asset-rows tr[data-asset-id='" + table.getId() + "']"));
    Assertions.assertTrue(row.getText().contains("sm-sales"), row.getText());
    Assertions.assertTrue(
        driver
            .findElements(By.cssSelector("#asset-rows tr[data-asset-id='" + domain.getId() + "']"))
            .isEmpty(),
        "an entity that is not an asset must not be listed");

    // And it can be removed from there.
    row.findElement(By.cssSelector(".ref-chip-remove")).click();
    driver.switchTo().alert().accept();
    await()
        .until(
            d ->
                entityService
                    .linked(table.getId(), it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON)
                    .isEmpty());
  }

  /**
   * A right-click on the board creates a new node without leaving it: the menu offers the types
   * that belong there, and the dialog asks for the values the chosen type declares.
   */
  @Test
  void aRightClickOnTheBoardCreatesSomethingForAssetsToDependOn() {
    ensureAssetModel();
    var entityService = context.getBean(EntityService.class);

    driver.get(baseUrl + "/ui/assets?view=graph");
    var board =
        await()
            .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#asset-graph svg")));
    // The corner of the board: the layout centres the nodes, so nothing is drawn there. Read from
    // the page rather than offset from the element: the board can reach below the window, and the
    // driver measures an element offset from the centre of the part that is in view.
    @SuppressWarnings("unchecked")
    var corner =
        (List<Number>)
            ((org.openqa.selenium.JavascriptExecutor) driver)
                .executeScript(
                    "var r = arguments[0].getBoundingClientRect();"
                        + " return [Math.round(r.left + 24), Math.round(r.top + 24)];",
                    board);
    new org.openqa.selenium.interactions.Actions(driver)
        .moveToLocation(corner.get(0).intValue(), corner.get(1).intValue())
        .contextClick()
        .perform();

    var menu = await().until(ExpectedConditions.visibilityOfElementLocated(By.id("board-menu")));
    Assertions.assertTrue(menu.getText().contains("SmTableType"), menu.getText());
    menu.findElements(By.cssSelector("button")).stream()
        .filter(item -> item.getText().equals("SmDomainType"))
        .findFirst()
        .orElseThrow()
        .click();

    var name =
        await()
            .until(
                ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector("#board-dialog input[name='name']")));
    name.sendKeys("sm-created-on-the-board");
    driver.findElement(By.id("board-dialog-create")).click();

    await()
        .until(
            d ->
                d
                    .findElements(By.cssSelector("#asset-graph .gc-node-entity .gc-node-title"))
                    .stream()
                    .anyMatch(title -> title.getText().equals("sm-created-on-the-board")));
    Assertions.assertTrue(
        entityService.list("SmDomainType", "").stream()
            .anyMatch(
                entity ->
                    "sm-created-on-the-board".equals(entity.getValues().get("name").asText())));
  }

  /**
   * The catalog shows what an asset materialises as: the entities mappings derived from its members
   * hang below it in the graph, which can leave them out, and are named in the list.
   */
  @Test
  void theCatalogShowsTheEntitiesMappedFromAnAsset() {
    ensureAssetModel();
    var entityTypeService = context.getBean(EntityTypeService.class);
    var mappingService = context.getBean(MappingService.class);
    var aggregateService =
        context.getBean(it.davidgreco.metacatalog.service.AggregateService.class);
    var empty =
        """
        { "type": "object", "properties": {} }""";
    entityTypeService.create(
        "SmFeedType", List.of("AggregateElement", "SmNamed"), Optional.empty(), empty);
    entityTypeService.create("SmTopicType", List.of("SmNamed"), Optional.empty(), empty);
    mappingService.create(
        "SmFeedType",
        "SmTopicType",
        """
        { "name": "#source.getValue('$.name').asText() + '-topic'" }""",
        List.of());

    var tableId =
        context
            .getBean(it.davidgreco.metacatalog.service.BulkLoaderService.class)
            .bulkAggregateCreation(
                new java.io.ByteArrayInputStream(
                    """
                    entityType: SmTableType
                    values: { name: sm-mapped }
                    parts:
                      - entityType: SmFeedType
                        values: { name: sm-feed }
                    """
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .getFirst();
    // The mapping updater derives the topic a moment after the load.
    await()
        .until(
            d ->
                aggregateService.assets().stream()
                    .filter(asset -> asset.entity().getId().equals(tableId))
                    .anyMatch(asset -> !asset.mapped().isEmpty()));

    driver.get(baseUrl + "/ui/assets?view=graph");
    var topic =
        await()
            .until(
                d ->
                    d.findElements(By.cssSelector("#asset-graph .gc-node-mapped")).stream()
                        .filter(node -> node.getText().contains("sm-feed-topic"))
                        .findFirst()
                        .orElse(null));
    Assertions.assertTrue(topic.getText().contains("from sm-feed"), topic.getText());
    Assertions.assertFalse(
        driver.findElements(By.cssSelector("#asset-graph .gc-edge-mapping")).isEmpty(),
        "a mapped entity must be joined to its asset");

    // Selecting it says where it comes from and leads to the mapping that made it.
    topic.click();
    var panel =
        await().until(ExpectedConditions.visibilityOfElementLocated(By.id("asset-panel-detail")));
    Assertions.assertTrue(panel.getText().contains("sm-mapped"), panel.getText());
    Assertions.assertTrue(
        panel.findElements(By.cssSelector("a")).stream()
            .anyMatch(
                link ->
                    link.getDomAttribute("href")
                        .endsWith("/ui/admin/mappings/new?source=SmFeedType&target=SmTopicType")));

    driver.findElement(By.id("asset-show-mapped")).click();
    await().until(d -> d.findElements(By.cssSelector("#asset-graph .gc-node-mapped")).isEmpty());

    driver.findElement(By.cssSelector(".view-switch [data-view='list']")).click();
    var row = driver.findElement(By.cssSelector("#asset-rows tr[data-asset-id='" + tableId + "']"));
    Assertions.assertTrue(row.getText().contains("sm-feed-topic"), row.getText());
  }

  /**
   * Editing an asset saves it as a whole and changes only what was edited: the member keeps its
   * identity, a new part is added, and the access policy comes back exactly as it was — a grant
   * with no {@code resources} must not gain an empty one on the way through the builder.
   */
  @Test
  void assetEditorSavesTheWholeAssetAndLeavesTheRestAlone() throws Exception {
    ensureAssetModel();
    var entityService = context.getBean(EntityService.class);
    var bulkLoader = context.getBean(it.davidgreco.metacatalog.service.BulkLoaderService.class);
    var json = new com.fasterxml.jackson.databind.ObjectMapper();

    var tableId =
        bulkLoader
            .bulkAggregateCreation(
                new java.io.ByteArrayInputStream(
                    """
                    entityType: SmTableType
                    values:
                      name: sm-editable
                      principals: [ { id: analysts, type: GROUP } ]
                      permissions: [ { name: READ } ]
                      grants: [ { principals: [analysts], permissions: [READ] } ]
                    parts:
                      - entityType: SmColumnType
                        values: { name: sm-id }
                    """
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .getFirst();
    var before = entityService.read(tableId).getValues();
    var columnId =
        entityService
            .linked(tableId, it.davidgreco.metacatalog.entity.RelationType.HAS_PART)
            .getFirst()
            .getId();

    driver.get(baseUrl + "/ui/assets/" + tableId + "/edit");
    await()
        .until(
            ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#aggregate-editor .mv-leaf .mv-input")));
    var nameInput =
        driver.findElements(By.cssSelector("#aggregate-editor .mv-leaf-label")).stream()
            .filter(label -> label.getText().trim().startsWith("name"))
            .findFirst()
            .orElseThrow()
            .findElement(By.cssSelector("input"));
    Assertions.assertEquals("sm-editable", nameInput.getDomProperty("value"));
    nameInput.clear();
    nameInput.sendKeys("sm-edited");
    // The statuses are a run's to write: shown, but not editable.
    Assertions.assertFalse(
        driver.findElements(By.cssSelector("#aggregate-editor .mv-readonly")).isEmpty());

    driver.findElements(By.cssSelector("#aggregate-editor button.btn")).stream()
        .filter(button -> button.getText().contains("Add SmColumnType"))
        .findFirst()
        .orElseThrow()
        .click();
    var newPartName =
        driver.findElements(By.cssSelector("#aggregate-editor .mv-leaf-label")).stream()
            .filter(label -> label.getText().trim().startsWith("name"))
            .map(label -> label.findElement(By.cssSelector("input")))
            .filter(input -> input.getDomProperty("value").isEmpty())
            .findFirst()
            .orElseThrow();
    newPartName.sendKeys("sm-amount");

    driver.findElement(By.cssSelector("#aggregate-form .form-actions button[type=submit]")).click();
    await()
        .until(
            d ->
                !d.findElements(By.cssSelector(".flash")).isEmpty()
                    || !d.findElements(By.cssSelector(".error")).isEmpty());
    var errors = driver.findElements(By.cssSelector(".error"));
    Assertions.assertTrue(
        errors.isEmpty(),
        "saving must succeed, got: " + errors.stream().map(e -> e.getText()).toList());
    Assertions.assertTrue(driver.getCurrentUrl().endsWith("/ui/assets/" + tableId));

    var expected =
        (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(before.toString());
    expected.put("name", "sm-edited");
    Assertions.assertEquals(
        expected, entityService.read(tableId).getValues(), "only the edited field may change");
    var parts =
        entityService.linked(tableId, it.davidgreco.metacatalog.entity.RelationType.HAS_PART);
    Assertions.assertEquals(2, parts.size());
    Assertions.assertTrue(
        parts.stream().anyMatch(part -> part.getId().equals(columnId)),
        "the existing part must be kept, not re-created");
    Assertions.assertTrue(
        parts.stream().anyMatch(part -> "sm-amount".equals(part.getValues().get("name").asText())));
  }

  /**
   * The model graph draws the built-in {@code Asset} trait with both of its parents, and says what
   * it inherits when it is selected.
   */
  @Test
  void modelGraphShowsWhatTheAssetTraitInherits() {
    driver.get(baseUrl + "/ui/admin/graph");
    var asset =
        await()
            .until(
                ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("#graph-canvas .gc-node[data-node-id='trait:Asset']")));
    Assertions.assertFalse(
        driver.findElements(By.cssSelector("#graph-canvas .gc-edge-mixes-in")).isEmpty(),
        "the mixed-in trait must be drawn as an edge of its own");

    asset.click();
    var panel =
        await()
            .until(ExpectedConditions.visibilityOfElementLocated(By.id("graph-panel-detail")))
            .getText();
    Assertions.assertTrue(panel.contains("Provisionable"), panel);
    Assertions.assertTrue(panel.contains("Authorizable"), panel);
    Assertions.assertTrue(panel.toLowerCase().contains("built-in"), panel);
  }
}
