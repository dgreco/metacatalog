# AGENTS.md

Instructions for coding agents working in this repository. Read this before changing anything.

`README.md` is the reference for *what the system does* and for every REST API — it is long and it
is kept accurate, so consult it rather than guessing, and update it when behaviour changes. This
file is about *how to work here*: the layout, the commands, the boundaries that must hold, and the
conventions the code is written to.

## What this is

Meta Catalog is a metadata management system: entities, entity types, traits, relationships,
mappings and aggregates, with a REST API, a server-rendered UI, a SPARQL endpoint, an Iceberg REST
catalog and a Hive Metastore over the same model.

Java 25, Spring Boot 4.1.0, PostgreSQL 18+, Maven 3.9.9+. A JDK 25+ builds it.

## Layout

Ten Maven modules. `README.md` § *Project Structure* has the annotated tree; what matters when
changing code:

| Module | Role |
|---|---|
| `metacatalog-core` | The domain: entities, types, traits, versioning, mappings, aggregates, repositories, services. Everything else sits on top of it. |
| `metacatalog-functions` | Procedures that run over entities — `EntityProcedure`, `ProcedureExecutor`, and the provisioning / authorization procedures. |
| `metacatalog-functions-provisioning-tasks` | Concrete provisioning tasks and their registrars. |
| `metacatalog-openapi` | The API. Controllers and a client are **generated** from the spec. |
| `metacatalog-ui` | Thymeleaf pages, served by the application on the same port. |
| `metacatalog-security` | Pluggable auth: `none` / basic / oauth2 / ldap. |
| `metacatalog-sparql` | Embedded Ontop SPARQL 1.1 endpoint + Yasgui. |
| `metacatalog-application` | The Spring Boot app that assembles the rest. |
| `metacatalog-iceberg-catalog` | Independent app: Iceberg REST catalog. Own port and image. |
| `metacatalog-hive-metastore` | Independent app: Hive Metastore Thrift server. Own port and image. |

## Commands

```bash
mvn clean install                 # build + unit tests; CI runs `mvn clean install spotless:check test -B`
mvn verify                        # everything, integration tests included
mvn spotless:apply                # format — the build fails on unformatted code
mvn -pl metacatalog-core test     # one module
mvn test -Dtest=TaskManagerTests -pl metacatalog-core   # one class
```

Most tests need a **running Docker daemon**: anything `@SpringBootTest` runs against a real
PostgreSQL 18.4 through Testcontainers — the database is never mocked. A few suites are plain unit
tests with no Spring context at all (`TaskEngineTest`, for instance); those need nothing.

The stress test is tagged `stress` and excluded from `mvn test`, `mvn verify` and CI:

```bash
mvn test -Pstress-tests -pl metacatalog-functions
mvn test -Pstress-tests -pl metacatalog-functions -Dstress.aggregates=1000
```

Running the stack locally is `make` — see the `Makefile`, which is commented and is the source of
truth: `make up-d` (base), `run-keycloak`, `run-iceberg`, `run-hive`, `run-hive-demo`, `down`,
`logs`, `ps`, `info`. Always build from source (`--build`); the targets already do.

## Boundaries — do not cross these

**The UI must not reach past the REST API.** `metacatalog-ui` goes through the API exclusively, so
it exercises the same contract external clients do. `metacatalog-core` and `metacatalog-functions`
are *excluded* from its compile classpath in `metacatalog-ui/pom.xml`, which turns a stray
`import ...metacatalog.service.*` into a compile error rather than a convention. If a page needs
something, expose it as an endpoint first. Never "fix" this by deleting the exclusions.

**Controllers are generated, not written.** The OpenAPI spec at
`metacatalog-openapi/src/main/resources/static/api/interface-specification.yaml` is the source; the
`openapi-generator-maven-plugin` produces `...openapi.controller` and `...openapi.model`, plus a
client. Change the spec, not the generated code — edits to generated sources are lost on the next
build. Hand-written code implements the generated delegate interfaces.

