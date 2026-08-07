# Convenience targets for running the full stack locally.
# The compose `app` service builds from source, but `docker compose up` reuses an
# existing image — always pass --build (these targets do) to run the latest code.

COMPOSE ?= docker compose

# The Keycloak demo stacks the SSO overlay on the bearer-token one, so the UI at
# http://localhost:8080/ui is reachable through a Keycloak login (demo / demo).
# The bulk-loader only speaks HTTP Basic and is disabled under oauth2 — run
# `make up-d` once first to seed the demo data (the postgres volume is shared).
COMPOSE_KEYCLOAK = $(COMPOSE) -f docker-compose.yml -f docker-compose.keycloak.yml -f docker-compose.keycloak-sso.yml

.PHONY: run up up-d rebuild down logs ps info run-keycloak up-keycloak-d down-keycloak

## run / up: build from source and start the whole stack (foreground)
run up:
	$(COMPOSE) up --build

## up-d: same, detached
up-d:
	$(COMPOSE) up --build -d

## run-keycloak: the stack with Keycloak and oauth2 auth incl. browser SSO (foreground)
run-keycloak:
	$(COMPOSE_KEYCLOAK) up --build

## up-keycloak-d: same, detached
up-keycloak-d:
	$(COMPOSE_KEYCLOAK) up --build -d

## rebuild: force a full rebuild ignoring the Docker layer cache, then start detached
rebuild:
	$(COMPOSE) build --no-cache app
	$(COMPOSE) up -d

## down: stop the stack (add ARGS=-v to also drop the database volume)
down:
	$(COMPOSE) down $(ARGS)

## down-keycloak: stop the Keycloak stack (add ARGS=-v to also drop the database volume)
down-keycloak:
	$(COMPOSE_KEYCLOAK) down $(ARGS)

## logs: follow the application logs
logs:
	$(COMPOSE) logs -f app

## ps: show container status
ps:
	$(COMPOSE) ps

## info: print the running build's version / git commit / build time
info:
	@curl -s http://localhost:8080/actuator/info
