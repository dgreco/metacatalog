# Security Policy

## Supported versions

Meta Catalog is pre-1.0. Security fixes land on `main` and are included in the next release; older
snapshots are not patched.

## Reporting a vulnerability

Please do not open a public issue for a security problem.

Report it privately through
[GitHub's private vulnerability reporting](https://github.com/dgreco/metacatalog/security/advisories/new)
for this repository, or by email to greco@acm.org. Include the version affected, a description of
the issue and, where possible, steps to reproduce it. You will get an acknowledgement within a few
days and updates as the fix progresses. Please give us a reasonable window to release a fix before
disclosing the issue publicly.

## Scope notes

A few properties of the system are worth knowing when assessing a report:

- The default `none` authentication mode is for development only, and the application refuses to
  start with it under the `kubernetes` or `docker` profiles. Production deployments select basic, OAuth2 or LDAP
  (see *Security* in `README.md`).
- The Actuator exposes only `health`, `info`, `flyway` and `metrics`; `env`, `configprops`, `beans`
  and `loggers` are deliberately excluded because they would leak credentials.
- Credentials in the `docker-compose*.yml` files and the Keycloak demo realm under `docker/` are
  demo values for the local stack, not defaults of the application.