**Schema changes are Flyway migrations.** `metacatalog-core/src/main/resources/db/migration/`. Never
edit an applied migration; add a new one. Hibernate does not own the schema.

**The immutable model is code, applied at startup.** A module declares the traits, entity types and
trait relationships it depends on through `ImmutableModelContributor`, and `ImmutableModelInstaller`
applies them idempotently. Frozen items cannot be deleted or versioned. Add to the contributor
rather than seeding data by hand.

**The two independent applications stay independent.** `metacatalog-iceberg-catalog` and
`metacatalog-hive-metastore` have their own ports, images and lifecycles. They read the catalog;
they are not part of the main app.

## Conventions

**Formatting** is Google Java Format via Spotless, bound to `validate`, so an unformatted file fails
the build. Run `mvn spotless:apply` before committing; never hand-format, and never reformat code
you did not otherwise touch.

**Lombok is used** here (`@Getter`, `@Slf4j`, `@RequiredArgsConstructor`, …) — unlike in sibling
projects. Follow what the surrounding file does.

**Javadoc on public API**, including record components and enum constants.

**Comments explain *why*, never *what*.** This is the strongest convention in the codebase: where a
line looks odd, there is a comment naming the failure it prevents — see
`CommonServiceTestingSupport.beforeAll` (the Flyway `lock_timeout` deadlock) or
`Task.schedule` (the thread-pool starvation it avoids). Match that register. Do not add comments
that restate the code.

**Errors.** `ServiceError` is the service-layer base; `NotFoundException` extends it and the API
layer maps it to 404 rather than the generic 400. Database constraint violations go through
`ServiceError.forDataIntegrity`, which deliberately does not leak constraint or column names to the
client while keeping the original as the cause.

**`io.vavr.control.Try`** is how the service layer carries outcomes that must be inspected rather
than thrown. **JGraphT** does the graph work. Prefer them over hand-rolled equivalents.

**Naming.** Lifecycle event constants use the `ENTITY_` domain prefix (`ENTITY_SOURCE_CREATED`), not
an `EVENT_TYPE_` prefix.

## Tests

Conventions that matter:

- Integration tests extend the module's `CommonServiceTestingSupport`, which owns a **singleton**
  Testcontainers PostgreSQL shared across every test class. It is intentionally never stopped per
  class — stopping it would kill the database while Spring's cached context still points at the old
  port. Flyway cleans and re-migrates between classes.
- Because the context is cached and shared, a test must not assume a clean database beyond what that
  base class restores. Tests are written to be idempotent (create-if-missing), not order-dependent.
- Anything that could hang gets a `@Timeout` — a deadlock should fail the build, not stall it.
- Tag anything long-running `stress` so it stays out of `mvn test` and CI.
- When you fix a bug, write the failing test first, confirm it fails without the fix, then fix it.

## CI

Two pipelines, one logic. GitHub Actions (`.github/workflows/ci.yml`) and GitLab CI
(`.gitlab-ci.yml`) both call `ci/build.sh` — `mvn clean install spotless:check test -B` — and then
`ci/publish-images.sh`, which on `main` / `master` / `develop` builds and pushes the application,
Iceberg catalog and Hive Metastore images (GitHub to GHCR, GitLab to its own registry). Change
pipeline behaviour in the scripts, not in the platform files, so the two cannot drift. GitLab needs
a Docker-in-Docker service for Testcontainers; the GitHub runner has a daemon. Keep the build green
without Docker-daemon assumptions beyond Testcontainers.

`LICENSE` is Apache 2.0 and `CONTRIBUTING.md` / `SECURITY.md` / `CODE_OF_CONDUCT.md` are the
public-facing contributor documents; keep them consistent with this file when conventions change.

## Before you finish

1. `mvn spotless:apply`
2. `mvn clean install` (or `mvn verify` if you touched anything integration-level)
3. Update `README.md` if behaviour, API or configuration changed — it is the user-facing contract.
