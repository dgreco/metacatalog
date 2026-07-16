# Convenience targets for running the full stack locally.
# The compose `app` service builds from source, but `docker compose up` reuses an
# existing image — always pass --build (these targets do) to run the latest code.

COMPOSE ?= docker compose

.PHONY: run up up-d rebuild down logs ps info

## run / up: build from source and start the whole stack (foreground)
run up:
	$(COMPOSE) up --build

## up-d: same, detached
up-d:
	$(COMPOSE) up --build -d

## rebuild: force a full rebuild ignoring the Docker layer cache, then start detached
rebuild:
	$(COMPOSE) build --no-cache app
	$(COMPOSE) up -d

## down: stop the stack (add ARGS=-v to also drop the database volume)
down:
	$(COMPOSE) down $(ARGS)

## logs: follow the application logs
logs:
	$(COMPOSE) logs -f app

## ps: show container status
ps:
	$(COMPOSE) ps

## info: print the running build's version / git commit / build time
info:
	@curl -s http://localhost:8080/actuator/info
