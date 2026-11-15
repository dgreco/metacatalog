package it.davidgreco.metacatalog.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Aggregate;
import it.davidgreco.metacatalog.openapi.model.EntityType;
import it.davidgreco.metacatalog.openapi.model.LinkEntityRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Assets half of the UI: the catalog of data platform assets, and everything done to one asset
 * as a whole.
 *
 * <p>An asset is an aggregate whose root type carries the built-in {@code Asset} trait, so nothing
 * here is a new operation. The catalog is {@code GET /asset}; an asset is read, created, updated
 * and deleted through the aggregate endpoints, provisioned and authorized through the procedures,
 * and a dependency between two assets is an ordinary {@code DEPENDS_ON} link between their roots.
 * What this controller adds is the shape: one place where those are offered for assets only, by
 * name, without the type-agnostic machinery the Administration half exposes.
 *
 * <p>Which assets may depend on which is decided by the model — a dependency is legal only when the
 * two types carry traits related by {@code DEPENDS_ON} — and the answer is taken from {@code GET
 * /asset/dependency-rule} rather than discovered by trying: the catalog pages need it up front, to
 * show where a dragged asset may be dropped.
 */
@Controller
@RequestMapping("/ui/assets")
public class AssetUiController {

  private static final String CATALOG = "redirect:/ui/assets";

  private final MetacatalogApiDelegate api;
  private final ObjectMapper jsonMapper;
  private final HtmlSafeJsonSerializer jsonSerializer;
  private final AggregateDocuments documents;
  private final AggregateProcedureLauncher procedures;

  public AssetUiController(
      MetacatalogApiDelegate api,
      ObjectMapper jsonMapper,
      @Qualifier("yamlMapper") ObjectMapper yamlMapper,
      HtmlSafeJsonSerializer jsonSerializer) {
    this.api = api;
    this.jsonMapper = jsonMapper;
    this.jsonSerializer = jsonSerializer;
    this.documents = new AggregateDocuments(jsonMapper, yamlMapper);
    this.procedures = new AggregateProcedureLauncher(api);
  }

  // --- the catalog -------------------------------------------------------------------------------

  /**
   * The catalog, as a list or as a graph of assets with their dependencies as edges. Both views are
   * rendered from the same model and the page switches between them without a reload; {@code view}
   * only says which one to start on, so that either can be linked to.
   */
  @GetMapping
  public String catalog(
      @RequestParam(defaultValue = "list") String view,
      @RequestParam(required = false) String type,
      Model model) {
    var catalog = readCatalog();
    model.addAttribute("catalog", catalog);
    model.addAttribute("catalogJson", jsonSerializer.write(catalog, "{}"));
    model.addAttribute("view", "graph".equals(view) ? "graph" : "list");
    model.addAttribute("selectedType", type);
    return "assets-catalog";
  }

  /** The catalog as JSON, for the page to refresh itself after a dependency changed. */
  @GetMapping("/data")
  @ResponseBody
  public AssetCatalogView catalogData() {
    return readCatalog();
  }

  /**
   * Makes an asset depend on something — what dropping an asset onto another node asks for. Answers
   * with the refreshed catalog, or with the reason the dependency was refused.
   *
   * @param source the id of the asset that is to depend
   * @param target the id of what it is to depend on: an asset, an aggregate or a plain entity
   */
  @PostMapping("/dependencies")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> link(
      @RequestParam String source, @RequestParam String target) {
    return dependencyAnswer(() -> addDependency(readCatalog(), source, target));
  }

  /** Removes a dependency of an asset; answers like {@link #link}. */
  @PostMapping("/dependencies/delete")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> unlink(
      @RequestParam String source, @RequestParam String target) {
    return dependencyAnswer(() -> api.unlinkEntity(source, "DEPENDS_ON", target));
  }

  private ResponseEntity<Map<String, Object>> dependencyAnswer(Runnable change) {
    var body = new LinkedHashMap<String, Object>();
    try {
      change.run();
      body.put("catalog", readCatalog());
      return ResponseEntity.ok(body);
    } catch (RuntimeException e) {
      body.put("error", e.getMessage());
      return ResponseEntity.badRequest().body(body);
    }
  }

