# Changelog

All notable changes to Meta Catalog are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html): from 1.0.0 on, a breaking change to the
REST API, the database schema or the configuration needs a new major version. How releases are
cut is described in the README, under *Releases and Versioning*.

Changes go under **Unreleased** in the same merge request that makes them; cutting a release
renames that section to the new version.

## [Unreleased]

The catalog becomes a place to manage data platform assets. Everything is additive: the REST API
only gains endpoints and fields, and the one schema change is a migration that needs no data moved,
so a 2.0 database runs on this as it is. Pages of the web UI have moved, and their old addresses
still answer.

### Added

- **Assets.** A data platform asset is an aggregate whose root type carries the new built-in
  `Asset` trait, which inherits `Provisionable` and mixes in `Authorizable` — so an asset type gets
  both capabilities, and is an aggregate root, by naming one trait. `GET /aggregate/asset-type`
  lists the asset types, `GET /asset` the catalog: every asset with what it depends on, its
  parts — each with what contains it and what it depends on — and the entities mappings derived
  from its members.
  `GET /aggregate/type-capabilities` reports the asset types alongside the other classifications.
  A database created by an earlier release gains `Asset` on its first start.
- **What an asset depends on, decided by the model.** An asset can depend on another asset, on an
  aggregate or on a plain entity: a `DEPENDS_ON` link from its root, legal only when the two types
  carry traits related by `DEPENDS_ON`. `GET /asset/dependency-rule` lists, for every asset type,
  the types its assets may depend on, what each is and the trait relationships that allow it, so a
  client can tell in advance instead of from a refused link; `GET /asset/dependency-target` lists
  the aggregates and entities that qualify.
- **Traits inheriting from several traits.** A trait has a father and, now, any number of mixed-in
  traits (`traits` on the `Trait` resource, in the bulk YAML and in `ImmutableModelRegistry`),
  linearized as an entity type's ancestors are, so a shared ancestor contributes once. A parent
  that another parent already inherits is refused as redundant, with a message naming what to
  drop. In SPARQL a mixed-in trait is `mt:hasTrait`.
- **Updating an aggregate as a whole.** `PUT /aggregate/{id}/yaml` updates an aggregate in place
  to match a document, in one transaction and as one new version: values of the members it lists,
  parts it adds, parts it no longer lists, and the dependencies it declares. The fields a run
  writes are never taken from the document.
- **The Assets half of the web UI.** A catalog shown as a list of assets or as a dependency graph
  that also draws the aggregates and entities assets may depend on; drag and drop to make an asset
  depend on any of them — accepted only where the model allows it, with the reason shown where it
  does not; a right-click on the graph's board to create a new asset, or a new thing to depend on,
  in place; each asset's mapped entities, named in the list and drawn below it in the graph with
  the status a run left on them; each asset's inside in the graph — its parts and the
  dependencies between them; an asset's page with its structure, lifecycle actions, mappings
  and access; the schema-driven editor on an existing asset; and YAML download and upload.
- **A model graph.** Under Administration, every trait and entity type as a card laid out in rows
  — traits above the types assembled from them — with inheritance, mixed-in traits, carried
  traits, relationships and mappings as edges, filters per kind of edge, search, and a panel
  describing the selected node or edge.

### Changed

- **The web UI is split in two.** Assets (`/ui/assets`, where `/ui` now lands) and Administration
  (`/ui/admin`), which holds everything the UI had before: the model, the graph, instances,
  aggregates and bulk upload. The addresses those pages had directly under `/ui` redirect to their
  new place.
- **Deleting an aggregate no longer requires removing its own dependencies first.**
  `DELETE /aggregate/{id}` used to be refused for any link to an entity outside the aggregate. It
  is now refused only when an outside entity depends on a member or contains one; the aggregate's
  own dependencies on outside entities are removed with it. An asset that depends on another can
  therefore be deleted, one that others depend on cannot.
- **A downloaded aggregate uploads as a faithful copy.** `POST /aggregate/yaml` reads a node's
  `id` as a second `ref` and its `dependencies` as more `dependsOn` names, and takes a name no node
  declares as the id of an existing entity. It used to ignore both fields, so a document served by
  `GET /aggregate/{id}/yaml` created a copy with its dependencies silently gone.
- **Adding a dependency derives what could not be derived without it.** A mapping may read
  through a dependency — a resource named after the domain its data product belongs to. An entity
  created before it had that dependency used to stay without its derived entity until it happened
  to be edited; linking it now asks the mapping engine for whatever it, and everything it
  contains, should have derived and has not. Entities that already have their derived entity are
  not touched.
- **The demo.** The data product type carries `Asset` instead of mixing the two capability traits
  in itself, and the model gains a plain `DomainType` and the one dependency a data product may
  have, on a domain. The loader seeds a `sales` domain with `dp1` depending on it, and the
  mappings name the resources after it: the S3 folder is `/root/<domain>/<data product>/<port>`
  (`/root/sales/dp1/op1`) and the Athena database `<domain>_<data product>` (`sales_dp1`). A
  volume created by an earlier build has to be recreated (`make down ARGS=-v`), as the loader
  says.

### Fixed

- **Versioning a trait onto one of its own descendants** overflowed the stack instead of being
  refused. It is now a `400` naming the cycle.
- **The aggregate editor inventing empty values.** An optional object or array left empty was
  submitted as `{}` or `[]`, so an access grant with no `resources` came out with an empty
  selector. Empty optional containers are now left out, as the instance editor already did.
- **A link that could not be removed once a mapping's path through it was ambiguous.** An entity
  given a second dependency makes a path reference that takes whatever it depends on
  (`IS_REQUIRED_BY{$}`) ambiguous, and removing either dependency then failed with a `500` —
  "Transaction silently rolled back because it has been marked as rollback-only" in the web UI —
  leaving no way out. The link is now removed, which is what resolves the ambiguity.

## [2.0.0] - 2026-10-22

A major release: the Iceberg REST catalog and the Hive Metastore are removed, which breaks anyone
running them. Nothing else changes: the main application, its REST API, its configuration
properties and its schema are the same as in 1.0.1, so a 1.0.x database runs on 2.0.0 as it is.

### Removed

- **The Iceberg REST catalog and the Hive Metastore.** Both independent applications
  (`metacatalog-iceberg-catalog`, `metacatalog-hive-metastore`) are gone, with their container
  images, their Docker Compose overlays and `make` targets, and the data-product demo that published
  through them, provisioning scripts included. The main application, its REST API and its schema
  are unchanged. This is a breaking change for anyone running either application. On a database
  they ran against, the registries' immutable traits and entity types remain, inert, and cannot be
  deleted through the API; the entities they recorded remain as ordinary entities.

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

[Unreleased]: https://github.com/dgreco/metacatalog/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/dgreco/metacatalog/compare/v1.0.1...v2.0.0
[1.0.1]: https://github.com/dgreco/metacatalog/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/dgreco/metacatalog/releases/tag/v1.0.0
