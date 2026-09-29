# Contributing to Meta Catalog

Thank you for taking the time to contribute. This document covers the mechanics: how to set up,
what a change must satisfy before it is merged, and how to submit it. `AGENTS.md` describes the
codebase conventions in depth and `README.md` is the reference for behaviour and every REST API;
both are kept accurate, so read them before changing anything.

## Ground rules

- Be respectful. This project follows the [Code of Conduct](CODE_OF_CONDUCT.md).
- Open an issue before starting large work, so the design can be discussed first.
- Security problems go to the process in [SECURITY.md](SECURITY.md), not to the public tracker.
- By contributing you agree that your contributions are licensed under the
  [Apache License 2.0](LICENSE), the license of the project.

## Prerequisites

- JDK 25 or newer
- Maven 3.9.9 or newer
- A running Docker daemon: integration tests start a real PostgreSQL through Testcontainers, and
  the local stack (`make up-d`) builds the application image from source.

## Building and testing

```bash
mvn spotless:apply               # format; an unformatted file fails the build
mvn clean install                # build + unit tests (what CI runs, plus spotless:check)
mvn verify                       # everything, integration tests included
mvn -pl metacatalog-core test    # a single module
mvn test -Dtest=TaskManagerTests -pl metacatalog-core   # a single class
```

Long-running tests are tagged `stress` and only run on demand:

```bash
mvn test -Pstress-tests -pl metacatalog-functions
```

## What a change must satisfy

These are the boundaries the codebase is built around. A pull request that crosses one will be
asked to change, however good the rest is.

- **The UI only talks to the REST API.** `metacatalog-ui` cannot see `metacatalog-core`; if a
  page needs something, expose it as an endpoint first.
- **Controllers are generated from the OpenAPI spec** at
  `metacatalog-openapi/src/main/resources/static/api/interface-specification.yaml`. Change the
  spec, never the generated sources.
- **Schema changes are new Flyway migrations.** Never edit an applied migration.
- **Comments explain why, not what.** Where a line looks odd, a comment names the failure it
  prevents. Do not add comments that restate the code.
- **Javadoc on public API**, including record components and enum constants.
- **Bug fixes come with a failing test first.** Confirm it fails without the fix, then fix it.
- **Anything that could hang gets a `@Timeout`.**
- **`README.md` is updated** whenever behaviour, API or configuration changes.

## Submitting a change

1. Fork the repository and create a branch from `main`.
2. Make the change, with tests.
3. Run `mvn spotless:apply`, then `mvn clean install` (or `mvn verify` if you touched anything
   integration-level) and make sure both pass.
4. Write commit messages in the imperative, with a short prefix when it helps
   (`fix:`, `docs:`, `build:`, `refactor:`), and explain *why* in the body when it is not obvious.
5. Open a pull request against `main`. Describe what changed, why, and how it was tested. Link the
   issue it addresses, if any.

Continuous integration runs the same build on GitHub Actions and GitLab CI, from the shared
scripts in `ci/`. A pull request must be green before it is reviewed.

## Reporting bugs and requesting features

Use the issue tracker on GitHub. For bugs, include the version (from `/actuator/info` or the
startup log line), the steps to reproduce, what you expected and what happened instead. Logs and
a minimal reproduction help far more than a description.