  /**
   * Makes an asset depend on an asset, an aggregate or a plain entity, saying why when the model
   * does not allow it.
   *
   * <p>The API is still what decides — it is asked, and whatever it refuses is reported. The check
   * made first only exists for its message: the API answers an unsanctioned link with "Relationship
   * is not legit", which is true and tells an asset manager nothing about what to do next.
   */
  private void addDependency(AssetCatalogView catalog, String source, String target) {
    var from = catalog.row(source);
    if (from == null)
      throw new IllegalArgumentException("Only an asset can be made to depend on something.");
    var to = catalog.node(target);
    if (to == null)
      throw new IllegalArgumentException(
          "An asset can depend on another asset, an aggregate or an entity the model allows it"
              + " to; "
              + target
              + " is none of those.");
    if (!catalog.allows(from.entityType(), to.entityType()))
      throw new IllegalArgumentException(
          "A "
              + from.entityType()
              + " asset cannot depend on a "
              + to.entityType()
              + " "
              + to.kind()
              + ": no DEPENDS_ON relationship is declared between traits those two types carry."
              + " Declare one under Administration to allow it.");
    var request = new LinkEntityRequest();
    request.setSourceEntityId(source);
    request.setRelationshipTypeName("DEPENDS_ON");
    request.setTargetEntityId(target);
    api.linkEntity(request);
  }

  /**
   * Creates a new node of the dependency graph from the values typed into the board's dialog: an
   * asset, or something an asset may depend on. Answers with the refreshed catalog, or with the
   * reason the creation was refused.
   *
   * <p>Only the root is created. An asset or an aggregate made this way has no parts yet; they are
   * added from its own page, where the whole tree can be edited. The board is where a dependency's
   * other end is brought into being without leaving it, not a second editor.
   *
   * @param type the entity type of the new node — an asset type, or a type some asset may depend
   *     on; any other is refused, since nothing else belongs on the board
   * @param values the new entity's values, as JSON
   */
  @PostMapping("/entities")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> createNode(
      @RequestParam String type, @RequestParam String values) {
    return dependencyAnswer(
        () -> {
          if (!readCatalog().canCreate(type))
            throw new IllegalArgumentException(
                type + " is neither an asset type nor a type an asset may depend on.");
          var entity = new it.davidgreco.metacatalog.openapi.model.Entity();
          entity.setEntityType(type);
          entity.setValues(values);
          api.createEntity(entity);
        });
  }

  /**
   * Deletes a node of the dependency graph — what Delete in a node's menu asks for. Answers with
   * the refreshed catalog, or with the reason the deletion was refused.
   *
   * <p>An asset or an aggregate is deleted as one: its parts and the entities derived from them go
   * with it. A plain entity is deleted alone. The API decides whether either may go — something
   * still depending on it is the usual reason it may not — and what it refuses is reported.
   *
   * <p>Only what stands on the board in its own right can be deleted from it. A part is content of
   * its asset and a mapped entity follows what it was derived from: deleting one here would change
   * an asset behind its back, without the change becoming a version of it.
   *
   * @param id the id of the node: an asset, an aggregate or a plain entity
   */
  @PostMapping("/entities/delete")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> deleteNode(@RequestParam String id) {
    return dependencyAnswer(
        () -> {
          var catalog = readCatalog();
          var node = catalog.node(id);
          if (node == null) {
            var owner = catalog.owner(id);
            throw new IllegalArgumentException(
                owner == null
                    ? id + " is not on the board."
                    : "It belongs to the asset «"
                        + owner.name()
                        + "»: a part is removed by editing the asset, and a mapped entity goes"
                        + " when what it was derived from does.");
          }
          if (AssetCatalogView.ENTITY.equals(node.kind())) api.deleteEntity(id);
          else api.deleteAggregate(id);
        });
  }

  // --- creating ----------------------------------------------------------------------------------

  /** The builder for a new asset: pick an asset type, fill in the tree its schema describes. */
  @GetMapping("/new")
  public String newForm(@RequestParam(required = false) String type, Model model) {
    if (!model.containsAttribute("aggregateForm")) {
      var form = new AggregateForm();
      form.setRootType(type);
      model.addAttribute("aggregateForm", form);
    }
    populateForm(model, type);
    return "asset-form";
  }

