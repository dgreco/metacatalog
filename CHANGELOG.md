# Changelog

All notable changes to Meta Catalog are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html): from 1.0.0 on, a breaking change to the
REST API, the database schema or the configuration needs a new major version. How releases are
cut is described in the README, under *Releases and Versioning*.

Changes go under **Unreleased** in the same merge request that makes them; cutting a release
renames that section to the new version.

## [Unreleased]

## [1.0.1] - 2026-10-21

A maintenance release: dependency and build-plugin upgrades, with no change in behaviour, API,
schema or configuration. A 1.0.0 database runs on it as it is.

### Changed

- **Dependencies.** Flyway 13.11.1, the PostgreSQL JDBC driver 42.7.14, Tomcat 11.0.27 (the
  embedded server and its connection pool), Hibernate ORM 7.4.13, Hibernate Validator 9.1.5,
  Jackson 2.21.8 and 3.1.8, Netty 4.2.19, SLF4J 2.0.20, Byte Buddy 1.18.15, Apache HttpClient 5.6.5
  and HttpCore 5.4.4, webjars-locator-lite 1.1.5; Selenium 4.51.0 for the tests.
- **Build plugins.** maven-compiler-plugin 3.16.0, maven-dependency-plugin 3.11.0 and
  maven-install-plugin 3.2.0.

## [1.0.0] - 2026-10-20

The first release. Meta Catalog manages metadata as entities, entity types, traits,
relationships, mappings and aggregates, and serves the same model through a REST API, a web UI, a
SPARQL endpoint, an Iceberg REST catalog and a Hive Metastore.

### Added

- **Model.** Entity types and traits with JSON Schema validation, inheritance and an immutable
  core installed from code at startup; entities pinned to the type version they were validated
  against; relationships between entities sanctioned by relationships between their traits.
- **Versioning.** Every change to a type or trait records a version that stays readable and
  deletable. Instances of `Versionable` types keep a history of their values; a versionable
  aggregate root versions its whole aggregate at once.
- **Mappings.** Rules that derive target entities from source entities with SpEL expressions and
  relationship paths, re-evaluated automatically when a source changes.
- **Aggregates.** Trees of parts authored as one YAML document against a generated schema, read
  and deleted as a whole.
- **Provisioning and authorization.** Two independent lifecycles run over an aggregate's
  resources in dependency order, by tasks registered per entity type (stdout and script tasks
  included), synchronously or as tracked asynchronous runs. Authorization resolves the grants of
  an access policy declared on the aggregate root.
- **Runnable past versions.** Each past version of a versionable aggregate is a frozen copy
  that provisioning and authorization can still run on, and that nothing else can change. Past
  versions can be deleted one at a time or all at once.
- **Referential safety.** Deletes that would strand an entity, dangle a link or break a
  mapping's path resolution are refused, with an exact per-link check.
- **Interfaces.** An OpenAPI-documented REST API; a server-rendered web UI with schema-driven
  editors, a catalog graph and lifecycle actions; an embedded Ontop SPARQL 1.1 endpoint with a
  Yasgui query page.
- **Iceberg REST catalog and Hive Metastore.** Two independent applications that let Spark,
  Trino, PyIceberg and Hive use the catalog as their table catalog or metastore, recording every
  namespace, table, schema and partition as a catalog entity.
- **Security.** Pluggable authentication: none, HTTP Basic, OAuth2 (JWT, with browser SSO) or
  LDAP.
- **Operations.** Container images for the three applications (amd64 and arm64), Docker Compose
  stacks for local and demo use, Kubernetes manifests, and identical CI on GitLab and GitHub.

[Unreleased]: https://github.com/dgreco/metacatalog/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/dgreco/metacatalog/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/dgreco/metacatalog/releases/tag/v1.0.0
