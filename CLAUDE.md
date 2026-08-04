# CLAUDE.md

Guidance for Claude Code (and human developers) working in this repository.

## What this project is

**Meta Catalog** is a metadata management system built with Spring Boot. It manages **entities**,
**entity types**, **traits**, and the **relationships** between them, with JSON-Schema-based
validation of entity attributes, graph-based relationship traversal, an asynchronous task/procedure
engine, and optional ontology-based data access via Ontop.

- **Group / artifact:** `it.davidgreco:metacatalog` — version `0.0.1-SNAPSHOT`
- **Packaging:** Maven multi-module (`pom` parent + 4 modules)
- **Java:** 25 · **Spring Boot:** 4.0.1 · **PostgreSQL** (JDBC 42.7.x) · **Maven:** 3.9.9+ (enforced)
- **Persistence:** Spring Data JPA / Hibernate, JSON stored in `jsonb` columns via Hypersistence Utils
- **Schema migrations:** Flyway (auto-run on startup)

## Module layout

The reactor builds modules in this order (see root `pom.xml` `<modules>`):

| Module | Purpose |
| --- | --- |
| `metacatalog-core` | Domain model (JPA entities), repositories, services, task engine, JSON utilities, DB migrations, Ontop mapping files. The heart of the system. |
| `metacatalog-functions` | Pluggable **procedures** that run over entities — notably the `provisioning` package that provisions aggregate entities in dependency order. Depends on core. |
| `metacatalog-openapi` | The OpenAPI contract (`interface-specification.yaml`), code generated from it (spring server + client), and `MetacatalogApiImpl` (the delegate implementation wiring the generated controllers to core services). |
| `metacatalog-application` | The deployable Spring Boot app: `Application` main class, web/OpenAPI config, and profile-specific YAML (`application.yaml`, `application-docker.yaml`, `application-kubernetes.yaml`). |
| `metacatalog-security` | Pluggable authentication for the REST API. Selectable via `application.config.security.auth-mode`: `none` (default, no auth), `basic` (HTTP Basic with users from config), `oauth2` (JWT resource server, e.g. Keycloak) or `ldap` (LDAP bind). Depends on Spring Security 7.x. |
| `metacatalog-ui` | Server-side rendered admin UI under `/ui/**` (Thymeleaf templates + plain JS, no build step). Drives the REST API through `MetacatalogApiDelegate`; does not depend on `metacatalog-core`. See [UI](#ui-metacatalog-ui). |
| `metacatalog-sparql` | Embedded Ontop SPARQL endpoint over the metacatalog DB. See [Ontop](#ontop-embedded-sparql-endpoint). |

Package root everywhere: `it.davidgreco.metacatalog`.

## Domain model (core concepts)

Located in `metacatalog-core/.../entity`:

- **`EntityType`** (`entity_type` table) — a type definition. Holds a `baseSchema` (JSON Schema) and
  an optional computed `derivedSchema`. Supports single inheritance via `father` and can be
  associated with multiple `Trait`s (`type_traits` join table). Implements `Type<EntityType>`.
- **`Trait`** (`trait` table) — a reusable characteristic contributing schema properties to the
  entity types that use it. Also supports inheritance via `father` and has `baseSchema` /
  `derivedSchema`. Implements `Type<Trait>`.
- **`Entity`** (`entity` table) — a concrete instance of an `EntityType`. Its `values` are a
  `jsonb` JSON object validated against the type's effective (derived) schema.
- **`RelationType`** enum — the relationship vocabulary, each with an `inverse()`:
  `DEPENDS_ON ↔ IS_REQUIRED_BY`, `HAS_PART ↔ IS_PART_OF`, `MAPPED_TO ↔ IS_MAPPED_BY`.
- **Relationship entities** — `EntityRelationship`, `TraitRelationship`,
  `MappingEntityRelationship`, `MappingEntityTypeRelationship`, plus the `CommonRelationship` base.
- **`EntityLifeCycleEvent`** (`entity_lifecycle_event` table) — audit/lifecycle record (e.g. source
  created/updated) that drives asynchronous mapping.
- **`AdvisoryLockManager`** — PostgreSQL advisory locks used to serialize scheduled work across
  application instances.

### Built-in traits (seeded by migration V2)

`Aggregate`, `AggregateElement`, `Provisionable`, `ProvisionableResource`. These underpin the
aggregate model (`AggregateService`) and the provisioning procedure.

## Services (core)

Located in `metacatalog-core/.../service`:

- CRUD + validation services: `EntityService`, `EntityTypeService`, `TraitService`,
  `MappingService`, `MappingUpdaterService`, `BulkLoaderService`, `AggregateService`.
- Shared contracts / helpers: `CommonService`, `CommonTypeService`, and error types
  `ServiceError` (checked), `ServiceRuntimeError`, `SchemaValidationError`.
- **`AggregateSchemaService`** — derives the combined JSON Schema of a whole aggregate tree, so an
  aggregate can be authored as a single document. See [Aggregate schemas](#aggregate-schemas).
- **Task engine:** `TaskManager`, `Task`, `TaskFactory` — registers typed task factories, builds
  dependency graphs with **JGraphT**, detects cycles (`CycleDetector`), and executes schedules
  asynchronously (results cached with Caffeine). `AsyncTaskExecutor`-backed.
- **`MappingUpdaterService`** — `@Scheduled` job that reacts to `EntityLifeCycleEvent`s to create /
  update mapped entities. Gated by `application.config.automaticEntitiesMapping` and guarded by an
  advisory lock so only one instance runs at a time.

### Aggregate schemas

`AggregateSchemaService` answers two questions the UI needs in order to author an aggregate:

- **Which types can start one?** An *aggregate root type* has a `HAS_PART` relationship towards at
  least one other type but is never itself a part. Note that `HAS_PART` is **not** stored between
  entity types: composition is declared between *traits*, and a type participates by mixing them in
  (the same rule `ServiceUtils.checkRelIsLegit` enforces when linking two instances). The service
  projects those trait relationships onto the types carrying them to obtain the type-level graph.
- **What does the whole tree look like?** `aggregateSchema(rootTypeName)` returns one self-contained
  schema for everything reachable from the root, shaped like the aggregate YAML `BulkLoaderService`
  already accepts (`entityType`, `values`, `ref`, `dependsOn`, `parts`).

Two invariants worth preserving when touching this:

- Each node's `values` come from the type's **`derivedSchema`**, never `baseSchema` (and not via the
  `getSchema()` accessor, which falls back to the base schema). A type composed mostly of traits
  typically has an empty base schema, so using it would render a form missing the very fields entity
  creation validates against.
- Reachable types are emitted under `$defs` and containment uses `$ref`. The trait model permits a
  type to transitively contain its own kind, so inlining would not terminate.

Mapping-target types are excluded: the mapping engine derives their instances and
`EntityService.create` refuses to create them directly.

### Procedures (functions module)

`AbstractEntityProcedure` / `EntityProcedure` / `EntityFunction` / `ProcedureExecutor` (in core)
define the procedure abstraction. `metacatalog-functions` provides concrete ones — chiefly
`ProvisioningProcedure` + `ProvisioningTask`, which read an aggregate, build a dependency graph of
`ProvisionableResource` entities from mapping relationships, detect cycles, and schedule provisioning
in dependency order.

## REST API

Contract-first via OpenAPI. The spec lives at
`metacatalog-openapi/src/main/resources/static/api/interface-specification.yaml` and is the source
of truth — **edit the spec, then regenerate**, don't hand-edit generated controllers/models.

- Generator: `openapi-generator-maven-plugin` (spring server + a client), delegate pattern.
  Generated code lands under `it.davidgreco.metacatalog.openapi.controller` / `.model`.
- `MetacatalogApiImpl` is the delegate implementation binding endpoints to core services.
- Base path prefix: `/metacatalog/v1/...`. Resource groups: `trait`, `entity-type`, `entity`,
  `aggregate`, `bulk-creation`, plus `.../link/...` sub-resources for relationships.
- List-all reads (used by the catalog graph, and by any client needing a full snapshot):
  `GET /trait/relationships`, `/entity/relationships`, `/mapping/entity-relationships`,
  `/trait/versions`, `/entity-type/versions`. `GET /entity` lists every entity when
  `entityTypeName` is omitted.
- Aggregate authoring: `GET /aggregate/root-type` lists the aggregate root types and
  `GET /aggregate/root-type/{name}/schema` returns the combined schema for one (see
  [Aggregate schemas](#aggregate-schemas)). A document written against that schema is accepted by
  `POST /aggregate/yaml`.

When running:
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Spec: `http://localhost:8080/api/interface-specification.yaml`
- Actuator: `/actuator/health`, `/actuator/info`, `/actuator/metrics`

## UI (metacatalog-ui)

A server-side rendered admin UI mounted at `/ui`, built with Thymeleaf templates
(`src/main/resources/templates`) and plain ES5 JavaScript (`src/main/resources/static/ui/js`) — no
npm, bundler, or front-end build step.

**Everything goes through the REST API.** Controllers call `MetacatalogApiDelegate`, never the core
services, so the UI exercises the same contract external clients do. This is enforced by the build,
not by convention: `metacatalog-ui/pom.xml` deliberately does **not** depend on `metacatalog-core`,
so reaching into the service layer fails to compile. If a page needs data the API doesn't expose,
add the endpoint to the spec first — don't add the core dependency back.

Pages: dashboard (`/ui`), trait / entity-type / mapping / trait-link forms, version history, the
catalog graph (`/ui/graph`), YAML bulk upload (`/ui/bulk`), instances (`/ui/instances`), and
aggregate authoring (`/ui/aggregates/new`).

The two schema-driven editors are the substantial pieces:

- `instance-values-form.js` builds a typed form for a single entity from its type's schema.
- `aggregate-form.js` builds a **tree** from the combined aggregate schema, resolving `$ref` against
  `$defs` and creating child parts only when the user adds one — which, together with the
  `$defs`/`$ref` indirection, is what keeps a recursive composition from expanding forever. It
  submits JSON; `AggregateUiController` converts it to the YAML `POST /aggregate/yaml` consumes.

Both offer a Builder / Raw JSON tab pair and fall back to Raw JSON for anything they cannot render.

JSON embedded into a page must go through `HtmlSafeJsonSerializer` (`<script type="application/json"
th:utext="...">`), which escapes `<`, `>`, `&` and `/` so entity values cannot break out of the
script block.

Tests use standalone MockMvc with a mocked delegate — no Spring context or database
(`UiControllerTest`, `AggregateUiControllerTest`).

## Security (metacatalog-security)

Pluggable authentication for the REST API. The active mechanism is selected via
`application.config.security.auth-mode` and wired by `@ConditionalOnProperty` so exactly one
`SecurityFilterChain` is registered. `metacatalog-application` depends on this module; the
config classes live in package `it.davidgreco.metacatalog.security` and are picked up by the
application's component scan.

| `auth-mode` | What it does | Required sub-config |
| --- | --- | --- |
| `none` (default) | Security disabled: every request is permitted. Intended for local dev / tests. | — |
| `basic` | HTTP Basic with users defined statically in config. Passwords use the DelegatingPasswordEncoder scheme (`{noop}secret`, `{bcrypt}$2a$...`). | `application.config.security.basic.users[]` |
| `oauth2` | Spring Security OAuth2 resource server validating JWT bearer tokens. Resolves the JWK set from `jwk-set-uri` or `issuer-uri` (e.g. Keycloak's `/protocol/openid-connect/certs`). When `client-id` (and `client-secret`) is additionally set, browser SSO is enabled via the OIDC authorization-code flow — the UI redirects to the IdP, and after login the session is reused by the API chain (Swagger "Try it out" works without re-authentication, mirroring `basic`/`ldap`). | `application.config.security.oauth2.{jwk-set-uri \| issuer-uri}` + optional `{client-id, client-secret}` for SSO |
| `ldap` | LDAP bind authentication. Locates the user either by `user-dn-pattern` or by `(user-search-base, user-search-filter)`. Optional `manager-dn` / `manager-password` for non-anonymous search. | `application.config.security.ldap.url` + DN pattern or search filter |

URL authorization (shared by all non-`none` modes, see `SecurityFilterChainCustomizer`):
- `/metacatalog/v1/**` and `/sparql/query` require authentication
- `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/api/interface-specification.yaml`, `/javadoc/**` are public
- Everything else (e.g. the server-side rendered UI under `/ui/**`, the SPARQL query UI at `/sparql`) is public
- CSRF is disabled on the API chain. Sessions are `STATELESS` for `oauth2` without SSO (JWT
  bearer tokens only) and `NEVER` for `basic`/`ldap` and `oauth2` with SSO: an existing UI session
  is reused so the Swagger UI "Try it out" XHRs and Yasgui SPARQL XHRs are authenticated without
  re-authentication, but no session is ever created on the API chain — programmatic clients stay
  effectively stateless

Only authentication is enforced at the API level — no role-based authorization. Roles configured
under `basic.users[].roles` are still attached to the `Authentication` principal and can be used
for finer-grained checks later.

## Configuration

Custom properties bind under the `application.config` prefix into
`CoreConfigProperties` (a record):

- `automaticEntitiesMapping` (boolean) — enable the scheduled mapping updater
- `updateMappedEntitiesSchedulingInterval` (Duration)
- `entityPathResolutionMaxAttempts` (int)
- `taskScheduleCacheMaxSize` (int, default 100) — `TaskManager` schedule-result cache capacity
- `taskScheduleCacheExpireAfterWrite` (Duration, default 1h) — `TaskManager` schedule-result cache TTL

Security properties bind under `application.config.security` into `SecurityConfigProperties`
(see the [Security](#security-metacatalog-security) section). Defaults live in `application.yaml`;
`docker` and `kubernetes` profiles override datasource wiring.
The app enables `@EnableScheduling`, `@EnableTransactionManagement`, `@EnableConfigurationProperties`.

## Build & common commands

Run from the repo root unless noted.

```bash
mvn clean install            # build all modules + run tests
mvn test                     # unit tests
mvn verify                   # tests incl. integration (Testcontainers → needs Docker)
mvn spotless:apply           # format (Google Java Format) — REQUIRED before committing
mvn spotless:check           # verify formatting (runs in the validate phase / CI)
mvn spring-boot:run -pl metacatalog-application     # run the app locally on :8080
mvn spring-boot:build-image -pl metacatalog-application -DskipTests   # buildpack image
```

Quality / security plugins (bound in the reactor):

```bash
mvn dependency-check:check   # OWASP — fails build on CVSS >= 9 (suppressions/suppression.xml)
mvn licensescan:audit        # fails on forbidden licenses (GPL v2.0)
```

### Testing notes

- Integration tests use **Testcontainers** with PostgreSQL and therefore **require a running Docker
  daemon**. In CI (GitLab) this runs against Docker-in-Docker; the Postgres container is a shared
  singleton (not stopped per test class) and the JDBC URL is resolved dynamically for DinD.
- Test data lives under each module's `src/test/resources` (e.g. `bulk/`, `jsons/`).
- Coverage via JaCoCo (report generated in the `test` phase).

## CI / Deployment

- **CI:** `.gitlab-ci.yml` — `build` stage on `maven:3-eclipse-temurin-25` with a `docker:dind`
  service: runs `mvn clean install spotless:check test`, then on `main`/`master`/`develop` builds
  and pushes a Docker image (tags: short SHA, `latest`, and version on main).
- **Docker Compose:** `docker-compose.yml` — Postgres 18.1 (`:5432`) + app (`:8080`, `docker` profile).
- **Kubernetes:** `k8s/` — app Deployment/Service/Ingress/ConfigMap/ServiceAccount plus a
  CloudNativePG (`cnpg`) Postgres cluster and scheduled backup; `kustomization.yaml` ties it together.
  `*.template` secret files must be filled in (DB credentials, registry pull secret).

## Ontop (embedded SPARQL endpoint)

The `metacatalog-sparql` module embeds Ontop 5.5.0 as a virtual knowledge graph over the
metacatalog Postgres DB, exposed on the same port as the application (no separate process):

- `GET /sparql` — Yasgui query UI (server-side rendered Thymeleaf template, loads Yasgui from a CDN).
- `/sparql/query` — SPARQL 1.1 Protocol endpoint (SELECT/ASK/CONSTRUCT/DESCRIBE; GET `?query=`,
  POST `application/sparql-query`, POST `application/x-www-form-urlencoded`). Requires the same
  authentication as the REST API when `auth-mode` is not `none` (Basic / JWT / LDAP); the Yasgui
  page at `/sparql` itself stays public.

Mapping/ontology files: `metacatalog-sparql/src/main/resources/ontop/{mapping.obda, ontology.owl}`,
resolved from the classpath at startup. The endpoint is gated on
`application.sparql.enabled` (default `true`) and configured under `application.sparql.*` in
`application.yaml`. Ontop's full transitive graph is shaded into the `metacatalog-sparql` jar
with `net.sf.jsqlparser` and `org.jgrapht` relocated to private packages; RDF4J 5.3.0 is managed
separately and not shaded. No Ontop CLI install is needed to build or run the app.

## Conventions & gotchas

- **Format before you commit** — `mvn spotless:apply`. The build fails on unformatted code.
- **Lombok** is used throughout (`@Getter/@Setter/@RequiredArgsConstructor/@Slf4j/@ToString`);
  it's an annotation-processor path in the compiler plugin and excluded from the boot jar/image.
- **JSON columns** are `jsonb` mapped with Hypersistence `JsonBinaryType`; entity `values` and
  type/trait `baseSchema`/`derivedSchema` are `JsonNode`. Schema validation uses networknt
  `json-schema-validator` (helpers in `common/JsonUtils`).
- **Vavr** (`Try`, `Tuple2`, …) is used for functional error handling in the task/procedure paths.
- **Don't hand-edit generated OpenAPI code** — change the YAML spec and regenerate.
- **DB schema** is owned by Flyway migrations in
  `metacatalog-core/src/main/resources/db/migration/` (`V1__create_tables.sql`,
  `V2__insert_base_traits_and_relationships.sql`). Add new `V*__*.sql` files; never edit applied ones.
- Java 25 + the JDK-internal compiler `--add-opens` args in the compiler plugin are required for the
  toolchain (Google Java Format / Lombok) — keep them when touching the parent `pom.xml`.
```
