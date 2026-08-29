# An authorization model for `Authorizable` aggregates

The design behind the access policy implemented on `feat/authorization-model`. It was written as a
proposal first; where the implementation departed from it, the section says so and why. §11 lists
the decisions that were deliberately deferred rather than settled.

## 1. What exists today

The authorization capability is currently a *lifecycle with no content*. `BuiltInCapability.AUTHORIZATION`
declares two traits (`Authorizable`, `AuthorizableResource`) whose entire schema is one status field
and one result string, both `readOnly` and both written by the machinery:

```json
{"authorizationStatus": {"enum": ["AUTHORIZED","REJECTED","FAILED"]}, "authorizationResult": {"type":"string"}}
```

`AuthorizationProcedure` / `RejectionProcedure` walk the aggregate, select members carrying
`AuthorizableResource`, and run each type's registered `ProvisioningTask` in the `authorize` /
`reject` direction. The run answers **whether** access was decided. It carries no statement of
*who* gets *what*.

The mixed data-product demo fills that vacuum ad hoc: each output port's own entity type declares
an optional `grantee` string, and `docker/provisioning/access-decision.sh` stamps
`access.status` + `access.granted-to` onto the real table. That works for a demo and has three
problems as a model:

- **The information lives on the resource, not the root.** Four ports of one data product each
  restate their audience, and nothing makes them consistent or lets you ask the product-level
  question "who can read this data product?".
- **There is no permission.** `access.granted-to: sales-analysts` says a group was granted
  *something*. Read? Write? The demo has one implicit permission and no way to name a second.
- **A single grantee is not a policy.** One string cannot express "analysts read, the pipeline
  service account writes".

There is also a plumbing gap that any fix runs into: **a task is handed its own entity and nothing
else.** `Task` exposes `getEntity()`; `ProvisioningTask` adds `setOperation`. A task wanting the
root's policy today would have to walk `IS_PART_OF` upwards itself — N queries, once per resource,
each re-parsing the same document, on whatever thread the executor happened to pick. The policy has
to be resolved **once, at plan time, by the procedure**, which already reads the whole aggregate.

## 2. What the big-data world actually agrees on

Ranger, Lake Formation, Unity Catalog, Sentry and Snowflake differ in surface and agree on
structure. Stripped to what matters here:

| | shape |
| --- | --- |
| **Ranger** | a *policy* = resource spec + `policyItems[]`, each `{users, groups, roles, accesses[]}`. The *access types* come from a per-service definition (hive: select/update/create/drop/alter…), not from the policy. |
| **Lake Formation** | `(principal, resource, permissions[], grantable[])`. Tag-based access control selects resources by LF-Tag rather than by name. |
| **Unity Catalog** | `GRANT <privilege> ON <securable> TO <principal>`; principals are account-level users / groups / service principals, federated in from the IdP. |
| **Snowflake / SQL standard** | privileges granted to *roles*, roles granted to users. The role is the indirection. |

Four invariants fall out, and they are the ones worth copying:

1. **A grant is a triple**: principals × permissions × resource scope. Every system is that,
   decorated.
