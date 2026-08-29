# CLAUDE.md

Guidance for Claude Code (and human developers) working in this repository.

## What this project is

**Meta Catalog** is a metadata management system built with Spring Boot. It manages **entities**,
**entity types**, **traits**, and the **relationships** between them, with JSON-Schema-based
validation of entity attributes, graph-based relationship traversal, an asynchronous task/procedure
engine, and optional ontology-based data access via Ontop.

- **Group / artifact:** `it.davidgreco:metacatalog` — version `0.0.1-SNAPSHOT`
- **Packaging:** Maven multi-module (`pom` parent + 7 modules)
- **Java:** 26 · **Spring Boot:** 4.1.0 · **PostgreSQL** (JDBC 42.7.x) · **Maven:** 3.9.9+ (enforced)
- **Persistence:** Spring Data JPA / Hibernate, JSON stored in `jsonb` columns via Hypersistence Utils
- **Schema migrations:** Flyway (auto-run on startup)

## Module layout

The reactor builds modules in this order (see root `pom.xml` `<modules>`):

| Module | Purpose |
| --- | --- |
| `metacatalog-core` | Domain model (JPA entities), repositories, services, task engine, JSON utilities, DB migrations, Ontop mapping files, and the `bootstrap` startup extension point for modules declaring an immutable model. The heart of the system. |
| `metacatalog-functions` | Pluggable **procedures** that run over entities — the `provisioning` package provisions an aggregate's resources in dependency order and unprovisions them in reverse, and authorizes / rejects them the same way over the second pair of traits (all four share `AbstractAggregateResourceProcedure`). Also holds the procedure abstraction (`EntityProcedure`, `AbstractEntityProcedure`, `ProcedureExecutor`, `DeferredTaskFactoryRegistrar`) and the reusable task/registrar base classes (`ProvisioningTask`, `ProvisioningTasks`) that task modules extend. Depends on core; assembled into the application. |
| `metacatalog-functions-provisioning-tasks` | The concrete provisioning **tasks** and their registrars — `StdoutProvisioningTask`(`s`) and `ScriptProvisioningTask`(`s`) plus `ProvisioningConfigProperties`, whose `tasks` map associates each task with the entity types it handles. The template for plugging in real task modules: depend on `metacatalog-functions`, subclass `ProvisioningTask`, register through a `ProvisioningTasks` registrar. Assembled into the application. |
| `metacatalog-openapi` | The OpenAPI contract (`interface-specification.yaml`), code generated from it (spring server + client), and `MetacatalogApiImpl` (the delegate implementation wiring the generated controllers to core services and, for the provision/unprovision endpoints, to `ProcedureExecutor` in `metacatalog-functions`). |
| `metacatalog-application` | The deployable Spring Boot app: `Application` main class, web/OpenAPI config, and profile-specific YAML (`application.yaml`, `application-docker.yaml`, `application-kubernetes.yaml`). |
| `metacatalog-security` | Pluggable authentication for the REST API. Selectable via `application.config.security.auth-mode`: `none` (default, no auth), `basic` (HTTP Basic with users from config), `oauth2` (JWT resource server, e.g. Keycloak) or `ldap` (LDAP bind). Depends on Spring Security 7.x. |
| `metacatalog-ui` | Server-side rendered admin UI under `/ui/**` (Thymeleaf templates + plain JS, no build step). Drives the REST API through `MetacatalogApiDelegate`; does not depend on `metacatalog-core`. See [UI](#ui-metacatalog-ui). |
| `metacatalog-sparql` | Embedded Ontop SPARQL endpoint over the metacatalog DB. See [Ontop](#ontop-embedded-sparql-endpoint). |
| `metacatalog-iceberg-catalog` | An **independent Spring Boot application** (port 8181, own image) implementing the standard Apache Iceberg REST catalog API over the core service layer, against the same database as the main app. See [Iceberg REST catalog](#iceberg-rest-catalog-metacatalog-iceberg-catalog). |
| `metacatalog-hive-metastore` | Another **independent Spring Boot application** (Thrift on 9083, actuator on 8282, own image) implementing the Apache Hive Metastore protocol over the core service layer, against the same database. A separate implementation with its own model, not a view over the Iceberg registry. See [Hive Metastore](#hive-metastore-metacatalog-hive-metastore). |

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

`Aggregate`, `AggregateElement`, `Provisionable`, `ProvisionableResource`, `Authorizable`,
`AuthorizableResource`. These underpin the aggregate model (`AggregateService`) and the
provisioning and authorization procedures. All six are installed **immutable**, as is the
`Aggregate HAS_PART AggregateElement` relationship between them.

The last four come in two pairs with the same shape: a root trait inheriting `Aggregate` and a
resource trait inheriting `AggregateElement`, each carrying a status/result field pair the
machinery writes (`provisioningStatus` / `provisioningResult`, `authorizationStatus` /
`authorizationResult`). The authorization pair carries more than that — its root also holds the
[access policy](#the-access-policy) and its resource the grants a run resolved — which is why the
two sides of a capability no longer share one schema. **The two capabilities are independent** — a type may offer provisioning,
authorization, both or neither — which is why they are separate fields rather than one: an
aggregate can be provisioned and not yet authorized, or authorized and torn down, and one field
could not hold both answers. Since a derived schema carries `additionalProperties: false`, writing
the wrong pair on a type that does not carry the matching trait is refused by validation rather
than silently accepted.

Each pair is declared once, as a `BuiltInCapability` constant (core, next to `BuiltInTraits`):
the two trait names, the two field names, the statuses the first may take, and a base schema per
side (`rootSchema()` / `resourceSchema()`) — rendered from those same constants, so the schema can
only ever admit exactly the fields the machinery writes. `BuiltInCapabilityTests` pins that pairing
by validating what the machinery renders against the schema an entity is actually written through:
a field spelled differently on the two sides fails the build rather than the last write of an
otherwise successful run. `BuiltInModelContributor` loops over the enum rather than spelling out
a block per capability, and `ProvisioningTask.Operation` reads its field pair from the same place.
Before that the field names lived as literals in a JSON text block in core *and* as constants in
`metacatalog-functions`, with nothing connecting them — a typo in either half stays invisible until
the status write at the end of a successful run, and since the traits are immutable the only remedy
then is recreating the database.

They are deliberately **not** seeded by the Flyway baseline. `BuiltInModelContributor` declares them
through the same [contributor extension point](#declaring-an-immutable-model-from-a-module-bootstrap-package)
any other module uses, ordered `HIGHEST_PRECEDENCE` so a module can inherit from them or mix them
in. That keeps one source of truth, expressed against the services rather than the tables: derived
schemas, version groups and the inverse `IS_PART_OF` row are computed exactly as they are for a
user-declared trait, instead of being hand-written in SQL that drifts from what the services would
produce. (The old seed wrote only the `HAS_PART` row and no inverse — precisely that kind of drift.)

One consequence for tests: the built-ins no longer come back at fixed UUIDs after a
`flyway.clean()`. `CommonServiceTestingSupport` (core, and its twins in the other modules)
therefore re-runs the installer in `@BeforeEach` — the Spring context is cached across test
classes, and the installer is an `ApplicationRunner` that would otherwise not run again.

#### Aggregate containment rules

Carrying `Aggregate` or `AggregateElement` (directly or through inheritance) constrains what a
trait or type may contain, and the constraints are **enforced**, not just documented — a violating
containment is refused wherever a `HAS_PART` can come into existence or change meaning:

1. A carrier of `Aggregate` may only have `HAS_PART` parts carrying `AggregateElement`.
2. A carrier of *only* `AggregateElement` may have no parts at all — leaves are leaves. (An
   intermediate node carries both; its Aggregate role is what grants it parts, so rule 1 applies.)
3. A carrier of **neither** stays free: containment outside the aggregate model is not the model's
   business, which is what lets an Iceberg namespace contain an Aggregate-only table type without
   the table ceasing to be a root.

The corollary of 1+2 is that an Aggregate-only carrier can never be a *part* inside the model. A
single trait can never carry both built-ins (single inheritance, both father-less), so a
self-composing type (a folder containing folders) carries its container role and its element role
through two different traits — making it an intermediate, with a separate root type above it.

Enforcement points (`ServiceUtils.checkAggregateTraitContainment` / `checkAggregateEntityContainment`,
always normalized to (whole, part) so `IS_PART_OF` is judged identically):

- `TraitServiceImpl.doLink` — every trait-relationship route, including `linkImmutable`: a startup
  contributor declaring a violating model aborts installation.
- `EntityServiceImpl.link` — necessary even with the trait-level check, because a link's sanction
  is existential: a relationship between two *neutral* traits can sanction a link whose types
  carry the built-ins through other mixins.
- `TraitServiceImpl.createVersion` / `EntityTypeServiceImpl.createVersion` — the only ways carriage
  can change after creation (a trait version can swap the father, a type version the trait set).
  A version whose carriage change would leave an existing containment violating the rules is
  refused naming it, mirroring the mapping re-validation; only relationships whose carriage flows
  through the versioned row are re-checked, so pre-existing violations elsewhere never block an
  unrelated version.

`AggregateModelRuleTests` (core) pins all of this, including the allowances: the neutral-source
freedom, `DEPENDS_ON` between any carriers, and versions that leave carriage untouched.

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
- **Editing a declaration that is already installed aborts startup.** "Left alone" is only safe
  while the row still says what the contributor declares, so the installer compares each existing
  **immutable** row against its declaration — base schema (as parsed nodes, so `jsonb`'s key
  reordering is irrelevant), father, and for an entity type its trait set (as a set: `type_traits`
  has no order column) — and throws naming the row and the differing field. An immutable row cannot
  be migrated, so the only remedy is recreating the database, and the message says so. This is the
  same posture as the Flyway baseline, which the project also edits in place rather than versioning.
  A *mutable* row of the same name is deliberately exempt: it is the operator's, was never expected
  to match, and stays fixable through the API, so it keeps the warning below. The check exists
  because `Provisionable` gained a schema after its first release; without it, every database
  created before that kept the empty schema, and the aggregate-root status write failed validation
  on a feature startup never mentioned. `ImmutableModelInstallerTests` pins both the refusals and
  the round-trip that must *not* be mistaken for drift.
- **Declaration order is application order**, and nothing is sorted for you: declare a father
  before its children, and both traits before the link between them. Across modules the order is
  bean order, pinnable with `@Order`.
- The whole installation is one transaction, so a contributor that throws leaves nothing behind and
  aborts startup rather than half-building a model later declarations would extend.
- A PostgreSQL advisory lock (id `2`; `MappingUpdaterService` uses `1`) serialises instances booting
  together. An instance that misses it still applies every declaration — the drift check decides
  whether that instance may run at all, and a replica exempted from it would boot happily against
  the very database the lock holder is refusing. What it skips is the **creating**.
- **Whether it then waits depends on what that pass found**, and the distinction is load-bearing.
  Nothing missing means the holder is a replica of the same application installing the same model,
  so there is nothing to wait for: this is the rolling-restart case, where queueing every replica
  behind one transaction would be pure delay. Something missing means the holder declares a
  *different* model against the same database and will never create it — **the lock id is shared by
  every application built on core**, and `metacatalog-iceberg-catalog` boots against the same tables
  with its own contributors, so a lost race must not cost it `IcebergNamespace` / `IcebergTable`.
  It then waits for the lock (60s, bounded by `lock_timeout` so a stalled holder aborts startup with
  a message instead of hanging it) and re-applies. The second pass re-runs every declaration from
  scratch: at READ COMMITTED each statement takes a fresh snapshot, so it sees what the holder
  committed and creates only the remainder. Links are deferred like anything else — a source trait
  the holder has not committed yet is not the mis-ordered declaration `linked()` throws on.
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
  asynchronously. `AsyncTaskExecutor`-backed.
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

- **Which types can start one?** Classification is by the built-in traits a type carries (directly
  or through the type and trait inheritance chains): an *aggregate root type* carries `Aggregate`
  but **not** `AggregateElement`; an intermediate node carries both; a leaf only `AggregateElement`.
  Whether some other type declares `HAS_PART` towards a root is irrelevant — a root may be
  contained by a type outside the aggregate model (one carrying neither trait, like an Iceberg
  namespace containing tables) without ceasing to be a root. `AggregateService.read`/`delete` apply
  the same trait test to the root entity. For the schema derivation, `HAS_PART` is **not** stored
  between entity types: composition is declared between *traits*, and a type participates by mixing
  them in (the same rule `ServiceUtils.checkRelIsLegit` enforces when linking two instances). The
  service projects those trait relationships onto the types carrying them to obtain the type-level
  graph — and since every `Aggregate` carrier declares `HAS_PART` towards `AggregateElement`, any
  element-carrying type is offered as a part of any aggregate type.
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
depends on this module for `ProcedureExecutor`, which backs the provision/unprovision and
authorize/reject endpoints.
`metacatalog-functions` also provides the concrete procedures: `ProvisioningProcedure` and
`UnprovisioningProcedure`, which read an aggregate, build a dependency graph of
`ProvisionableResource` entities from mapping relationships (`ResourceGraphBuilder`, shared by both
so they cannot disagree), detect cycles, and schedule the work — plus `AuthorizationProcedure` and
`RejectionProcedure`, the same thing over `AuthorizableResource` (see
[Authorization](#authorization)). The authorization pair lives in the same `provisioning` package
rather than one of its own: it shares the task type, the graph builder and the status recorder,
and moving it out would mean widening all three to `public` for no gain.

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

For the same reason, a factory producing a plain `Task` rather than a `ProvisioningTask` fails the
run too, **in every direction**. Provisioning used to tolerate one — it is the operation a task
runs by default, so nothing needed selecting — but a plain task records no status, and the run
would then leave the root `PROVISIONED` for a resource that reported nothing at all. That is the
same silence the missing-factory check exists to prevent, and it is worth no more here.

The run's **overall** outcome is recorded the same way on the aggregate root — the `Provisionable`
trait carries the same `provisioningStatus`/`provisioningResult` schema as `ProvisionableResource`,
and `AggregateProvisioningStatusRecorder` writes `PROVISIONED`/`UNPROVISIONED` on the root only
when every contained resource succeeded, `FAILED` with the error otherwise. The recorder *composes*
the schedule future (the procedures return the composed future in their `ScheduleHandle`), so a
synchronous call returns — and an async run turns terminal — only after the root reflects the
outcome; and it re-reads the root at completion time, because a root that is also a resource (an
intermediate node) had its resource-level status written during the run and a stale snapshot would
erase it. A failed status write never alters the run's result.

**The status write takes a transaction of its own (`REQUIRES_NEW`), and re-reading is not enough
without it.** The completion callback is not guaranteed to run on the async executor: `Schedule`
composes with `allOf(...).thenApply(...)` and the recorder attaches with `whenComplete`, and both
run *inline on the calling thread* when the futures they are given have already completed — which
for an empty or fast schedule is the thread building the plan, still inside `ProcedureExecutor`'s
transaction. That transaction has already read the root, so the "re-read" is answered from its
persistence context with the plan-time instance, and writing it back reverts whatever the run
committed meanwhile. A fresh transaction is what makes the re-read reach the row. It also makes the
read-modify-write atomic: on the async path the callback has no transaction at all, so read and
write would be two of them with room for a lost update in between.
`AggregateProvisioningStatusRecorderTests` stages the inline case deterministically rather than
racing for it, and fails with the root reverted if the write is put back inline.

**A run that never starts is recorded too.** Building the plan can fail before any future exists to
attach a completion to — a resource type with no registered factory, a cycle in the graph, a
throwing `DeferredTaskFactoryRegistrar` — and the root would then keep the `PROVISIONED` of the last
run that worked, which the UI and SPARQL both read. Both procedures therefore plan *inside*
`recordingAround`, which records `FAILED` before letting the error propagate. It stays silent when
the root is itself why planning failed (no such entity, or not a legitimate aggregate): there is
nothing to mark and the caller already has that error. That single wrapper is also what removed the
duplicated `ScheduleHandle` block the two procedures used to carry.

**The recorded message names the cause.** A failed task takes its dependents down with it, and each
of those is failed with a `DependencyFailedError` that says only that something else broke;
`Schedule` picks those last, since in a three-resource chain two of the three results are that
restatement and the task list is ordered by a `HashMap`. The message then carries its root cause
(`Provisioning failed for entity <id>: <why>`), because the task's own wrapper identifies the
resource but not the reason.

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
  `logging.level.root: ERROR`. An optional `delay` in its config entry pauses each resource
  between its start and done lines, simulating a slow real provisioning function — the docker
  profile sets `5s` so an asynchronous run's progress (the UI banner, the status endpoint) is
  actually observable instead of finishing before the first poll.
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

#### Authorization

Authorization is the same machinery over the second pair of traits, and the symmetry is deliberate:
`AuthorizationProcedure` and `RejectionProcedure` act on a root carrying `Authorizable`, plan over
the aggregate's `AuthorizableResource` members — leaves **and** intermediate nodes — and schedule
one task per resource. The tasks are the same `ProvisioningTask`s, from the same factories
registered per entity type name; they run `authorize()` / `reject()` instead of `provision()` /
`unprovision()` and record into the authorization field pair.

All four procedures are one class, `AbstractAggregateResourceProcedure`, and each supplies exactly
one answer: which `ProvisioningTask.Operation` it runs. Everything else follows from it. The
operation names a `BuiltInCapability` (core, next to `BuiltInTraits`) that owns the trait pair and
the status/result field pair, and carries the wiring direction itself — so a procedure *cannot*
pair an operation with the wrong capability's traits. That combination used to be expressible and
would surface only at the end of an otherwise successful run, as a validation refusal on the status
write, because a derived schema carries `additionalProperties: false`. `ResourceGraphBuilder` takes
the resource trait as a parameter for the same reason — the selection is the only thing that varies,
so no two procedures can disagree about what an aggregate contains. **The direction is the part
worth reading twice**, and it is the same
reasoning in both pairs: building up makes a resource wait for what it is derived from, tearing
down makes what it is derived from wait for *it*. An Athena table reading an S3 folder is created
after the folder and destroyed before it, and by the same token access to it is granted after — and
withdrawn before — access to the folder it reads, so nothing is ever left holding a grant on
something it can no longer reach. `AuthorizationProcedureTests` asserts both orders.

`authorize()` / `reject()` are **not abstract**, unlike `provision()` / `unprovision()`. A type may
well be a `ProvisionableResource` and never an `AuthorizableResource`, so forcing every task to
implement an authorization it does not have would buy nothing; instead the defaults fail the task
naming the class that did not override them, exactly as loudly as a resource type with no
registered factory fails. A task that *does* authorize must override both — the pairing argument
still holds within the pair, just not across the two capabilities. Both shipped tasks implement all
four: `stdout` prints `[authorizing]` / `[rejecting]` lines, and `script` invokes `bash <path>
authorize|reject`.

One consequence of the shared `Operation` is that `AggregateProvisioningStatusRecorder` keeps its
name while recording either kind of run: which field pair it writes on the root comes from the
operation, not from the class.

##### The access policy

Authorization is the one capability whose traits carry more than a status, and where that extra
lives is the whole design: **the root declares who gets what, and a resource declares nothing.**
`Authorizable` therefore adds three fields (declared in `AccessControl`, core, next to
`BuiltInCapability`):

- `principals` — the subjects this aggregate recognises, each `{id, type}` with `type` one of
  `USER` / `GROUP` / `ROLE` / `SERVICE`. They are **references into an external identity
  provider**, not entities in the catalog: a directory is something a catalog federates from, not
  something it owns, which is the conclusion Ranger, Lake Formation and Unity Catalog all reached.
- `permissions` — the vocabulary the aggregate can grant, and it is deliberately **abstract**
  (`READ`, `WRITE`) rather than any one catalog's native privilege names. One data product can
  publish output ports through two catalogs at once — the mixed demo does — and native names on the
  root would leave a consumer reading the product-level policy unable to tell whether the Iceberg
  half and the Hive half meant the same thing. Translating an abstract permission into what a given
  system understands is the *task's* job, which is exactly the split Ranger draws between a policy's
  accesses and a service definition's access types. A task handed a permission it cannot translate
  must fail its resource naming it.
- `grants` — the policy: `{principals[], permissions[], resources?}`. Both name lists reference the
  two declarations above **by name**, so a group named in four grants is spelled once.

`AuthorizableResource` gains one field, `effectiveGrants`, `readOnly` like the status pair: what the
last run actually applied there. That is the piece that makes access a **queryable catalog fact** —
"which tables may `sales-analysts` read?" is one jsonb query, one SPARQL query, answerable across
resources published by different catalogs through different tasks. A resource no grant reached
records `[]` rather than omitting the field: "decided, nobody" and "never asked" must not read the
same. On a failure the field is *cleared* rather than left holding the previous run's answer, so a
query never finds grants from a run that no longer describes reality.

A grant's optional `resources` selector narrows which members it reaches — `entityTypes` (the
universal filter: entities carry no name column, so the type name is the only label every resource
is guaranteed to have) and `values` (exact match on the resource's own values), combining with AND,
both absent meaning every resource. **Exact match rather than a path expression, deliberately**: the
selector is evaluated in memory against an aggregate the procedure has already read, so the Postgres
`jsonpath` that `EntityService.list` pushes into the database is not available, and reaching for
Jayway instead would put a *second* path dialect into the model under the same name — two syntaxes
for what looks like one concept, differing only in the cases nobody tests.

`AccessPolicy` (core, `service` package) parses the root's declaration and resolves it, and
**everything that can be wrong with a policy is wrong at plan time**: a grant naming an undeclared
principal or permission, or a selector reaching none of the aggregate's resources, is refused naming
it before a single task runs. Each of those is a typo whose only other symptom would be access
quietly not being granted — the same silence a task factory registered under a misspelled entity
type name used to produce. Since planning happens inside `recordingAround`, the refusal is recorded
as `FAILED` on the root rather than leaving the `AUTHORIZED` of the last run that worked.

The procedure resolves the policy **once**, inside the plan-building transaction, and hands each
task its own grants through `setAccessGrants` alongside `setOperation`. A task walking `IS_PART_OF`
upwards for itself would re-read and re-parse the same document once per resource, on whichever
thread the executor picked.

**The lifecycle stays binary, and that is the design.** Adding a policy makes the *content* of
`AUTHORIZED` richer; it does not turn the capability into a mutable grant set. Changing access means
editing the policy and re-running `authorize` — an idempotent re-apply that converges the target
systems on the declared state, which is how every declarative policy engine in this space works.
`REJECT` therefore reads no policy at all (`Operation.appliesAccessPolicy()` is true only for
`AUTHORIZE`): it means "no access to this aggregate", needs no list to say so, and so keeps working
as an emergency stop after the policy has been emptied or while it is malformed. It is also the only
reading under which "empty the grants, then reject" has an answer — a grant-diffing model could not
know what to withdraw.

`ScriptProvisioningTask` hands the grants to its script in the `METACATALOG_ACCESS` environment
variable, as the same JSON array `effectiveGrants` holds, and only on `authorize`. The environment
rather than standard input, which stays the entity's values for all four operations, so a script
that does not care keeps working unchanged; and not an argument, because arguments are visible to
every process on the host through `ps`. `ScriptAuthorizationTests` pins that contract — it is a
contract with shell scripts nothing in the build compiles against, so it would otherwise stay broken
until someone ran `docker compose up`.

`GET /metacatalog/v1/aggregate/{id}/access` answers both halves in one call: the policy the root
declares (intent) and each resource's recorded grants (fact). They can legitimately disagree —
editing the policy does not change what is in force until the aggregate is authorized again — and
being able to see that is the point, so the endpoint deliberately does *not* recompute the grants.

Metacatalog is the **policy administration point**, never the decision point: it is not asked at
query time whether alice may read a table. Pushing the declared decision into the systems that do
enforce it is what the `authorize` task is for. Note also the name collision worth keeping straight:
`metacatalog-security` authenticates callers of the catalog's own API, while this governs access to
the data products the catalog describes. They are unrelated today.

Both provisioning operations are exposed as `POST /metacatalog/v1/aggregate/{id}/provision` and
`.../unprovision`, and both authorization ones as `.../authorize` and `.../reject`. They are **synchronous by default** — `ProcedureExecutor.executeProcedure`
returns only once the schedule completes and throws if any task failed — so a 204 means the whole
aggregate is done. With `?async=true` the call instead returns **202 with a schedule id** right
after the plan-building transaction (a missing aggregate or an illegitimate root still fails
immediately, on the caller's thread), and the run is polled via `GET
/metacatalog/v1/procedure/{scheduleId}` → `RUNNING` / `SUCCEEDED` / `FAILED` + error.
`ProcedureExecutor.executeProcedureAsync` records the run in the `procedure_run` table — a durable
registry shared by every instance, so any replica can answer a poll and outcomes survive restarts.
A scheduled retention job (`cleanupProcedureRuns`, every 10 minutes) deletes terminal rows older
than `procedureRunRetention` (default 24h) and first marks `RUNNING` rows that old as `FAILED` —
a run that old has lost the instance executing it, and a visible failure beats an eternal spinner;
after deletion the id polls as 404. Both retention statements are idempotent, so concurrent
replicas need no coordination. The UI uses the async mode for provisioning (see the
[UI](#ui-metacatalog-ui) section); it does not yet surface the authorization pair. Nothing
provisions or authorizes on its own; it happens when asked.

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
- Capability listings: `GET /aggregate/provisionable-type` and `GET /aggregate/authorizable-type`
  return the entity types carrying `Provisionable` / `Authorizable`, directly or through the type
  and trait inheritance chains. Both are answered by `AggregateSchemaService` rather than worked
  out client-side: the walk needs an open transaction, and the `traits` on an `EntityType` DTO are
  only the directly associated ones, so a client checking them itself would miss an inherited
  capability.
- `GET /aggregate/type-capabilities` answers those two **and** the root-type question in one call,
  returning just the names (`aggregateRoot` / `provisionable` / `authorizable`). Each of the three
  individual endpoints costs a `findAll`, a mapping-target query per type and a walk of every
  type's and trait's father chains, so a client needing all three — the instances page does — paid
  for that walk three times. `AggregateSchemaService.typeCapabilities()` does it once, testing one
  trait-name set per type. The answers stay three separate lists: the capabilities are declared by
  independent pairs of traits, so none implies another, and `AggregateSchemaServiceTests` pins the
  combined answer against the three individual ones over a model that crosses them over. The three
  endpoints remain for clients wanting a single capability with the full DTOs.
- Access: `GET /aggregate/{id}/access` returns an `Authorizable` aggregate's declared policy — its
  principals, permission vocabulary and grants — together with, per `AuthorizableResource`, the
  grants the last authorization run actually applied there. Intent and fact are reported separately
  and the latter is never recomputed: editing the policy does not change what is in force until the
  aggregate is authorized again, and seeing that gap is the reason to ask. Refused with a 400 when
  the root does not carry `Authorizable`.
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
- Containment creation: `POST /trait/link/...` and `POST /entity/link/...` refuse a `HAS_PART` /
  `IS_PART_OF` that violates the [aggregate containment rules](#aggregate-containment-rules), and
  the trait / entity-type version endpoints refuse a version whose carriage change would leave an
  existing containment violating them.
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

The instances list is also where an aggregate is deleted, provisioned, unprovisioned, authorized
and rejected. **A row's actions are data, not markup**: each is an `AggregateAction` constant
carrying its path, button label, tooltip, confirmation text, the capability a row must have for it
to be offered, and — for the four procedures — the label flashed for the progress popup. The
controller attaches the constants that apply, the template renders them with a single `th:each`,
and the four procedures share one `@PostMapping("/{id}/procedure/{action}")` that looks the action
up by path (an unknown or non-procedure segment is refused, since the segment is now input rather
than something the routing table pins down). A third capability therefore adds enum constants and
nothing else — before this, it meant two more controller methods, two more near-identical `<form>`
blocks and a wider `InstanceRowView` that two unrelated call sites had to pad with empty sets.

`AggregateAction` deliberately does **not** reuse `ProvisioningTask.Operation`, whose four
constants it mirrors: that lives in `metacatalog-functions`, which this module's build excludes on
purpose. The overlap is the REST contract's, not a shortcut around the boundary.

A row gets *Delete aggregate* when its entity type is one of the aggregate root types — roots are
classified by the built-in traits (`Aggregate` without `AggregateElement`), so a root instance may
still hang off an entity outside the aggregate model (an Iceberg table off its namespace); deleting
such an aggregate is then refused with the outside-link error until that link is removed. It gets
*Provision* and *Unprovision* when its type is provisionable, and *Authorize* and *Reject* when it
is authorizable.

All three classifications are asked of the API rather than worked out in the UI, in **one** call to
`GET /aggregate/type-capabilities`. Whether a type is provisionable or authorizable depends on the
type **and** trait inheritance chains, both lazily fetched, so the walk only works inside a
transaction — `AggregateSchemaService.typeCapabilities()` does it there, once, and the endpoint
hands the UI all three answers. The `traits` on the `EntityType` DTO are only the directly
associated ones, so checking them here would miss an inherited `Provisionable`. **The capability
lists remain separate answers and neither stands in for the other** — they are declared by
independent pairs of traits, so a type may offer one, both or neither, and `UiControllerTest` pins
the crossed-over case that a single "has a lifecycle" flag would get wrong in both rows.

Provisioning and authorization from the UI are asynchronous: the controller calls the API with
`async=true`, flashes
the returned schedule id, and the instances page renders a `#procedure-banner` seed that
`procedure-poller.js` — included on **every** template — immediately consumes into
`localStorage` and replaces with a fixed-position popup naming the run and its target — label, entity type, aggregate id —
(spinner while `RUNNING`, color-coded success/failure that dismisses itself after a few
seconds, failures lingering longer). Because the active run lives in `localStorage` and the
script is site-wide, the popup survives navigating to any other UI page and shows up in every
open tab (a `storage` listener picks up runs launched elsewhere); each page polls
`GET /metacatalog/v1/procedure/{scheduleId}` every second — a same-origin XHR, authenticated by
the existing UI session in `basic`/`ldap` mode like every other API call the pages make. On the
instances page, completion also reloads so the rows show the finished `provisioningStatus`
values, the outcome popup surviving the reload through `localStorage`. Plan-building failures
(missing aggregate, root not `Provisionable` / not `Authorizable`) still surface immediately as a
flash error — the API validates the plan synchronously before returning the schedule id.

**Nothing in the poller knows which of the four procedures ran.** The controller flashes a label
(`Provisioning`, `Unprovisioning`, `Authorizing`, `Rejecting`) and the script renders it, which is
why adding the authorization pair needed no JavaScript change at all — the seed element, the
polling, the popup states and the cross-tab `storage` listener were already procedure-agnostic.

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
- `procedureRunRetention` (Duration, default 24h) — how long an async procedure run's status row
  in `procedure_run` stays pollable after its last write; also the staleness threshold after which
  an orphaned `RUNNING` row is marked `FAILED` (a cleaned-up id polls as 404)

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
  re-creates just the aggregate. The model probe checks **every** trait, entity type and trait
  relationship the file declares — one sentinel name standing in for the file silently skipped the
  load whenever the model gained something while keeping its first declaration, which is what left
  the Iceberg demo's `DEPENDS_ON` uncreated on older volumes. It also checks the **traits each
  entity type carries**, for the same reason one step further in: a model change can leave every
  declared *name* exactly as it was and still be a change, which is precisely what adding
  `Authorizable` / `AuthorizableResource` to both demo models did — without that check the loader
  reports the model fully present and the authorization demo silently does nothing on an older
  volume. (The DTO reports only directly associated traits, which is exactly what the file declares,
  so the two compare as they stand.) A model that is *partly* present
  fails the loader with `docker compose down -v` in the message rather than skipping: the bulk
  endpoint is all-or-nothing and dies on the first existing name, so a partial change is not
  something it can apply. (`Mappings` are not probed — there is no read-by-name for one.) **Schemas
  are not probed either, and deliberately not**: a schema change on a *built-in* trait is caught
  one level up, by `ImmutableModelInstaller`'s drift check, which aborts startup — so the loader
  never runs against a database the change would have broken. A schema change on a demo type is
  the same `down -v` either way. Both app healthchecks poll **`/actuator/health/readiness`**, not
  `/actuator/health`: the latter is UP as soon as Tomcat listens, during context refresh, whereas
  `ImmutableModelInstaller` is an `ApplicationRunner` and runs after it — so a loader gated on
  `depends_on: service_healthy` could post a model before the traits it builds on existed. Both apps
  therefore set `management.endpoint.health.probes.enabled`, which exposes that path *and* folds
  readiness into the aggregate (so the plain endpoint refuses during startup too, which is what makes
  the k8s `startupProbe` on it correct). Boot enables probes by itself when it detects Kubernetes,
  so `k8s/app-deployment.yaml`'s probes already worked; this gives Compose the same behaviour.
  `ReadinessProbeStartupTest` pins all of it by probing the app from inside its own startup — the
  contract is invisible to the rest of the build, and a mistyped path would leave a container
  permanently unhealthy rather than merely racing. `docker-compose.keycloak.yml` is an overlay (`-f` both files)
  that adds a Keycloak with a pre-imported realm (`docker/keycloak/`) and switches the app to
  `auth-mode: oauth2` in jwk-set-uri mode (client `metacatalog`/`metacatalog-secret`, user
  `demo`/`demo`, password grant for curl-friendly token fetching); the bulk-loader is disabled
  there because it only speaks HTTP Basic. `docker-compose.keycloak-sso.yml` stacks on top of
  that to enable browser SSO for the UI: issuer `http://localhost:8081` works from both sides
  because the browser hits Keycloak's published port while a socat sidecar in the app container's
  network namespace forwards its `localhost:8081` to Keycloak. `docker-compose.iceberg.yml` is
  another overlay (`make run-iceberg` / `up-iceberg-d`): it adds a RustFS S3 warehouse
  (S3 API on `:9000`, console on `:9001`, credentials `rustfsadmin`/`rustfsadmin`, bucket
  `iceberg-warehouse` created by a one-shot `rustfs-init` via the aws CLI) and the Iceberg REST
  catalog app on `:8181`, sharing the base stack's postgres. The base bulk-loader is disabled
  there (profile trick, like the Keycloak overlay; run the base file once first if you also want
  the S3/Athena demo) — instead an `iceberg-demo-loader` seeds the **Iceberg data-product demo**
  (`docker/bulk/iceberg-demo-*.yaml`, reusing `load-bulk.sh`): a `IcebergDataProductType`
  aggregate (`Provisionable`) whose three `IcebergTableOutputPortType` parts (a trait inheriting
  `ProvisionableResource`) each carry a namespace, table name and column list. Provisioning the
  aggregate — UI instances page or `POST /aggregate/{id}/provision` — runs the `script` task
  (wired in `application-docker.yaml`) once per port; the mounted
  `docker/provisioning/provision-iceberg-table.sh` drives the Iceberg REST catalog with curl/jq
  (namespace + table create on provision, purge-drop on unprovision, idempotent both ways), so
  the tables materialize in the catalog and are visible to PyIceberg/Spark/Trino, in the UI graph
  and over SPARQL. The script also connects each output port to the table provisioned from it:
  after creating the table it links the port `DEPENDS_ON` the materialized `IcebergTable`
  entity (both ends looked up by name/namespace through the metacatalog REST API — the app's
  own `localhost:8080`, credentials via `METACATALOG_*` env in the overlay), sanctioned by the
  `IcebergTableOutputPort DEPENDS_ON IcebergTableTrait` trait relationship the demo model
  declares (which is why the demo loader also waits for the iceberg-catalog to be healthy —
  that app's startup contributor creates `IcebergTableTrait`). On unprovision the link is
  removed *first*: dropping a table deletes it as an aggregate, which refuses while an outside
  link exists — the same check that refuses deleting the data-product aggregate while its
  tables are provisioned, so unprovision before delete. The runtime image ships
  `bash`/`curl`/`jq` for exactly this.
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
IcebergTableTrait` link, and entity types `IcebergNamespace` / `IcebergTable`. A table is a real
aggregate of its schema entities — `IcebergTableTrait` inherits `Aggregate`,
`IcebergTableSchemaTrait` inherits `AggregateElement` — so a schema can never be unlinked from its
table (`unlink` refuses aggregate containment) and dropping goes through `AggregateService.delete`.
The **namespace** trait deliberately stays outside the aggregate model: were it an `Aggregate` too,
the namespace→table link could never be unlinked and neither dropping a table nor renaming one
across namespaces would be possible. A namespace entity
stores `key` (levels joined with `\u001F`, the one character illegal inside an Iceberg namespace
level — a level may contain dots, so a dotted join would collide), `name` (dotted, display only),
`levels`, `properties`. A table entity stores `name`, `namespaceKey`, `metadataLocation`,
`previousMetadataLocation` and `metadata` (a cached copy of the current table metadata, browsable
in the UI/SPARQL — with the schema list stripped out, since schemas live as linked entities; the
server never reads this cache back, refresh loads the metadata file). Containment is a real
`HAS_PART` entity link, so tables hang off their namespace in the catalog graph. Table schemas are additionally materialized as first-class
entities: on every commit the registry appends one `IcebergTableSchema` entity per new schema-id
in the metadata (`table HAS_PART schema`, sanctioned by `IcebergTableTrait HAS_PART
IcebergTableSchemaTrait`), so schema evolution history is linkable and queryable in the graph.
The schema entity holds `schemaId` + an explicitly enumerated `columns` array (each entry
mirrors an Iceberg field: `id`, `name`, `type` — string for primitives, object for
struct/list/map — `required`, optional `doc`; plus optional `identifierFieldIds`) — no opaque
schema document, and deliberately no table or namespace name, so renames never leave stale
copies (the owning table is one `IS_PART_OF` hop away). Iceberg schemas are immutable per id,
so the sync is append-only and idempotent;
`dropTable` detaches the table from its namespace (legal — the namespace is not an aggregate) and
then deletes the table as an aggregate, taking the schema entities with it in one transaction.

**Storage split.** Standard Iceberg: the server writes table-metadata JSON files to the warehouse
via a `FileIO` (`application.config.iceberg.{warehouse,io-impl,io-properties}`); the entity holds
the metadata-location pointer. The built-in `LocalFileIO` (nio-based) handles `file://` without
Hadoop — iceberg-core's only local option is HadoopFileIO plus ~50 MB of hadoop-common — and
`S3FileIO` (iceberg-aws) serves any S3-compatible store; the `docker` profile targets the
RustFS added by the `docker-compose.iceberg.yml` overlay (`make run-iceberg`).

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
`pyiceberg-test/` is a uv-managed Python smoke project driving a **running** stack with PyIceberg
(real parquet appends via the S3 warehouse, scans, schema evolution, rename, drop): `make
run-iceberg`, then `cd metacatalog-iceberg-catalog/pyiceberg-test && uv run pytest`. It is not
part of the Maven build — it exists to test the catalog exactly the way a Python client does.
The same project carries `create_tables.py` (`uv run python create_tables.py`), an idempotent
seeder that creates a few demo tables with sample parquet data in a `demo` namespace, for
browsing the catalog from the UI/SPARQL or another Iceberg client (`--drop` recreates them).

## The mixed data-product demo (both catalogs at once)

`make run-hive-demo` (or `up-hive-demo-d`) stacks four compose files — base, Iceberg, Hive and
`docker-compose.hive-demo.yml` — and seeds **one data product whose output ports are published
through two different catalogs**: three as Iceberg tables in the REST catalog on `:8181`, and one as
an ordinary Parquet table in the Hive Metastore on `:9083`. Provisioning the aggregate materialises
all four; all four are entities under the same root in one graph, whichever catalog created them.

The data product is **also `Authorizable`**, and each port carries `AuthorizableResource` next to
its own port trait, so the same aggregate has a second lifecycle: `POST /aggregate/{id}/authorize`
resolves the [access policy](#the-access-policy) declared on the *root* into the grants that reach
each port, and stamps them onto the table that port produced — Iceberg table *properties*, Hive
table *parameters*, the same names either side — while `.../reject` withdraws them.

**The policy lives on the root and nowhere else**, which is what the demo is showing. Four ports do
not carry four audiences that can drift apart; there is one declaration, and which port a grant
reaches is said by the grant. The demo uses all three ways of saying it: the BI group reads every
port (no selector), CRM analysts read only `customers` (selected by `values`), finance reads only
the Hive port (selected by `entityTypes`), and the loading pipeline — a `SERVICE` principal —
holds `READ` and `WRITE` everywhere.

Those property names, and the rendering of the grants into them, are declared once in
`docker/provisioning/access-decision.sh`, sourced by both scripts, and the Hive side *passes them
into* `hive-cli` rather than the jar holding constants of its own: the whole point is that a
consumer of either catalog reads the same answer, and a Java constant on one side could have
drifted from a shell literal on the other with nothing failing — the two catalogs would just have
quietly stopped agreeing. `authorize` writes `access.status` plus three value keys
(`access.granted-to`, `access.permissions`, `access.grants` — who at all, which permissions at all,
and the precise mapping the first two flatten), and `reject` removes exactly the same three from one
shared list, so no key can be written by one direction and left behind by the other. Three fixed
keys rather than one per permission, deliberately: a permission name is arbitrary text from the
policy, and turning it into a property key would put the policy's vocabulary into the catalogs' key
space, where a rename would strand the old key on every table already stamped with it.

Because both are ordinary catalog writes, the decision is visible to PyIceberg / Spark / Trino and
to a Hive client, and (the Iceberg catalog caching each table's metadata on its `IcebergTable`
entity) in the UI graph and over SPARQL. Authorizing before provisioning fails naming the table that
does not exist yet; rejecting something never provisioned is a no-op, exactly as unprovisioning is.

Note the ports mix `AuthorizableResource` in at the **entity type**, not through their port trait:
a trait has single inheritance and `IcebergTableOutputPort` already inherits
`ProvisionableResource`. Two capabilities therefore means two traits, which is what a type's trait
list is for.

Four things about it are worth knowing before changing it:

- **The metastore speaks Thrift, so the provisioning script cannot be curl.** It shells out to
  `docker/provisioning/hive-cli/`, a small shaded Thrift client built out of the reactor by `make
  hive-cli` (in a container — the demo needs no JDK on the host) and **mounted** into the app
  container rather than baked into the shipped image, since it is 34 MB of Hive classes only the
  demo wants. It uses Thrift's *generated* client rather than `HiveMetaStoreClient`, which is what
  lets it run on the image's JDK 26 at all.
- **One script task, two kinds of port.** `application.config.provisioning.tasks` is keyed by task
  name, so the `script` task has exactly one `path`. `provision-output-port.sh` is a dispatcher: it
  reads the port's shape — `namespace` means Iceberg, `database` means Hive — and delegates. Change
  either schema and that line changes with it. The same one script serves all four operations: the
  task passes the operation through as `$1`, so `authorize` / `reject` reach the same dispatcher and
  the same two scripts.
- **Hive has no grant a bare metastore can honour.** Authorization in the Hive world lives in Ranger
  or in the query engine, so the CLI records the decision in the table's own parameters rather than
  pretending to a privilege system. That is also why it has to *read the table first*:
  `alter_table` replaces the row wholesale, so `HiveCli.alter` takes the loaded table and hands it
  back with everything else intact.
- **The Parquet table has no data behind it.** It is an external table describing a location a
  pipeline fills, which is how Hive external tables are normally used, and it is what makes the port
  readable by a Hive consumer without the demo needing a Parquet writer. What makes it *Parquet* to
  a query engine is the input/output format and SerDe, which the CLI sets.

**Every port is linked to the resource it produced**, on both sides: after materialising a table the
script links `port DEPENDS_ON <table entity>` — `IcebergTable` for the Iceberg ports, `HiveTable`
for the Hive one — so the graph shows what each port's contract is actually fulfilled by. Each is
sanctioned by a trait relationship the demo model declares (`IcebergTableOutputPort DEPENDS_ON
IcebergTableTrait`, `HiveTableOutputPort DEPENDS_ON HiveTableTrait`), and since both target traits
are declared by the *other* applications' startup contributors, the demo loader waits for both
services to be healthy before posting the model.

The lookup, link and unlink are shared between the two scripts in `metacatalog-api.sh`. Their
subtleties — a miss must be empty rather than fatal so unprovision tolerates a catalog that was
never provisioned, more than one match is fatal rather than a guess, and each invocation needs its
own response file because an aggregate's resources provision in parallel — were paid for once.

Unprovisioning removes the link **before** dropping, and the order is load-bearing: the drop deletes
the table as an aggregate, and `AggregateService` refuses that while a member is linked from outside
it. It then drops the tables from both catalogs and leaves the Hive **database** behind on purpose:
sibling ports share it, and the metastore refuses to drop a non-empty one.

Authorizing and rejecting touch no links at all: they neither create nor destroy a table, they only
record a decision about the one provisioning already made.

## Hive Metastore (metacatalog-hive-metastore)

A third deployable application: `HiveMetastoreApplication` speaks the **Apache Hive Metastore Thrift
protocol** on port **9083** (the Hive default, so clients need no unusual configuration), with HTTP
on **8282** carrying nothing but the actuator probes. It shares the main app's database, depends on
**`metacatalog-core` only** among internal modules, and — like the Iceberg app — has no
`@EnableScheduling`, because the mapping updater belongs to the main application.

It is a **separate implementation, not a view over the Iceberg registry**. It declares its own model
and owns its own entities; the two catalogs coexist in one database and one graph, and either side's
objects can be linked to anything else because both are just entities. A Hive database created over
Thrift is visible immediately through the main app's REST API, in `/ui/graph` and over SPARQL, which
is the point of storing metastore objects as entities rather than in tables of their own.

**Model.** Declared immutable at startup by `HiveModelContributor`: traits `HiveDatabaseTrait`,
`HiveTableTrait` (inherits `Aggregate`) and `HivePartitionTrait` (inherits `AggregateElement`), the
two `HAS_PART` links between them, and entity types `HiveDatabase` / `HiveTable` / `HivePartition`.
Two boundaries carry the weight:

- **A table is a real aggregate of its partitions.** `EntityService.unlink` refuses to detach a
  partition — it would strand it — and dropping a table goes through `AggregateService.delete`,
  which removes the table and its partitions in one transaction. A stock metastore has to remember
  to cascade; here the model will not let you forget.
- **The database trait deliberately carries neither built-in trait.** Were it an `Aggregate`, the
  database→table link could never be unlinked, so neither dropping a table nor renaming one into
  another database would be possible. This is the same conclusion the Iceberg module reached about
  namespaces, for the same reason.

Entity values are JSON-Schema validated on every write — storage descriptors, serde info, partition
keys and all. The schemas are **inlined rather than factored into `$defs`/`$ref`**, and that is
load-bearing: `JsonUtils.mergeSchemas` emits a fresh node carrying only `type`, `properties`,
`required` and `additionalProperties`, so `$defs` does not survive into the derived schema and every
reference would dangle. They are composed from Java constants instead, so there is still one source
of truth, and `HiveModelTests` covers a fully populated table specifically so that factoring them
back out fails loudly.

**Registry.** `HiveRegistryService` is the only class touching core services, one transaction per
operation. Entities have no name column and no uniqueness constraint on their jsonb values, so name
uniqueness is enforced with a transaction-scoped advisory lock keyed on the name (via core's
`RegistrySupport.lockId`, which avoids the ids core reserves) plus a re-check under it; reads push a
jsonpath predicate into PostgreSQL via `RegistrySupport.jsonPathString`. A rename takes both table
locks in ascending id order so two concurrent renames cannot deadlock. It throws the **unchecked**
types in `HiveErrors`, not Thrift's own: Thrift's are checked, and Spring rolls back on unchecked
exceptions only, so throwing one partway through would commit the half-written state the transaction
exists to prevent.

Dropping a *single* partition deliberately goes around `EntityService.unlink`, removing the
relationship row and the entity directly — exactly what `AggregateService.delete` does internally.
The public API still cannot strand a partition; this is the component that owns these entities
tearing one down on purpose.

**Protocol layer.** `ThriftHiveMetastore.Iface` has several hundred methods and extends fb303's
service interface, so implementing it literally would mean a stub file larger than the rest of the
module. `HmsIfaceProxy` satisfies it with a dynamic proxy dispatching **by name** to
`MetacatalogHmsHandler`, which declares only the supported operations; anything else is refused
naming the method. That trades a compile error for a runtime failure when Hive renames something, so
`HmsIfaceProxyTests` resolves every name, *signature* **and return type** the handler claims against
the live interface. It is not optional — it immediately caught that Hive 4 replaced
`get_table(db, name)` and `get_table_objects_by_name` with `get_table_req(GetTableRequest)` and
`get_table_objects_by_name_req(GetTablesRequest)`.

The return-type half was added later, and paid for itself the moment it ran. A handler method
returning `void` where the operation returns a result struct makes the proxy hand Thrift a `null`
success field, and the client gets `TApplicationException: <op> failed: unknown result` — a message
that names the operation and says nothing else. Two methods were wrong that way: `alter_table_req`
(`void`, but the request-object form returns `AlterTableResponse` — so **every** `alterTable` through
a real client failed, while the flat `alter_table` a generated client calls was fine), and
`getStatus` (returning fb303's status as an `int` rather than `fb_status`). Both are exactly the
shape the parameter check cannot see.

Databases, tables and partitions are supported read-write. **Deliberately refused**, each naming its
limitation: `get_partitions_by_filter` / `get_partitions_by_expr` (a Hive filter-expression parser is
its own project), transactions and locks, statistics, materialized views and the notification log. A
plausible-looking wrong answer — empty statistics, an empty partition list — would have clients
making decisions on it.

`HiveMetastoreThriftServer` is a `SmartLifecycle`, not a `@PostConstruct` bean, for two reasons: it
starts after context refresh so a client cannot reach a half-built application, and it never runs
during the image build, where the AOT training run boots with `spring.context.exit=onRefresh` and a
server bound in a constructor would try to take 9083 inside the builder. Its phase puts it after the
web server, so readiness only flips once the Thrift socket is accepting — which is what the Compose
healthcheck gates on.

**Two client conventions the flat protocol hides**, both found by driving the server with the real
client and invisible to Thrift's generated one:

- **Hive 4 moved most operations to request objects.** `HiveMetaStoreClient` calls
  `create_database_req`, `get_database_req`, `create_table_req`, `alter_table_req`,
  `drop_table_req`, `add_partitions_req`, `fetch_partition_names_req` and friends — not the flat
  forms. Both are implemented; the flat ones are still on the interface and older clients use them.
- **A catalog-qualified database is encoded into the name string** as `@hive#sales`, and "every
  database" as `@hive#` (`MetaStoreUtils.prependCatalogToDbName`). A server that treats the whole
  string as a name returns an empty catalog *with no error*, which is exactly what a real client
  saw here. `HiveCatalogNames` strips the prefix; this metastore has one catalog and does not model
  the concept.
- **Several operations have an environment-context variant the client prefers.** `getSchema` and
  `getFields` call `get_schema_with_environment_context` / `get_fields_with_environment_context`,
  never the flat forms. Found by the mixed demo, not by any in-build test — the pattern by now is
  that anything only a real client exercises is only ever found by a real client.
- **The request-object forms carry result structs the flat ones do not.** `alter_table` is `void`;
  `alter_table_req` returns `AlterTableResponse`. Getting that wrong is invisible to a generated
  client calling the flat form and fatal to every real one. Found the same way — by the demo's Hive
  port trying to alter a table — and now pinned by the return-type check in `HmsIfaceProxyTests`.

**Tests.** `HiveModelTests` pins the model and its schemas; `HiveRegistryServiceTests` the registry,
including a four-thread race where exactly one caller creates the table; `HmsIfaceProxyTests` the
dispatch guard; `HiveMetastoreEndToEndTest` boots the real app and drives it over a real Thrift
socket. That last one uses Thrift's **generated client**, not `HiveMetaStoreClient`, and not by
choice: `HiveMetaStoreClient` calls `Subject.getSubject()`, which throws on JDK 24+, and
`-Djava.security.manager=allow` is *rejected at VM startup* on JDK 26. It cannot run in this build.
Real consumers run their own JVMs and are unaffected — which is what
`metacatalog-hive-metastore/hive-test/` is for: a Maven project outside the reactor that drives a
*running* stack with the real `HiveMetaStoreClient` on JDK 21, in a container, exactly as
`pyiceberg-test/` does for the Iceberg catalog. `make up-hive-d`, then `./hive-test/run.sh`. It is
not optional decoration: it is what found both client conventions above, and each of them made the
metastore silently useless to a real consumer while every in-build test stayed green.

The module's own `@SpringBootTest`s bind the Thrift server to a free port rather than 9083, because
the lifecycle bean starts with the context and would otherwise fight a stack the developer has
running. Note `HiveMetastoreProperties` reads a non-positive port as "unset" and substitutes 9083,
so 0 does not mean "ephemeral" there.

**Dependencies.** `hive-standalone-metastore-common` unfiltered resolves 128 jars / 132 MB: two
Hadoop client bundles, a transitive `hadoop-common` **2.6.0** behind a metrics reporter, an embedded
Derby, DataNucleus, ORC and a gRPC stack. The module excludes all of it — a metastore that stores
nothing itself needs none of it — leaving the whole classpath at 127 MB, less than the Iceberg
module's 162 MB, with guava back at the parent-managed version. Keep the exclusions.

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
- Java 26 + the JDK-internal compiler `--add-opens` args in the compiler plugin are required for the
  toolchain (Google Java Format / Lombok) — keep them when touching the parent `pom.xml`.
```
