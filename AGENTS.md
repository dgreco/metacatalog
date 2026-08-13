# CLAUDE.md

Guidance for Claude Code (and human developers) working in this repository.

## What this project is

**Meta Catalog** is a metadata management system built with Spring Boot. It manages **entities**,
**entity types**, **traits**, and the **relationships** between them, with JSON-Schema-based
validation of entity attributes, graph-based relationship traversal, an asynchronous task/procedure
engine, and optional ontology-based data access via Ontop.

- **Group / artifact:** `it.davidgreco:metacatalog` — version `0.0.1-SNAPSHOT`
- **Packaging:** Maven multi-module (`pom` parent + 7 modules)
- **Java:** 25 · **Spring Boot:** 4.1.0 · **PostgreSQL** (JDBC 42.7.x) · **Maven:** 3.9.9+ (enforced)
- **Persistence:** Spring Data JPA / Hibernate, JSON stored in `jsonb` columns via Hypersistence Utils
- **Schema migrations:** Flyway (auto-run on startup)

## Module layout

The reactor builds modules in this order (see root `pom.xml` `<modules>`):

| Module | Purpose |
| --- | --- |
| `metacatalog-core` | Domain model (JPA entities), repositories, services, task engine, JSON utilities, DB migrations, Ontop mapping files, and the `bootstrap` startup extension point for modules declaring an immutable model. The heart of the system. |
| `metacatalog-functions` | Pluggable **procedures** that run over entities — the `provisioning` package provisions an aggregate's resources in dependency order and unprovisions them in reverse. Also holds the procedure abstraction (`EntityProcedure`, `AbstractEntityProcedure`, `ProcedureExecutor`, `DeferredTaskFactoryRegistrar`) and the reusable task/registrar base classes (`ProvisioningTask`, `ProvisioningTasks`) that task modules extend. Depends on core; assembled into the application. |
| `metacatalog-functions-provisioning-tasks` | The concrete provisioning **tasks** and their registrars — `StdoutProvisioningTask`(`s`) and `ScriptProvisioningTask`(`s`) plus `ProvisioningConfigProperties`, whose `tasks` map associates each task with the entity types it handles. The template for plugging in real task modules: depend on `metacatalog-functions`, subclass `ProvisioningTask`, register through a `ProvisioningTasks` registrar. Assembled into the application. |
| `metacatalog-openapi` | The OpenAPI contract (`interface-specification.yaml`), code generated from it (spring server + client), and `MetacatalogApiImpl` (the delegate implementation wiring the generated controllers to core services and, for the provision/unprovision endpoints, to `ProcedureExecutor` in `metacatalog-functions`). |
| `metacatalog-application` | The deployable Spring Boot app: `Application` main class, web/OpenAPI config, and profile-specific YAML (`application.yaml`, `application-docker.yaml`, `application-kubernetes.yaml`). |
| `metacatalog-security` | Pluggable authentication for the REST API. Selectable via `application.config.security.auth-mode`: `none` (default, no auth), `basic` (HTTP Basic with users from config), `oauth2` (JWT resource server, e.g. Keycloak) or `ldap` (LDAP bind). Depends on Spring Security 7.x. |
| `metacatalog-ui` | Server-side rendered admin UI under `/ui/**` (Thymeleaf templates + plain JS, no build step). Drives the REST API through `MetacatalogApiDelegate`; does not depend on `metacatalog-core`. See [UI](#ui-metacatalog-ui). |
| `metacatalog-sparql` | Embedded Ontop SPARQL endpoint over the metacatalog DB. See [Ontop](#ontop-embedded-sparql-endpoint). |
| `metacatalog-iceberg-catalog` | An **independent Spring Boot application** (port 8181, own image) implementing the standard Apache Iceberg REST catalog API over the core service layer, against the same database as the main app. See [Iceberg REST catalog](#iceberg-rest-catalog-metacatalog-iceberg-catalog). |

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

### Built-in traits (installed at startup by `BuiltInModelContributor`)

`Aggregate`, `AggregateElement`, `Provisionable`, `ProvisionableResource`. These underpin the
aggregate model (`AggregateService`) and the provisioning procedure. All four are installed
**immutable**, as is the `Aggregate HAS_PART AggregateElement` relationship between them.

They are deliberately **not** seeded by the Flyway baseline. `BuiltInModelContributor` declares them
through the same [contributor extension point](#declaring-an-immutable-model-from-a-module-bootstrap-package)
any other module uses, ordered `HIGHEST_PRECEDENCE` so a module can inherit from them or mix them
in. That keeps one source of truth, expressed against the services rather than the tables: derived
schemas, version groups and the inverse `IS_PART_OF` row are computed exactly as they are for a
user-declared trait, instead of being hand-written in SQL that drifts from what the services would
produce. (The old seed wrote only the `HAS_PART` row and no inverse — precisely that kind of drift.)

One consequence for tests: the built-ins no longer come back at fixed UUIDs after a
`flyway.clean()`. `CommonServiceTestingSupport` (core, and its twin in functions) therefore clears
the Caffeine caches and re-runs the installer in `@BeforeEach` — the Spring context is cached across
test classes, so without that a cache hit would hand a later test a trait whose id was deleted.

### Immutability

`Trait`, `EntityType` and `TraitRelationship` carry an `immutable` boolean column (default
`false`). It makes the row neither deletable nor updatable:

| Marked immutable | Refused |
| --- | --- |
| `Trait` | `TraitServiceImpl.delete`, `TraitServiceImpl.createVersion` |
| `EntityType` | `EntityTypeServiceImpl.delete`, `EntityTypeServiceImpl.createVersion` |
| `TraitRelationship` | `TraitServiceImpl.unlink` |

`createVersion` is refused because versioning mutates the live row **in place** (snapshotting the
old state into the history table), which is exactly what the flag forbids.

Three properties are worth preserving:

- **The flag is never exposed over REST.** No endpoint sets it and no DTO reports it — `DtoMapper`
  deliberately does not map it. Callers only ever observe the refusal, as a 400 naming the row and
  saying it is immutable. The admin UI therefore cannot render a badge or hide the delete control
  either; it surfaces the error as a flash message like any other refusal. Do not add it to
  `interface-specification.yaml` to make the UI prettier — that is the whole point of the decision.
- **A startup contributor is the only way to create one.** `TraitService` and `EntityTypeService`
  expose no method that can produce an immutable row, so nothing holding them — the REST delegate,
  the bulk loader, the UI's path through the API — can make one. The capability lives on two
  separate interfaces, `ImmutableTraitWriter` and `ImmutableEntityTypeWriter`, implemented by the
  same service impls and injected only into `ImmutableModelInstaller` (see below).
- **It is one-way.** Set at creation and never cleared. Correcting a row marked by mistake means
  going to the database.
- **It freezes the row, not the graph around it.** A mutable trait may inherit from an immutable
  one, a relationship may be linked to it, and entities of an immutable type are created, updated
  and deleted as usual — none of that writes to the frozen row.

Two wiring details are load-bearing and easy to undo by accident:

- **The writer interfaces exist because the services are JDK proxies.** `CoreConfig` declares the
  service `@Bean`s with the *interface* as return type and the app proxies with
  `proxyTargetClass=false`, so the bean in the container is a proxy implementing `TraitService` — it
  is not a `TraitServiceImpl`. Putting `createImmutable` on the impl class alone would make it
  unreachable (injecting the class fails outright). A proxy does implement every interface of its
  target, which is why a second interface works where a class-only method does not.
- **`traitService` / `entityTypeService` are `@Primary`.** `immutableTraitWriter` and
  `immutableEntityTypeWriter` re-expose the *same instance* under a second bean name, so resolving
  `TraitService` by type would otherwise find two candidates and fail.

The shared bodies behind `create` / `createImmutable` are **private** helpers, not one public method
delegating to another: a self-invocation bypasses Spring's proxy, so the annotation on the inner
method would be silently ignored. (Both entry points are themselves `@Transactional`, so the
transaction is already open — but the same mistake in the form of a `default` method on the
interface is what once left creation running with no session at all, failing the lazy father/trait
walk in `computeDerivedSchema` with `LazyInitializationException`.)

#### Declaring an immutable model from a module (`bootstrap` package)

A Java module that owns part of the model — and needs it present and frozen before anything else
runs — implements `ImmutableModelContributor` and publishes it as a bean. `ImmutableModelInstaller`
(an `ApplicationRunner`) applies every contributor once at startup:

```java
@Component
class GovernanceModel implements ImmutableModelContributor {
  @Override
  public void contribute(ImmutableModelRegistry registry) {
    registry.trait("Owned", """
        {"type":"object","properties":{"owner":{"type":"string"}}}""");
    registry.trait("Classified");
    registry.link("Owned", RelationType.HAS_PART, "Classified");
    registry.entityType("Dataset", null, List.of("Owned"));
  }
}
```

- **This is the only way to create an immutable row.** The installer is the sole holder of
  `ImmutableTraitWriter` / `ImmutableEntityTypeWriter`; ordinary creation through
  `TraitService` / `EntityTypeService` always produces mutable rows.
- **Idempotent by construction.** Each declaration is check-then-create against the live catalog:
  what exists is left alone, what is missing is created immutable. There is no "already applied"
  ledger and none is needed — restarts and re-deploys converge on the same state. Write
  `contribute` as a list of what must exist, not as a migration.
- **Declaration order is application order**, and nothing is sorted for you: declare a father
  before its children, and both traits before the link between them. Across modules the order is
  bean order, pinnable with `@Order`.
- The whole installation is one transaction, so a contributor that throws leaves nothing behind and
  aborts startup rather than half-building a model later declarations would extend.
- A PostgreSQL advisory lock (id `2`; `MappingUpdaterService` uses `1`) serialises instances booting
  together. An instance that misses the lock **skips** rather than waits — the holder is doing the
  same work, and blocking every replica behind one transaction would turn a rolling restart into a
  queue.
- A declared name already held by a **mutable** row is left as it is, with a warning. Installation
  never converts live catalog data, because the flag is only ever set at creation.
- `contributors` is injected as an `ObjectProvider`, not a `List`: injecting a list when no
  contributor bean exists fails outright, and none is the default.

## Services (core)

Located in `metacatalog-core/.../service`:

- CRUD + validation services: `EntityService`, `EntityTypeService`, `TraitService`,
  `MappingService`, `MappingUpdaterService`, `BulkLoaderService`, `AggregateService`.
- Shared contracts / helpers: `CommonService`, `CommonTypeService`, and the error hierarchy
  `ServiceError` (unchecked — extends `RuntimeException`) with subclasses `NotFoundException` and
  `SchemaValidationError`.
- **`AggregateSchemaService`** — derives the combined JSON Schema of a whole aggregate tree, so an
  aggregate can be authored as a single document. See [Aggregate schemas](#aggregate-schemas).
- **Task engine:** `TaskManager`, `Task`, `TaskFactory` — a `Task` always operates on an `Entity`
  of a specific `EntityType`; factories are registered per entity type name and resolved from the
  entity's own type. Builds
  dependency graphs with **JGraphT**, detects cycles (`CycleDetector`), and executes schedules
  asynchronously (results cached with Caffeine). `AsyncTaskExecutor`-backed.
- **`MappingUpdaterService`** — `@Scheduled` job that reacts to `EntityLifeCycleEvent`s to create /
  update mapped entities. Gated by `application.config.automaticEntitiesMapping` and guarded by an
  advisory lock so only one instance runs at a time.
- **One mapping per (source, target) pair** — `MappingService.create` is create-or-replace:
  re-creating an already-mapped pair updates the existing relationship in place (same id, values
  re-validated, type versions re-stamped), so mapped entities stay attached and the engine never
  derives a duplicate instance of the target type. The UI mapping form pre-fills from the existing
  mapping and submitting replaces it.
- **Versioning a mapped target type** — `EntityTypeService.createVersion` re-validates every
  mapping *targeting* the type against the candidate schema and refuses the version if a mapping no
  longer satisfies it (update or delete the mapping first). Otherwise the breakage would surface
  only asynchronously, as FAILED lifecycle events. Existing mapped entities stay pinned to the
  snapshot of the version their *mapping* was defined against — mapped entities are derived, so
  they are validated against and pinned to the mapping's stamped target version: an untouched
  mapping keeps them on the old version even after the type is versioned, and replacing the
  mapping (re-stamped against the live version) moves them forward on their next update. Only
  the target side can be checked statically — a mapping's SpEL reads source *values*, not the
  source schema. Each mapping also records the source/target type versions it was defined against
  (`sourceEntityTypeVersion` / `targetEntityTypeVersion`, frozen at mapping creation and exposed
  read-only on the `Mapping` DTO); the UI shows them next to the type names in its mapping lists.

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

`AbstractEntityProcedure` / `EntityProcedure` / `ProcedureExecutor` (in this module, package
`it.davidgreco.metacatalog.functions`) and `DeferredTaskFactoryRegistrar` (in the `provisioning`
subpackage, next to the registrar base class that implements it) define the procedure
abstraction — it lives here rather
than in core because it is strictly tied to the provisioning features; `metacatalog-openapi`
depends on this module for `ProcedureExecutor`, which backs the provision/unprovision endpoints.
`metacatalog-functions` also provides the concrete procedures: `ProvisioningProcedure` and
`UnprovisioningProcedure`, which read an aggregate, build a dependency graph of
`ProvisionableResource` entities from mapping relationships (`ResourceGraphBuilder`, shared by both
so they cannot disagree), detect cycles, and schedule the work.

**The two differ only in direction.** Provisioning makes a resource wait for what it is derived
from; unprovisioning makes what it is derived from wait for *it*. An Athena table reading an S3
folder is created after the folder and destroyed before it, so nothing is removed while something
still depends on it. `ProvisioningProcedureTests` asserts both orders — that is the assertion to
keep if the wiring is ever touched.

Registering a function for a resource type means subclassing `ProvisioningTask` and registering a
`TaskFactory` under the **entity type name** — `TaskManager.createTask(entity)` resolves the
factory from the entity's own type, so a resource can never be handed to another type's task:

```java
taskManager.registerTaskFactory("S3FolderType",
    entity -> new StdoutProvisioningTask(entity, entityService));
```

**The name must be one an entity type actually has** — `registerTaskFactory` throws
`NotFoundException` otherwise. Registering under a name nothing matches is always a mistake, and it
used to surface only much later, as `No factory for name: <type>` when a run reached the resource,
with nothing tying it back to the registration; the reverse case was worse, since a factory
registered under a misspelled name simply never fired.

The consequence is that **a registrar working from configuration cannot register at startup**, because
entity types are catalog data created at runtime. That is what
`it.davidgreco.metacatalog.functions.provisioning.DeferredTaskFactoryRegistrar` is for: the two
provisioning procedures call `ensureRegistered()` on every such bean at the start of each run,
inside the plan-building transaction, when the types involved certainly exist. (`ProcedureExecutor`
knows nothing about registrars — deferred registration is a provisioning concern, invoked by the
procedures that need the factories.) Implementations must be idempotent and cheap
(skip what `TaskManager.isRegistered` already reports), and must **not** throw for a type that still
does not exist — this runs before *every* execution, so one stale name would fail the provisioning of
unrelated aggregates. A resource whose type genuinely has no factory still fails its own run.

`ProvisioningTask` requires both `provision()` and `unprovision()`, so a type cannot end up with a
way to create a resource and no way to remove it. Which one runs is set by the procedure through
`setOperation`, and the base class records the outcome on the entity — `PROVISIONED`,
`UNPROVISIONED`, or `FAILED` with the error in `provisioningResult`. A resource type with no
registered factory fails the run (`No factory for name: <type>`) rather than being reported as
provisioned by nothing.

The concrete tasks live in their own module, `metacatalog-functions-provisioning-tasks`, so task
registration is pluggable: a module contributes tasks by depending on `metacatalog-functions`,
subclassing `ProvisioningTask`, and registering through a `ProvisioningTasks` registrar (or any
other `DeferredTaskFactoryRegistrar`) — the procedures in `metacatalog-functions` never reference a
concrete task class. Which task handles which entity type is configuration: the
`application.config.provisioning.tasks` map associates each task (by its registrar's task name)
with the entity types it handles, and task-specific settings live in the same entry —

```yaml
application:
  config:
    provisioning:
      tasks:
        stdout:
          entity-types: [S3FolderType]
        script:
          entity-types: [AthenaTableType]
          path: /opt/scripts/provision.sh
```

The map is keyed by task name, not entity type name, deliberately: Spring's relaxed binding
mangles map keys that are not lowercase alpha-numerics, so a `S3FolderType` key would silently
bind as `s3foldertype` unless bracket-quoted. A type listed under two tasks is refused at startup
(`ProvisioningConfigProperties` validates in its constructor); the map is empty by default, so
adding the module changes nothing until types are named.

Two tasks ship in the module:

- **`stdout`** — `StdoutProvisioningTasks` registers `StdoutProvisioningTask`, printing what it
  would do (lines prefixed `[provisioning]` / `[unprovisioning]`) instead of creating anything. It
  writes to standard output rather than the log on purpose: the application ships with
  `logging.level.root: ERROR`.
- **`script`** — `ScriptProvisioningTasks` registers `ScriptProvisioningTask`, which runs `bash
  <path> <provision|unprovision>` with the entity's JSON values on standard input, relays the
  script's output to standard output and records it in `provisioningResult`. A non-zero exit or a
  5-minute timeout fails the task (and the run — provisioning is synchronous). The `path` is pure
  configuration with nothing runtime about it, so a `script` entry naming entity types but no path
  is refused at startup rather than surfacing on the first run.

Both registrars are `DeferredTaskFactoryRegistrar`s rather than `@PostConstruct` registrars, for exactly the
reason above: the Docker Compose demo names `S3FolderType` / `AthenaTableType` in
`application-docker.yaml`, but `docker/bulk/` only creates them once the app reports healthy, so
registering at startup would fail on a fresh volume. The shared registrar base
(`ProvisioningTasks`, functions module) does **not** re-check `EntityTypeService.exists` itself —
that rule belongs to `registerTaskFactory` and duplicating it would let the two drift — it attempts
the registration and catches the `NotFoundException`, logging a warning so a stale entry in the
list never turns into an exception. `StdoutProvisioningRegistrationTests` pins this — it is the
guard against a change that would break `docker compose up` rather than CI — and
`ScriptProvisioningRegistrationTests` pins the per-type association, provisioning one aggregate
whose two resource types are handled by different tasks.

Both operations are exposed as `POST /metacatalog/v1/aggregate/{id}/provision` and
`.../unprovision`. They are **synchronous** — `ProcedureExecutor.executeProcedure` returns only
once the schedule completes and throws if any task failed — so a 204 means the whole aggregate is
done. Nothing provisions on its own; it happens when asked.

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
- Aggregate deletion: `DELETE /aggregate/{id}` removes an aggregate as a whole — the root, its
  whole `HAS_PART` tree and everything derived from those members through `MAPPED_TO`, in one
  transaction. It is refused when a member is linked to an entity outside the aggregate (deleting
  it would leave that outsider dangling) or when a member is itself a mapping target. `DELETE
  /entity/{id}` stays deliberately strict — it refuses any entity that still has links — so it is
  never a way to dismantle an aggregate piecemeal. For the same reason `DELETE /entity/link/...`
  refuses to remove a `HAS_PART` / `IS_PART_OF` link whose containing side implements the
  `Aggregate` trait: containment is what makes a part reachable from its root, so detaching one
  strands it where the aggregate delete can no longer see it and the entity delete still refuses
  it. Dependency (`DEPENDS_ON`) links within an aggregate, and containment between entities that
  are not aggregates, stay removable — neither determines reachability. One more refusal applies
  to any entity link: if an existing mapping resolves an entity path reference through it, removing
  it would make the mapped entities derived through that path fail every later update —
  asynchronously, as FAILED lifecycle events. The check is exact (each candidate mapping's path is
  re-resolved and only a traversal that actually walks the link blocks the removal); delete or
  re-create the mapping first.
- Trait links: `DELETE /trait/link/...` refuses to remove a trait relationship that an existing
  entity link relies on. A link between two instances is only admitted because a trait relationship
  sanctions it (`ServiceUtils.checkRelIsLegit`), so removing the last one that does would leave the
  link in the graph with nothing justifying it — un-recreatable, and dropped from the aggregate
  schema derived from the trait graph. The check is exact, not type-level: since a type participates
  by mixing traits in, several trait relationships can sanction the same link, and each candidate
  link is re-tested for legitimacy with the removed pair excluded — only a link losing its *last*
  justification blocks the removal. Remove the instance link first.
- Immutability: the delete and version endpoints for traits and entity types, and `DELETE
  /trait/link/...`, also refuse anything marked immutable (see [Immutability](#immutability)). The
  flag itself is deliberately **not** part of the contract — it is neither settable nor reported by
  any endpoint; clients only ever see the refusal.

When running:
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Spec: `http://localhost:8080/api/interface-specification.yaml`
- Actuator: `/actuator/health`, `/actuator/info`, `/actuator/metrics`

## UI (metacatalog-ui)

A server-side rendered admin UI mounted at `/ui`, built with Thymeleaf templates
(`src/main/resources/templates`) and plain ES5 JavaScript (`src/main/resources/static/ui/js`) — no
npm, bundler, or front-end build step.

**Everything goes through the REST API.** Controllers call `MetacatalogApiDelegate`, never the core
services, so the UI exercises the same contract external clients do.

This is enforced by the build rather than by convention: `metacatalog-ui/pom.xml` declares no
dependency on `metacatalog-core` **and** excludes it from `metacatalog-openapi`, which would
otherwise drag it in transitively. Omitting the direct dependency alone is not enough — without the
exclusion, `import it.davidgreco.metacatalog.service.*` still compiles. With it, that import is a
compile error. The exclusion is compile-scope only: at runtime `metacatalog-application` assembles
both modules, so the delegate still finds its services.

If a page needs data the API doesn't expose, add the endpoint to the spec first — don't relax the
exclusion.

Pages: dashboard (`/ui`), trait / entity-type / mapping / trait-link forms, version history, the
catalog graph (`/ui/graph`), YAML bulk upload (`/ui/bulk`), instances (`/ui/instances`), and
aggregate authoring (`/ui/aggregates/new`).

The instances list is also where an aggregate is deleted, provisioned and unprovisioned. A row gets
a *Delete aggregate* action when its entity type is one of the `GET /aggregate/root-type` types —
that test is enough to identify a root, since an aggregate root type is by definition never
contained in another, so no instance of one can be a part of a larger aggregate. It gets *Provision*
and *Unprovision* when its type is one of the `GET /aggregate/provisionable-type` types.

Both lists are asked of the API rather than worked out in the UI. Whether a type is provisionable
depends on the type **and** trait inheritance chains, both lazily fetched, so the walk only works
inside a transaction — `AggregateSchemaService.provisionableTypes()` does it there and the endpoint
hands the UI the answer. The `traits` on the `EntityType` DTO are only the directly associated ones,
so checking them here would miss an inherited `Provisionable`.

Provisioning from the UI is synchronous: the POST does not return until the whole aggregate is
done, so the page reloads showing the finished state. A slow real-world provisioning function would
make that request hang, which is a reason to keep the UI action for demo-scale aggregates.

The link forms distinguish two things that `CatalogGraphService.PRIMARY_RELATION_TYPE_NAMES` does
not: which relation types may be *authored* versus which direction of a bidirectional pair is
*displayed*. Only `DEPENDS_ON` and `HAS_PART` are offered for creation
(`UiControllerHelper.LINKABLE_RELATION_TYPE_NAMES`); `MAPPED_TO` is derived by the mapping engine
and lives in `mapping_entity_relationship`, so hand-creating one would write a mapping-typed row
into `entity_relationship` where the engine would never see it. The entity-link page therefore
lists mapping relationships in a separate read-only section (from
`GET /mapping/entity-relationships`) with no create or remove controls, and `createLink` also
rejects a mapping type server-side so a hand-crafted POST cannot bypass the dropdown.

The two schema-driven editors are the substantial pieces:

- `instance-values-form.js` builds a typed form for a single entity from its type's schema.
- `aggregate-form.js` builds a **tree** from the combined aggregate schema, resolving `$ref` against
  `$defs` and creating child parts only when the user adds one — which, together with the
  `$defs`/`$ref` indirection, is what keeps a recursive composition from expanding forever. It
  submits JSON; `AggregateUiController` converts it to the YAML `POST /aggregate/yaml` consumes.

Both offer a Builder / Raw JSON tab pair and fall back to Raw JSON for anything they cannot render.
The aggregate form adds a read-only YAML tab (rendered server-side via `POST
/ui/aggregates/yaml-preview`) showing the exact document the aggregate endpoint would receive.

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
- `/metacatalog/v1/**`, `/sparql/query`, the server-side rendered UI under `/ui/**` and the SPARQL
  query UI at `/sparql` require authentication (in `oauth2` mode without SSO there is no browser
  login flow, so a browser hitting the UI gets a plain 401)
- `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/api/interface-specification.yaml`, `/javadoc/**` are public
- Everything else is public
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

Provisioning properties bind under `application.config.provisioning` into
`ProvisioningConfigProperties` (`metacatalog-functions-provisioning-tasks` module): `tasks`
(Map&lt;String, TaskConfig&gt;, default empty) — the entity types each provisioning task handles,
keyed by task name (`stdout`, `script`), with task-specific settings (the `script` task's `path`)
in the same entry (see [Procedures](#procedures-functions-module)).

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
- The plain-JS front end has no JS test runner; it is exercised by
  `UiJavascriptSmokeTest` in `metacatalog-application` — the full app booted against a
  Testcontainers PostgreSQL, driven by Selenium against a headless Chrome container (the browser
  reaches the app via `host.testcontainers.internal`). Add scenarios there when UI JavaScript
  behavior needs coverage. It boots with `application.sparql.enabled=false`: in a reactor build
  the sparql dependency is its unshaded `target/classes` (JGraphT relocation happens only at jar
  packaging), so Ontop cannot start there.
- Coverage via JaCoCo (report generated in the `test` phase).

## CI / Deployment

- **CI:** `.gitlab-ci.yml` — `build` stage on `maven:3-eclipse-temurin-25` with a `docker:dind`
  service: runs `mvn clean install spotless:check test`, then on `main`/`master`/`develop` builds
  and pushes a Docker image (tags: short SHA, `latest`, and version on main).
- **Docker Compose:** `docker-compose.yml` — Postgres 18.4 (`:5432`) + app (`:8080`, `docker`
  profile) + a one-shot `bulk-loader` that seeds the demo model and aggregate from `docker/bulk/`.
  The loader is idempotent across restarts (the postgres volume persists): each step probes the API
  first and only loads what is missing, so deleting the demo aggregate from the UI and restarting
  re-creates just the aggregate. `docker-compose.keycloak.yml` is an overlay (`-f` both files)
  that adds a Keycloak with a pre-imported realm (`docker/keycloak/`) and switches the app to
  `auth-mode: oauth2` in jwk-set-uri mode (client `metacatalog`/`metacatalog-secret`, user
  `demo`/`demo`, password grant for curl-friendly token fetching); the bulk-loader is disabled
  there because it only speaks HTTP Basic. `docker-compose.keycloak-sso.yml` stacks on top of
  that to enable browser SSO for the UI: issuer `http://localhost:8081` works from both sides
  because the browser hits Keycloak's published port while a socat sidecar in the app container's
  network namespace forwards its `localhost:8081` to Keycloak. `docker-compose.iceberg.yml` is
  another overlay (`make run-iceberg` / `up-iceberg-d`): it adds a MinIO warehouse (bucket
  `iceberg-warehouse`, created by a one-shot `minio-init`) and the Iceberg REST catalog app on
  `:8181`, sharing the base stack's postgres.
- **Kubernetes:** `k8s/` — app Deployment/Service/Ingress/ConfigMap/ServiceAccount plus a
  CloudNativePG (`cnpg`) Postgres cluster and scheduled backup; `kustomization.yaml` ties it together.
  `*.template` secret files must be filled in (DB credentials, registry pull secret).

## Iceberg REST catalog (metacatalog-iceberg-catalog)

A second deployable application: `IcebergCatalogApplication` (package root `it.davidgreco.metacatalog`,
like every app class) serves the standard **Apache Iceberg REST catalog API** on port 8181 so
Spark / Trino / PyIceberg can use the metacatalog as their table catalog. It shares the main
app's database (identical Flyway baseline from the core jar — this module **never** adds
migrations) and depends on **`metacatalog-core` only** among the internal modules: every module
shares the `it.davidgreco.metacatalog` package root, so any other internal jar on the classpath
would be component-scanned wholesale. No `@EnableScheduling` — the mapping updater stays owned by
the main application.

**Registry model.** Declared immutable at startup by `IcebergModelContributor`: traits
`IcebergNamespaceTrait` / `IcebergTableTrait`, the `IcebergNamespaceTrait HAS_PART
IcebergTableTrait` link, and entity types `IcebergNamespace` / `IcebergTable`. The traits
deliberately do **not** inherit `Aggregate`/`AggregateElement` — `unlink` refuses to detach a part
from an `Aggregate` container, which would make dropping a table impossible. A namespace entity
stores `key` (levels joined with `\u001F`, the one character illegal inside an Iceberg namespace
level — a level may contain dots, so a dotted join would collide), `name` (dotted, display only),
`levels`, `properties`. A table entity stores `name`, `namespaceKey`, `metadataLocation`,
`previousMetadataLocation` and `metadata` (a cached copy of the current table metadata, browsable
in the UI/SPARQL). Containment is a real `HAS_PART` entity link, so tables hang off their
namespace in the catalog graph.

**Storage split.** Standard Iceberg: the server writes table-metadata JSON files to the warehouse
via a `FileIO` (`application.config.iceberg.{warehouse,io-impl,io-properties}`); the entity holds
the metadata-location pointer. The built-in `LocalFileIO` (nio-based) handles `file://` without
Hadoop — iceberg-core's only local option is HadoopFileIO plus ~50 MB of hadoop-common — and
`S3FileIO` (iceberg-aws) serves S3/MinIO; the `docker` profile targets the MinIO added by the
`docker-compose.iceberg.yml` overlay (`make run-iceberg`).

**Concurrency.** Entities have no name column and no uniqueness on jsonb values, so
`IcebergRegistryService` (the only class touching core services) enforces both itself, inside one
transaction per operation holding a `pg_try_advisory_xact_lock` derived from the name (ids 1 and 2
are reserved by core; the lock is retried per `commit-lock-*` config). The commit path is a CAS:
re-read under the lock, compare the stored `metadataLocation` with the commit's base, swap or
throw `CommitFailedException` — the CAS, not the lock, is the correctness guard. An unknown
failure after the metadata file is written surfaces as `CommitStateUnknownException` (500) so
clients do not delete a possibly-live metadata file.

**REST layer.** Hand-written controllers (no OpenAPI codegen — the contract is Apache's):
`IcebergRestController` parses bodies with `IcebergJson` (a Jackson-2 mapper configured like
iceberg-core's `RESTObjectMapper`; controllers exchange `String` so Spring's Jackson-3 converters
never touch iceberg types) and dispatches to iceberg-core's `CatalogHandlers`, which owns the
protocol semantics including `UpdateRequirement` validation and the server-side commit retry.
`GET /v1/config` advertises the prefix and an **explicit endpoint list** — omitting it makes
clients assume the default set (multi-table transactions, views) and fail with 404s. Errors map to
the spec's `ErrorResponse` in `IcebergExceptionHandler` (404 no-such, 409
already-exists/commit-failed/not-empty, 500 commit-state-unknown).

**Tests.** `IcebergRegistryServiceTests` pins the jsonpath `\u001F` escaping (a dotted level vs.
two levels must not collide), the CAS success/stale paths and a concurrent-commit race (exactly
one winner). `IcebergRestCatalogEndToEndTest` boots the real app against Testcontainers PostgreSQL
and drives it with **Iceberg's own `RESTCatalog` client** over a temp `file://` warehouse —
namespaces, table create, property/schema commits, rename, drop. One client quirk worth keeping:
request builders probe `containsKey(null)`, so never hand the client a `Map.of(...)`.

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
- **DB schema** is owned by a single Flyway baseline,
  `metacatalog-core/src/main/resources/db/migration/V1__initial_schema.sql`, which creates every
  table. It seeds **no data** — the built-in traits are installed at startup by
  `BuiltInModelContributor`, not by SQL. The project does not support migrating an existing database:
  edit the baseline in place and recreate the DB rather than adding `V2`, `V3`, … files. Editing it
  changes its checksum, so any database that ran the previous version must be dropped. After
  editing, run `mvn clean` before testing — a stale copy of a deleted migration left in
  `target/classes` makes Flyway fail with "Found more than one migration with version 1".
  The JPA entities are the source of truth for column names, nullability and index names; tests run
  with `ddl-auto: validate`, which catches column drift but **not** index or nullability drift.
- Java 25 + the JDK-internal compiler `--add-opens` args in the compiler plugin are required for the
  toolchain (Google Java Format / Lombok) — keep them when touching the parent `pom.xml`.
```