2. **The permission vocabulary is declared separately from the grants that use it**, and it belongs
   to the *kind of thing* being granted (Ranger's service-def), not to the policy.
3. **Principals are references into an external identity provider**, resolved by name. No system
   models users as first-class catalog objects; they sync them.
4. **The policy is declarative desired state.** You edit the policy and the engine converges. There
   is no imperative `revoke` verb at the policy layer.

Point 4 matters more than it looks — see §7.

## 3. The proposal: three properties on `Authorizable`

The root carries the whole answer. `AuthorizableResource` never declares who may see it.

```yaml
principals:                       # the subjects this aggregate knows about
  - { id: sales-analysts,   type: GROUP,   description: "BI team" }
  - { id: crm-pipeline,     type: SERVICE }
  - { id: alice@corp.example, type: USER }

permissions:                      # the vocabulary this aggregate can grant
  - { name: READ,  description: "Query the published data" }
  - { name: WRITE, description: "Append to it" }

grants:                           # the policy: who gets what, over which resources
  - principals:  [sales-analysts]
    permissions: [READ]
  - principals:  [crm-pipeline]
    permissions: [READ, WRITE]
    resources:                    # optional; absent means every resource in the aggregate
      entityTypes: [IcebergTableOutputPortType]
```

Why three properties rather than one flat list of grants:

- **`principals` and `permissions` are declared once and referenced by name**, so a group named in
  four grants is spelled once and a typo in the fifth is a *refusal* (§8), not a silent grant to
  nobody. The demo's current shape — the audience restated per port — is exactly the failure this
  removes.
- They are also the **documentation surface**. A catalog whose UI can show "this data product
  recognises these three principals and offers READ and WRITE" is answering a question a bare grant
  list does not.

### Principal shape

`{id, type}` where `type ∈ {USER, GROUP, ROLE, SERVICE}`, `id` resolved against whatever IdP is
behind the deployment (§2, invariant 3). Deliberately **not** entities in the catalog: an identity
directory is a thing metacatalog federates from, not a thing it owns, and modelling users as
entities means a synchronisation problem nobody asked for.

The enum should carry all four values **from the first commit** even though the demo needs two.
Adding a value later means editing an immutable trait's schema, which means recreating the
database — this is precisely the `Provisionable`-gained-a-schema story that `ImmutableModelInstaller`'s
drift check exists to catch. Be generous once rather than sorry later.

### Permission shape

`{name, description?}` rather than a bare string, for the same reason.

The vocabulary is **abstract and portable**, and that is a deliberate choice rather than laziness
about writing out Iceberg's privilege names. §5 argues it.

## 4. How a root's grant reaches a resource

Default: **every grant applies to every `AuthorizableResource` in the aggregate.** That is the
literal reading of "the root manages it, it authorizes all the resources", and for most data
products it is the whole truth.

The optional `resources` selector narrows it, with two orthogonal filters:

```yaml
resources:
  entityTypes: [IcebergTableOutputPortType]      # by type — always available, entities have no name column
  values: { namespace: sales }                   # exact match on the resource's own values
```

`entityTypes` is the cheap, universal filter. `values` narrows further by exact match. Both absent
means all; both present means the intersection.

**This is where the implementation departed from the proposal**, which called for a jsonpath `where`
on the grounds that it "reuses machinery the codebase already has". It does not. The machinery it
was pointing at — `EntityService.list(typeName, queryPath)`, `RegistrySupport.jsonPathString` — is
*Postgres-side* jsonpath, pushed into the database. Selector evaluation happens in memory, against
an aggregate the procedure has already read, so the only in-process option is Jayway, whose syntax
is a different dialect (`$[?(@.namespace == 'sales')]` against Postgres's `$ ? (@.namespace ==
"sales")`). That would have put two path languages into the model under one name, differing only in
the cases nobody tests. Exact match covers the realistic selectors, is trivially schema-validated,
and has no dialect to get wrong. Numbers compare by value rather than by node type, so a selector
does not start or stop matching depending on whether the document that created the entity wrote a
trailing zero.

Note what is *not* proposed: a resource narrowing its own grants. Keeping the root the single place
the answer lives is the requirement, and it is also what makes "who can read this product?"
answerable by reading one entity. Expressiveness goes into the selector, not into a second authority.

## 5. Abstract permissions, translated per resource type

`READ` is not an Iceberg privilege, a Hive privilege or a Ranger access type. That is the point.

The mixed demo already publishes **one data product through two catalogs**, and
`access-decision.sh` exists specifically because the Iceberg side and the Hive side had to be
stopped from drifting into two different vocabularies for the same decision. Native permission
names on the root would reintroduce that at the model layer: a product whose ports live in Iceberg
and Hive would have to name `SELECT` for one and something else for the other, and a consumer
reading the product-level policy could not tell whether the two meant the same thing.

So: **the root states an abstract decision, and the task translates it.** The Iceberg task knows
what `READ` means to Iceberg; the Hive CLI knows what it means to a metastore table's parameters;
a future Ranger task knows it maps to `select`. That is exactly the Ranger service-def split —
the policy names accesses, the service definition says what they are — arrived at for the same
reason.

The corollary is a rule for task authors: **a task handed a permission it cannot translate must
fail its resource**, naming the permission and the type. Silently ignoring an unknown permission is
the version of this bug that ships to production and is discovered by an auditor.

## 6. What gets recorded on the resource

`AuthorizableResource` gains one `readOnly` field the machinery writes:

```json
"effectiveGrants": {
  "type": "array", "readOnly": true,
  "items": {"type": "object", "properties": {
      "principal":   {"type": "object", "properties": {"id": {"type":"string"}, "type": {"type":"string"}}},
      "permissions": {"type": "array", "items": {"type": "string"}}}}
}
```

This is arguably the largest single win of the feature and is worth not cutting: it makes access a
**queryable catalog fact**. "Which tables can `sales-analysts` read?" becomes a jsonb query, a
SPARQL query, and a column in the UI's instances page — across both catalogs, over resources
created by different provisioning scripts, without asking either catalog. Nothing in the current
design can answer it.

An authorized resource that no grant reaches records `effectiveGrants: []` explicitly rather than
omitting the field. "Access decided: nobody" and "never asked" must not read the same.

Cost: the root trait and the resource trait currently share one schema —
`BuiltInCapability.statusSchema()` is passed to both in `BuiltInModelContributor`. They now diverge,
so the enum needs `rootSchema()` and `resourceSchema()`, with `PROVISIONING` returning the same for
both. Small, and it keeps the "declared once" property that enum was created for.

## 7. The lifecycle stays binary — and that is correct

Adding principals and permissions makes the *content* of `AUTHORIZED` richer. It does not turn the
capability into a mutable grant set, and it should not.

`AUTHORIZE` / `REJECT` remain two directions of one lifecycle, exactly like `PROVISION` /
`UNPROVISION`. Changing who has access means **editing the root's policy and re-running
`authorize`** — an idempotent re-apply that converges the real catalogs on the declared state.
That is §2's invariant 4, and it is how every declarative policy engine in the list works.

This also settles a question that otherwise has no good answer: *if the operator empties `grants`
and calls `reject`, what does the task withdraw?* Under a grant-diffing model it cannot know.
Under this one the question does not arise — `reject` means "no access to this aggregate, full
stop", it needs no list, and it stays a safe emergency stop. A per-grant revoke is a genuinely
different operation (it needs a diff, not a direction) and is deliberately out of scope.

## 8. Refusals, at plan time

All of these go **inside `recordingAround`**, so a refusal is recorded as `FAILED` on the root
rather than leaving the `AUTHORIZED` of the last run that worked — the case
`AbstractAggregateResourceProcedure` already handles for cycles and missing factories.

- A grant naming a principal absent from `principals` → refused, naming it.
- A grant naming a permission absent from `permissions` → refused, naming it.
- A `resources` selector matching **no** resource in the aggregate → refused, naming the selector.
  A typo'd selector that silently grants nothing is the same class of bug as a task factory
  registered under a misspelled entity type name, and gets the same treatment.
- A malformed policy (JSON Schema already catches shape; this catches the cross-field rules).

Each is a plan-time failure before any task runs, so no resource is ever half-authorized because
grant 7 of 9 named a group that does not exist.

## 9. Where it lands

| Module | Change |
| --- | --- |
| `metacatalog-core` | `BuiltInCapability` splits `statusSchema()` into `rootSchema()` / `resourceSchema()`; `Authorizable` gains `principals`/`permissions`/`grants`, `AuthorizableResource` gains `effectiveGrants`. The field names and schema fragments live in a new `AccessControl` (entity package, beside `BuiltInCapability`); the `AccessPolicy` record, its parser, selector evaluation and the §8 validations live in the `service` package, so the dependency runs service → entity as it does everywhere else. |
| `metacatalog-functions` | `AbstractAggregateResourceProcedure` resolves the policy once at plan time (it already has the root, from `aggregateService.read`) and hands each task its effective grants via `setAccessGrants(...)`, symmetric with the existing `setOperation(...)`. `ProvisioningTask.writeStatus` also writes `effectiveGrants` on `AUTHORIZE`/`REJECT`. |
| `metacatalog-functions-provisioning-tasks` | `stdout` prints the grants it would apply. `script` exports the decision as a `METACATALOG_ACCESS` environment variable — JSON, read with `jq` — rather than changing the stdin contract, which is the entity's values for all four operations and stays that way. (argv would work too but puts grants in `ps` output.) |
| `metacatalog-openapi` | `GET /aggregate/{id}/access` — the effective grants per resource, the product-level "who can see what". Authoring needs no new endpoint: the policy is entity values. |
| `metacatalog-ui` | An editor for the policy on the root (it is schema-driven already, so `instance-values-form.js` renders it for free); an effective-grants column on the instances page. |
| demo | Ports drop `grantee`; `IcebergDataProductType`'s root instance gains the policy; `provision-iceberg-table.sh` / `provision-hive-table.sh` / `hive-cli` read `METACATALOG_ACCESS` and write one property/parameter per permission. `access-decision.sh` keeps its job — one vocabulary, both catalogs — with more to say. |

Staging: core schema + policy resolution first (with tests, no behavioural change), then the
procedure wiring, then the tasks, then the demo, then UI/API. The demo is the acceptance test:
one data product, two catalogs, one policy, and the same answer readable from PyIceberg and from a
Hive client.

Two notes on what actually landed:

- **The demo writes three value keys, not one per permission.** `access.granted-to` (who at all),
  `access.permissions` (which permissions at all) and `access.grants` (`id=PERM|PERM,…`, the precise
  mapping the first two flatten), plus `access.status`. A key per permission would put the policy's
  vocabulary into the catalogs' key space, where renaming a permission strands the old key on every
  table already stamped with it. A fixed key set also means `reject` removes exactly what
  `authorize` writes, from one shared list.
- **The UI got no dedicated access view, and needs none for the data to be visible.** Both the root's
  policy and each resource's `effectiveGrants` are ordinary entity values, so the existing
  schema-driven forms, the catalog graph and SPARQL already show them; `effectiveGrants` is
  `readOnly`, so the form renders it disabled. A per-row "who has access" column on the instances
  page would need one API call per row and is worth doing only if someone wants it.

## 10. The cost, stated plainly

`Authorizable` and `AuthorizableResource` are **immutable** traits. Changing their base schema
changes what `BuiltInModelContributor` declares, and `ImmutableModelInstaller`'s drift check will
abort startup on any existing database, with the only remedy being to recreate it. That is the
project's accepted posture (the Flyway baseline is edited in place for the same reason), but it
means this lands as a breaking change for every deployed instance and should land **once** —
which is the argument in §3 for making the principal-type enum and the permission shape generous
now rather than iterating.

## 11. Open decisions

| Decision | Recommendation |
| --- | --- |
| Deny rules (Ranger has allow + deny + exceptions) | **Allow-only.** Deny needs a documented precedence order *and* a guarantee that the two catalogs a product publishes through evaluate it identically — which metacatalog cannot give, since it is not the decision point (§12). Allow-only means the pushed-down state is unambiguous everywhere. |
| Vocabulary on the root, or per resource type (Ranger's service-def) | **Root**, as asked. The abstract-permission argument (§5) is what makes it work: the root's vocabulary is portable, and translation — the thing that really is per-type — lives in the task. A per-type *declaration* of which abstract permissions a type can honour is a clean later addition; the procedure would intersect. |
| Nested `additionalProperties: false` on principals/grants | **Yes.** Extending means restating the whole property on the concrete entity type, which linearization makes win (most-specific-first, reversed before merge — the type's declaration overwrites the trait's). Verbose, but a typo'd nested key is refused rather than stored. |
| Column/row-level grants, masking | **Defer.** They are per-resource detail (`columns`, `rowFilter`, `masks`) and every system that has them treats them as separate policy kinds. Adding the field shapes later is the DB-recreation cost again, so if column-level access is on the roadmap at all it is cheaper to reserve the shape now. Worth a decision, not a default. |
| Tag-based selection (LF-Tags / Ranger tag policies) | **Defer, but it is nearly free later.** It needs a classification trait on resources (`Classified` — the exact example in `AGENTS.md`'s contributor snippet) and one more selector key. The trait model already supports it. |
| Time-bounded grants, access-request workflow | **Out of scope.** A request/approval flow is its own capability with its own trait pair, not a field on this one. |

## 12. One boundary worth stating in the code

Metacatalog is the **policy administration point**. It is never asked at query time whether alice
may read a table — Trino, Spark, Ranger or the warehouse answers that. The `authorize` task's job
is to **push the declared decision into the systems that do enforce it**, which is exactly what the
existing task architecture is for.

And a name collision worth a comment somewhere prominent: `metacatalog-security` authenticates
callers of the *catalog's own API*. This model governs access to the *data products the catalog
describes*. They are unrelated today. They could eventually meet — the caller's groups gating who
may edit a product's policy is the obvious first link — but that is a separate feature and
conflating them would make both harder to reason about.
