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
- **Task engine:** `TaskManager`, `Task`, `TaskFactory` — registers typed task factories, builds
  dependency graphs with **JGraphT**, detects cycles (`CycleDetector`), and executes schedules
  asynchronously (results cached with Caffeine). `AsyncTaskExecutor`-backed.
- **`MappingUpdaterService`** — `@Scheduled` job that reacts to `EntityLifeCycleEvent`s to create /
  update mapped entities. Gated by `application.config.automaticEntitiesMapping` and guarded by an
  advisory lock so only one instance runs at a time.

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

When running:
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Spec: `http://localhost:8080/api/interface-specification.yaml`
- Actuator: `/actuator/health`, `/actuator/info`, `/actuator/metrics`

## Configuration

Custom properties bind under the `application.config` prefix into
`CoreConfigProperties` (a record):

- `automaticEntitiesMapping` (boolean) — enable the scheduled mapping updater
- `updateMappedEntitiesSchedulingInterval` (Duration)
- `entityLifeCycleEventCleanupSchedulingInterval` (Duration)
- `entityPathResolutionMaxAttempts` (int)

Defaults live in `application.yaml`; `docker` and `kubernetes` profiles override datasource wiring.
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

## Ontop (optional ontology layer)

Mapping/ontology files: `metacatalog-core/src/main/resources/ontop/{mapping.obda, ontology.owl}`.
Ontop CLI v5 exposes a SPARQL/R2RML endpoint over the same Postgres DB (see README for the exact
`ontop endpoint` / `ontop bootstrap` invocations). Not required to build or run the core app.

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
