## What

<!-- What changes, in a sentence or two. -->

## Why

<!-- The problem this solves or the behaviour it adds. Link the issue if there is one. -->

## How it was tested

<!-- Tests added or run. Anything that could not be tested and why. -->

## Checklist

- [ ] `mvn spotless:apply` has been run
- [ ] `mvn clean install` passes (or `mvn verify` for integration-level changes)
- [ ] A bug fix comes with a test that failed before the fix
- [ ] `README.md` is updated if behaviour, API or configuration changed
- [ ] The OpenAPI spec, not generated code, was changed for API changes
- [ ] Schema changes are new Flyway migrations