  @PostMapping
  public String create(
      @ModelAttribute("aggregateForm") AggregateForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      api.createAggregateAsYaml(new ByteArrayResource(documents.toYaml(form.getDocument())));
      redirectAttributes.addFlashAttribute("message", "Asset created.");
      return CATALOG;
    } catch (RuntimeException | IOException e) {
      model.addAttribute("aggregateForm", form);
      model.addAttribute("error", e.getMessage());
      populateForm(model, form.getRootType());
      return "asset-form";
    }
  }

  /** Serves the combined schema so the builder can switch asset type without a full reload. */
  @GetMapping("/schema")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> schema(@RequestParam String rootType) {
    return api.getAggregateSchema(rootType);
  }

  /** Renders the document the builder holds as the YAML that would be sent. */
  @PostMapping(value = "/yaml-preview", produces = "text/plain")
  @ResponseBody
  public ResponseEntity<String> yamlPreview(@RequestBody String document) {
    try {
      return ResponseEntity.ok(documents.preview(document));
    } catch (RuntimeException | IOException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    }
  }

  @GetMapping("/upload")
  public String uploadForm() {
    return "asset-upload";
  }

  /**
   * Creates assets from an uploaded or pasted YAML file — one or more documents, each an aggregate.
   * A file downloaded from an asset's page is accepted as it is, and creates a copy.
   */
  @PostMapping("/upload")
  public String upload(
      @RequestParam(value = "file", required = false) MultipartFile file,
      @RequestParam(value = "yamlText", required = false) String yamlText,
      Model model,
      RedirectAttributes redirectAttributes) {
    try (InputStream in = yamlInput(file, yamlText)) {
      if (in == null) {
        model.addAttribute("error", "Provide a YAML file or paste YAML text.");
        return "asset-upload";
      }
      api.createAggregateAsYaml(new InputStreamResource(in));
      redirectAttributes.addFlashAttribute("message", "Assets created from the uploaded YAML.");
      return CATALOG;
    } catch (RuntimeException | IOException e) {
      model.addAttribute("error", e.getMessage());
      model.addAttribute("yamlText", yamlText);
      return "asset-upload";
    }
  }

  // --- one asset ---------------------------------------------------------------------------------

  @GetMapping("/{id}")
  public String view(@PathVariable String id, Model model, RedirectAttributes redirectAttributes) {
    try {
      var catalog = readCatalog();
      var asset = requireAsset(catalog, id);
      // Read twice on purpose: with the derived entities, to show the resources the procedures
      // act on, and without, because that is the only thing telling a part from a derived entity.
      var withDerived = (Aggregate) api.getAggregate(id, Boolean.TRUE).getBody();
      var authored = (Aggregate) api.getAggregate(id, Boolean.FALSE).getBody();
      var names = new LinkedHashMap<String, String>();
      catalog.assets().forEach(row -> names.put(row.id(), row.name()));
      catalog.targets().forEach(target -> names.put(target.id(), target.name()));
      var root = AssetDetailView.tree(withDerived, authored, names);
      var rootValues = AssetCatalogView.parse(withDerived.getValues());

      var linked = asset.dependsOn().stream().map(AssetCatalogView.Ref::id).toList();
      var candidates =
          java.util.stream.Stream.concat(
                  catalog.assets().stream().map(AssetCatalogView.Row::ref),
                  catalog.targets().stream())
              .filter(node -> !node.id().equals(id) && !linked.contains(node.id()))
              .filter(node -> catalog.allows(asset.entityType(), node.entityType()))
              .toList();

      model.addAttribute(
          "detail",
          new AssetDetailView(
              asset,
              AssetCatalogView.text(rootValues, "provisioningResult"),
              AssetCatalogView.text(rootValues, "authorizationResult"),
              root,
              candidates,
              AssetDetailView.mappingsOf(
                  api.listMappings().getBody(), AssetDetailView.memberTypes(root)),
              readAccess(id, AssetDetailView.names(root)),
              // Every action applies: an asset is by definition a provisionable, authorizable
              // aggregate root, which is the whole reason the trait exists.
              List.copyOf(EnumSet.allOf(AggregateAction.class))));
      model.addAttribute("yaml", documents.read(readYaml(id, false)).yaml());
      return "asset-view";
    } catch (RuntimeException | IOException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return CATALOG;
    }
  }

  /**
   * The builder on an existing asset. The document it starts from is the asset as {@code GET
   * /aggregate/{id}/yaml} serves it without derived entities, with each node's dependency ids moved
   * to the {@code dependsOn} field the builder edits — so that what the form shows is exactly what
   * the asset will depend on once saved.
   */
  @GetMapping("/{id}/edit")
  public String editForm(
      @PathVariable String id, Model model, RedirectAttributes redirectAttributes) {
    try {
      var asset = requireAsset(readCatalog(), id);
      if (!model.containsAttribute("aggregateForm")) {
        var form = new AggregateForm();
        form.setRootType(asset.entityType());
        form.setDocument(
            jsonMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(editable(documents.read(readYaml(id, false)).tree())));
        model.addAttribute("aggregateForm", form);
      }
      model.addAttribute("asset", asset);
      populateForm(model, asset.entityType());
      return "asset-form";
    } catch (RuntimeException | IOException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return CATALOG;
    }
  }

  /** Saves the builder's document over the asset, as a whole and in one transaction. */
  @PostMapping("/{id}")
  public String update(
      @PathVariable String id,
      @ModelAttribute("aggregateForm") AggregateForm form,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      api.updateAggregateAsYaml(id, new ByteArrayResource(documents.toYaml(form.getDocument())));
      redirectAttributes.addFlashAttribute("message", "Asset updated.");
      return assetPage(id);
    } catch (RuntimeException | IOException e) {
      model.addAttribute("aggregateForm", form);
      model.addAttribute("error", e.getMessage());
      try {
        model.addAttribute("asset", requireAsset(readCatalog(), id));
      } catch (RuntimeException gone) {
        redirectAttributes.addFlashAttribute("error", e.getMessage());
        return CATALOG;
      }
      populateForm(model, form.getRootType());
      return "asset-form";
    }
  }

  /**
   * Downloads the asset as a YAML file. The file can be edited and uploaded back onto the asset, or
   * uploaded as a new asset to copy it.
   *
   * @param derived whether to include the entities derived by mappings; they are informative only —
   *     an upload ignores them
   */
  @GetMapping("/{id}/yaml")
  public ResponseEntity<Resource> download(
      @PathVariable String id, @RequestParam(defaultValue = "false") boolean derived) {
    var asset = readCatalog().row(id);
    // A download has no page to flash an error onto: an id that is not an asset is simply absent.
    if (asset == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/yaml"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(fileName(asset.name()), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(readYaml(id, derived));
  }

  /** Replaces the asset with an uploaded or pasted YAML document, as a whole. */
  @PostMapping("/{id}/yaml")
  public String replace(
      @PathVariable String id,
      @RequestParam(value = "file", required = false) MultipartFile file,
      @RequestParam(value = "yamlText", required = false) String yamlText,
      RedirectAttributes redirectAttributes) {
    try (InputStream in = yamlInput(file, yamlText)) {
      if (in == null) {
        redirectAttributes.addFlashAttribute("error", "Provide a YAML file or paste YAML text.");
      } else {
        api.updateAggregateAsYaml(id, new InputStreamResource(in));
        redirectAttributes.addFlashAttribute("message", "Asset updated from the uploaded YAML.");
      }
    } catch (RuntimeException | IOException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return assetPage(id);
  }

  /** Makes this asset depend on another, from the picker on its page. */
  @PostMapping("/{id}/dependencies")
  public String addDependency(
      @PathVariable String id, @RequestParam String target, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> addDependency(readCatalog(), id, target),
        "Dependency added.",
        "/ui/assets/" + id,
        redirectAttributes);
  }

  @PostMapping("/{id}/dependencies/delete")
  public String removeDependency(
      @PathVariable String id, @RequestParam String target, RedirectAttributes redirectAttributes) {
    return UiControllerHelper.flashAndRedirect(
        () -> api.unlinkEntity(id, "DEPENDS_ON", target),
        "Dependency removed.",
        "/ui/assets/" + id,
        redirectAttributes);
  }

  /**
   * Runs one of the four procedures on the asset and comes back to its page, which shows the run's
   * progress and reloads with the statuses it leaves (see {@link AggregateProcedureLauncher}).
   */
  /**
   * Launches one of the four runs on an asset and goes back to where it was asked for, which shows
   * its progress and reloads when it is over.
   *
   * @param id the asset
   * @param action the run, as the last segment of the address
   * @param from {@code graph} when the run was launched from the dependency graph's menu, which is
   *     then where the answer goes; anything else, or nothing, returns to the asset's page. A name
   *     rather than an address, so that the redirect cannot be pointed elsewhere
   */
  @PostMapping("/{id}/procedure/{action}")
  public String runProcedure(
      @PathVariable String id,
      @PathVariable String action,
      @RequestParam(required = false) String from,
      RedirectAttributes redirectAttributes) {
    var procedure = AggregateProcedureLauncher.procedure(action);
    if (procedure.isEmpty())
      redirectAttributes.addFlashAttribute("error", "Unknown asset procedure: " + action);
    else procedures.launch(redirectAttributes, procedure.get(), id);
    return "graph".equals(from) ? CATALOG + "?view=graph" : assetPage(id);
  }

  @PostMapping("/{id}/delete")
  public String delete(@PathVariable String id, RedirectAttributes redirectAttributes) {
    try {
      api.deleteAggregate(id);
      redirectAttributes.addFlashAttribute("message", "Asset deleted.");
      return CATALOG;
    } catch (RuntimeException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return assetPage(id);
    }
  }

  // --- shared ------------------------------------------------------------------------------------

  private static String assetPage(String id) {
    return "redirect:/ui/assets/" + id;
  }

  @SuppressWarnings("unchecked")
  private AssetCatalogView readCatalog() {
    var schemas = new LinkedHashMap<String, String>();
    ((List<EntityType>) api.listEntityTypes().getBody())
        .forEach(type -> schemas.put(type.getName(), type.getSchema()));
    return AssetCatalogView.from(
        api.listAssets().getBody(),
        api.listAssetDependencyTargets().getBody(),
        api.listAssetDependencyRules().getBody(),
        assetTypeNames(),
        schemas);
  }

  @SuppressWarnings("unchecked")
  private List<String> assetTypeNames() {
    return ((List<EntityType>) api.listAssetTypes().getBody())
        .stream().map(EntityType::getName).toList();
  }

  /**
   * The catalog row of an asset, refusing an id that is not one. Without the check these pages
   * would happily show and edit any aggregate, which belongs to the Administration half.
   */
  private static AssetCatalogView.Row requireAsset(AssetCatalogView catalog, String id) {
    var asset = catalog.row(id);
    if (asset == null) throw new IllegalArgumentException("There is no asset with id " + id + ".");
    return asset;
  }

  private Resource readYaml(String id, boolean derived) {
    return (Resource) api.getAggregateAsYaml(id, derived).getBody();
  }

  /**
   * The access the asset declares and holds, or {@code null} when it cannot be read: the page then
   * leaves the section out rather than failing for something it only reports.
   */
  private AssetDetailView.Access readAccess(String id, Map<String, String> names) {
    try {
      var access = api.getAggregateAccess(id).getBody();
      return access == null ? null : AssetDetailView.access(access, names);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Adds the asset-type choices and, when one is selected, its combined schema. A schema that
   * cannot be built surfaces as a form error rather than a 500.
   */
  private void populateForm(Model model, String rootType) {
    model.addAttribute("rootTypes", assetTypeNames());
    model.addAttribute("selectedRootType", rootType);
    if (rootType != null && !rootType.isBlank()) {
      try {
        model.addAttribute(
            "aggregateSchema", jsonSerializer.write(api.getAggregateSchema(rootType).getBody()));
      } catch (RuntimeException e) {
        // Never over an error already there: this runs again after a refused save, and the reason
        // the save was refused is the one the user has to read.
        if (!model.containsAttribute("error")) model.addAttribute("error", e.getMessage());
      }
    }
  }

  /**
   * Rewrites a served aggregate document into the one the builder edits: each node's {@code
   * dependencies} — the ids of what it depends on — become its {@code dependsOn}, the field the
   * builder shows. The update reads either, so this changes what the form displays, not what saving
   * it means.
   */
  private static JsonNode editable(JsonNode node) {
    if (node instanceof ObjectNode object) {
      var dependencies = object.remove("dependencies");
      if (dependencies != null && !object.has("dependsOn")) object.set("dependsOn", dependencies);
      var parts = object.get("parts");
      if (parts != null && parts.isArray()) parts.forEach(AssetUiController::editable);
    }
    return node;
  }

  private static InputStream yamlInput(MultipartFile file, String yamlText) throws IOException {
    if (file != null && !file.isEmpty()) return file.getInputStream();
    if (yamlText != null && !yamlText.isBlank())
      return new ByteArrayInputStream(yamlText.getBytes(StandardCharsets.UTF_8));
    return null;
  }

  /** A file name from an asset's name: whatever is not plainly safe in one becomes a dash. */
  private static String fileName(String assetName) {
    var safe = assetName.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
    return (safe.isEmpty() ? "asset" : safe) + ".yaml";
  }
}
