package it.davidgreco.metacatalog.iceberg.rest;

import it.davidgreco.metacatalog.iceberg.IcebergCatalogProperties;
import it.davidgreco.metacatalog.iceberg.IcebergModel;
import it.davidgreco.metacatalog.iceberg.MetacatalogIcebergCatalog;
import java.util.List;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.Endpoint;
import org.apache.iceberg.rest.RESTCatalogProperties;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Iceberg REST catalog API (config + namespaces + tables), hand-written against Apache's
 * contract: every route parses the body with {@link IcebergJson}, dispatches to {@link
 * CatalogHandlers} — which owns the protocol semantics, including {@code UpdateRequirement}
 * validation and the server-side commit retry — and serializes the response back as a plain JSON
 * string. {@code {prefix}} is fixed by configuration and advertised to clients via {@code
 * /v1/config}.
 *
 * <p>A namespace path variable carries the identifier's levels separated by {@code %1F} (the unit
 * separator, the one character illegal inside a level). Spring URL-decodes path variables, so the
 * raw separator is split here with {@link RESTUtil#NAMESPACE_SPLITTER}.
 */
@RestController
@RequestMapping("/v1")
public class IcebergRestController {

  /** The table/view endpoints this server implements, advertised in {@code /v1/config}. */
  private static final List<Endpoint> ENDPOINTS =
      List.of(
          Endpoint.V1_LIST_NAMESPACES,
          Endpoint.V1_LOAD_NAMESPACE,
          Endpoint.V1_NAMESPACE_EXISTS,
          Endpoint.V1_CREATE_NAMESPACE,
          Endpoint.V1_UPDATE_NAMESPACE,
          Endpoint.V1_DELETE_NAMESPACE,
          Endpoint.V1_LIST_TABLES,
          Endpoint.V1_LOAD_TABLE,
          Endpoint.V1_TABLE_EXISTS,
          Endpoint.V1_CREATE_TABLE,
          Endpoint.V1_UPDATE_TABLE,
          Endpoint.V1_DELETE_TABLE,
          Endpoint.V1_RENAME_TABLE,
          Endpoint.V1_REGISTER_TABLE,
          Endpoint.V1_REPORT_METRICS);

  private final MetacatalogIcebergCatalog catalog;
  private final IcebergCatalogProperties properties;

  public IcebergRestController(
      MetacatalogIcebergCatalog catalog, IcebergCatalogProperties properties) {
    this.catalog = catalog;
    this.properties = properties;
  }

  // -------------------------------------------------------------------------------- config

  @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
  public String getConfig(@RequestParam(value = "warehouse", required = false) String warehouse) {
    // The endpoint list is advertised explicitly: without it clients assume the protocol's
    // default set, which includes multi-table transactions and views this server does not serve.
    var response =
        ConfigResponse.builder()
            .withOverride("prefix", properties.prefix())
            .withDefault("warehouse", properties.warehouse())
            .withEndpoints(ENDPOINTS)
            .build();
    return IcebergJson.toJson(response);
  }

  // ---------------------------------------------------------------------------- namespaces

  @GetMapping(value = "/{prefix}/namespaces", produces = MediaType.APPLICATION_JSON_VALUE)
  public String listNamespaces(
      @PathVariable("prefix") String prefix,
      @RequestParam(value = "parent", required = false) String parent,
      @RequestParam(value = "pageToken", required = false) String pageToken,
      @RequestParam(value = "pageSize", required = false) String pageSize) {
    checkPrefix(prefix);
    var parentNamespace =
        parent == null ? Namespace.empty() : RESTUtil.namespaceFromQueryParam(parent);
    // Clients send an (empty) pageToken even when not paginating; only an explicit pageSize
    // selects the paginated handler, which parses it.
    var response =
        pageSize == null
            ? CatalogHandlers.listNamespaces(catalog, parentNamespace)
            : CatalogHandlers.listNamespaces(catalog, parentNamespace, pageToken, pageSize);
    return IcebergJson.toJson(response);
  }

  @PostMapping(value = "/{prefix}/namespaces", produces = MediaType.APPLICATION_JSON_VALUE)
  public String createNamespace(@PathVariable("prefix") String prefix, @RequestBody String body) {
    checkPrefix(prefix);
    var request = IcebergJson.fromJson(body, CreateNamespaceRequest.class);
    return IcebergJson.toJson(CatalogHandlers.createNamespace(catalog, request));
  }

  @RequestMapping(value = "/{prefix}/namespaces/{namespace}", method = RequestMethod.HEAD)
  public ResponseEntity<Void> namespaceExists(
      @PathVariable("prefix") String prefix, @PathVariable("namespace") String namespace) {
    checkPrefix(prefix);
    CatalogHandlers.namespaceExists(catalog, namespace(namespace));
    return ResponseEntity.noContent().build();
  }

  @GetMapping(
      value = "/{prefix}/namespaces/{namespace}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String loadNamespace(
      @PathVariable("prefix") String prefix, @PathVariable("namespace") String namespace) {
    checkPrefix(prefix);
    return IcebergJson.toJson(CatalogHandlers.loadNamespace(catalog, namespace(namespace)));
  }

  @DeleteMapping("/{prefix}/namespaces/{namespace}")
  public ResponseEntity<Void> dropNamespace(
      @PathVariable("prefix") String prefix, @PathVariable("namespace") String namespace) {
    checkPrefix(prefix);
    CatalogHandlers.dropNamespace(catalog, namespace(namespace));
    return ResponseEntity.noContent().build();
  }

  @PostMapping(
      value = "/{prefix}/namespaces/{namespace}/properties",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String updateNamespaceProperties(
      @PathVariable("prefix") String prefix,
      @PathVariable("namespace") String namespace,
      @RequestBody String body) {
    checkPrefix(prefix);
    var request = IcebergJson.fromJson(body, UpdateNamespacePropertiesRequest.class);
    return IcebergJson.toJson(
        CatalogHandlers.updateNamespaceProperties(catalog, namespace(namespace), request));
  }

  // -------------------------------------------------------------------------------- tables

  @GetMapping(
      value = "/{prefix}/namespaces/{namespace}/tables",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String listTables(
      @PathVariable("prefix") String prefix,
      @PathVariable("namespace") String namespace,
      @RequestParam(value = "pageToken", required = false) String pageToken,
      @RequestParam(value = "pageSize", required = false) String pageSize) {
    checkPrefix(prefix);
    var ns = namespace(namespace);
    var response =
        pageSize == null
            ? CatalogHandlers.listTables(catalog, ns)
            : CatalogHandlers.listTables(catalog, ns, pageToken, pageSize);
    return IcebergJson.toJson(response);
  }

  @PostMapping(
      value = "/{prefix}/namespaces/{namespace}/tables",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String createTable(
      @PathVariable("prefix") String prefix,
      @PathVariable("namespace") String namespace,
      @RequestBody String body) {
    checkPrefix(prefix);
    var ns = namespace(namespace);
    var request = IcebergJson.fromJson(body, CreateTableRequest.class);
    var response =
        request.stageCreate()
            ? CatalogHandlers.stageTableCreate(catalog, ns, request)
            : CatalogHandlers.createTable(catalog, ns, request);
    return IcebergJson.toJson(response);
  }

  @PostMapping(
      value = "/{prefix}/namespaces/{namespace}/register",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String registerTable(
      @PathVariable("prefix") String prefix,
      @PathVariable("namespace") String namespace,
      @RequestBody String body) {
    checkPrefix(prefix);
    var request = IcebergJson.fromJson(body, RegisterTableRequest.class);
    return IcebergJson.toJson(
        CatalogHandlers.registerTable(catalog, namespace(namespace), request));
  }

  @RequestMapping(
      value = "/{prefix}/namespaces/{namespace}/tables/{table}",
      method = RequestMethod.HEAD)
  public ResponseEntity<Void> tableExists(
      @PathVariable("prefix") String prefix,
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String table) {
    checkPrefix(prefix);
    CatalogHandlers.tableExists(catalog, TableIdentifier.of(namespace(namespace), table));
    return ResponseEntity.noContent().build();
  }

  @GetMapping(
      value = "/{prefix}/namespaces/{namespace}/tables/{table}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String loadTable(
      @PathVariable("prefix") String prefix,
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String table,
      @RequestParam(value = "snapshots", required = false) String snapshots) {
    checkPrefix(prefix);
    var identifier = TableIdentifier.of(namespace(namespace), table);
    var snapshotMode =
        snapshots == null
            ? RESTCatalogProperties.SnapshotMode.ALL
            : RESTCatalogProperties.SnapshotMode.valueOf(
                snapshots.toUpperCase(java.util.Locale.ROOT));
    return IcebergJson.toJson(CatalogHandlers.loadTable(catalog, identifier, snapshotMode));
  }

  @PostMapping(
      value = "/{prefix}/namespaces/{namespace}/tables/{table}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String updateTable(
      @PathVariable("prefix") String prefix,
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String table,
      @RequestBody String body) {
    checkPrefix(prefix);
    var identifier = TableIdentifier.of(namespace(namespace), table);
    var request = IcebergJson.fromJson(body, UpdateTableRequest.class);
    return IcebergJson.toJson(CatalogHandlers.updateTable(catalog, identifier, request));
  }

  @DeleteMapping("/{prefix}/namespaces/{namespace}/tables/{table}")
  public ResponseEntity<Void> dropTable(
      @PathVariable("prefix") String prefix,
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String table,
      @RequestParam(value = "purgeRequested", required = false, defaultValue = "false")
          boolean purgeRequested) {
    checkPrefix(prefix);
    var identifier = TableIdentifier.of(namespace(namespace), table);
    if (purgeRequested) {
      CatalogHandlers.purgeTable(catalog, identifier);
    } else {
      CatalogHandlers.dropTable(catalog, identifier);
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{prefix}/tables/rename")
  public ResponseEntity<Void> renameTable(
      @PathVariable("prefix") String prefix, @RequestBody String body) {
    checkPrefix(prefix);
    var request = IcebergJson.fromJson(body, RenameTableRequest.class);
    CatalogHandlers.renameTable(catalog, request);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{prefix}/namespaces/{namespace}/tables/{table}/metrics")
  public ResponseEntity<Void> reportMetrics(
      @PathVariable("prefix") String prefix,
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String table,
      @RequestBody String body) {
    // Metrics reports are accepted and discarded; the body is deliberately not parsed so an
    // unknown report shape can never fail the client's post-commit path.
    checkPrefix(prefix);
    return ResponseEntity.noContent().build();
  }

  // ------------------------------------------------------------------------------ internals

  private Namespace namespace(String encoded) {
    // Spring already URL-decoded the path variable, so the levels arrive separated by the raw
    // unit separator. Split manually: RESTUtil.NAMESPACE_SPLITTER is typed against iceberg's
    // relocated guava, which is not a compile-time dependency.
    return Namespace.of(encoded.split(IcebergModel.KEY_SEPARATOR, -1));
  }

  private void checkPrefix(String prefix) {
    if (!properties.prefix().equals(prefix)) {
      throw new org.apache.iceberg.exceptions.NotFoundException(
          "Unknown prefix: %s (expected %s)", prefix, properties.prefix());
    }
  }

  @SuppressWarnings("unused")
  private ResponseEntity<String> json(String body, HttpStatus status) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
  }
}
