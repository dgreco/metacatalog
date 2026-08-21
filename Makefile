# Convenience targets for running the full stack locally.
# The compose `app` service builds from source, but `docker compose up` reuses an
# existing image — always pass --build (these targets do) to run the latest code.

COMPOSE ?= docker compose

# The Keycloak demo stacks the SSO overlay on the bearer-token one, so the UI at
# http://localhost:8080/ui is reachable through a Keycloak login (demo / demo).
# The bulk-loader only speaks HTTP Basic and is disabled under oauth2 — run
# `make up-d` once first to seed the demo data (the postgres volume is shared).
COMPOSE_KEYCLOAK = $(COMPOSE) -f docker-compose.yml -f docker-compose.keycloak.yml -f docker-compose.keycloak-sso.yml

# The Iceberg overlay adds a RustFS S3 warehouse and the Iceberg REST catalog
# app on http://localhost:8181 (see docker-compose.iceberg.yml).
COMPOSE_ICEBERG = $(COMPOSE) -f docker-compose.yml -f docker-compose.iceberg.yml

## Base stack plus the Hive Metastore (Thrift on :9083)
COMPOSE_HIVE = $(COMPOSE) -f docker-compose.yml -f docker-compose.hive.yml

## The mixed demo: base + Iceberg + Hive, one data product publishing through both catalogs
COMPOSE_HIVE_DEMO = $(COMPOSE) -f docker-compose.yml -f docker-compose.iceberg.yml -f docker-compose.hive.yml -f docker-compose.hive-demo.yml

.PHONY: run up up-d rebuild down logs ps info run-keycloak up-keycloak-d down-keycloak run-iceberg up-iceberg-d down-iceberg run-hive up-hive-d down-hive hive-cli run-hive-demo up-hive-demo-d down-hive-demo

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

## run-iceberg: the stack plus RustFS and the Iceberg REST catalog on :8181 (foreground)
run-iceberg:
	$(COMPOSE_ICEBERG) up --build

## up-iceberg-d: same, detached
up-iceberg-d:
	$(COMPOSE_ICEBERG) up --build -d

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

## run-hive: the stack plus the Hive Metastore on :9083 (foreground)
run-hive:
	$(COMPOSE_HIVE) up --build

## up-hive-d: same, detached
up-hive-d:
	$(COMPOSE_HIVE) up --build -d

## down-hive: stop the hive stack (ARGS=-v to drop volumes too)
down-hive:
	$(COMPOSE_HIVE) down $(ARGS)

## hive-cli: build the Thrift client the Hive provisioning script shells out to.
## Out of the reactor and mounted rather than shipped: 34 MB of Hive classes only the demo needs.
## Built in a container so the demo needs no JDK or Maven on the host, like everything else here.
hive-cli:
	docker run --rm \
	  -v "$(PWD)/docker/provisioning/hive-cli":/work \
	  -v "$(HOME)/.m2":/root/.m2 \
	  -w /work maven:3-eclipse-temurin-21 mvn -q -B package

## run-hive-demo: the mixed data product demo (foreground)
run-hive-demo: hive-cli
	$(COMPOSE_HIVE_DEMO) up --build

## up-hive-demo-d: same, detached
up-hive-demo-d: hive-cli
	$(COMPOSE_HIVE_DEMO) up --build -d

## down-hive-demo: stop it (ARGS=-v to drop volumes too)
down-hive-demo:
	$(COMPOSE_HIVE_DEMO) down $(ARGS)

## down-iceberg: stop the Iceberg stack (add ARGS=-v to also drop the database and warehouse volumes)
down-iceberg:
	$(COMPOSE_ICEBERG) down $(ARGS)

## logs: follow the application logs
logs:
	$(COMPOSE) logs -f app

## ps: show container status
ps:
	$(COMPOSE) ps

## info: print the running build's version / git commit / build time
info:
	@curl -s http://localhost:8080/actuator/info
