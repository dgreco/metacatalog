#!/bin/sh
# One-shot bulk loader invoked by docker-compose.
# Waits for the app to be healthy, then POSTs the model definition first and
# the aggregate instances second. Exits non-zero on any failure so compose
# surfaces the error.
#
# Both steps are idempotent, because the postgres volume outlives the stack:
# on a restart the model is already there, and blindly re-POSTing it dies on
# the first duplicate trait. Each step probes the API first and only loads
# what is missing — so a restart after deleting the demo aggregate from the
# UI re-creates just the aggregate, and a plain restart loads nothing.
# The probes use sentinels read from the YAML files themselves (the first
# trait name, and the root instance's type and name), overridable via
# MODEL_SENTINEL_TRAIT / ROOT_ENTITY_TYPE / ROOT_ENTITY_NAME.
set -eu

APP_URL="${APP_URL:-http://app:8080}"
MODEL_FILE="${MODEL_FILE:-/bulk/bulk-model.yaml}"
INSTANCES_FILE="${INSTANCES_FILE:-/bulk/bulk-instances.yaml}"

# HTTP Basic credentials matching application-docker.yaml (admin / admin).
AUTH="${METACATALOG_USER:-admin}:${METACATALOG_PASSWORD:-admin}"

# Sentinels for the existence probes, read from the files unless overridden.
MODEL_SENTINEL_TRAIT="${MODEL_SENTINEL_TRAIT:-$(sed -n 's/^[[:space:]]*- name:[[:space:]]*//p' "${MODEL_FILE}" | head -1 | tr -d '"')}"
ROOT_ENTITY_TYPE="${ROOT_ENTITY_TYPE:-$(sed -n 's/^entityType:[[:space:]]*//p' "${INSTANCES_FILE}" | head -1 | tr -d '"')}"
ROOT_ENTITY_NAME="${ROOT_ENTITY_NAME:-$(sed -n 's/^[[:space:]]*name:[[:space:]]*//p' "${INSTANCES_FILE}" | head -1 | tr -d '"')}"

echo "[bulk-loader] waiting for app health at ${APP_URL}/actuator/health"
until curl -fsS "${APP_URL}/actuator/health" | grep -q '"status":"UP"'; do
  echo "[bulk-loader] app not healthy yet, retrying in 3s..."
  sleep 3
done
echo "[bulk-loader] app is healthy"

model_loaded() {
  curl -fsS -o /dev/null -u "${AUTH}" \
    "${APP_URL}/metacatalog/v1/trait/${MODEL_SENTINEL_TRAIT}" 2>/dev/null
}

instances_loaded() {
  out=$(curl -fsS -u "${AUTH}" --get \
    --data-urlencode "entityTypeName=${ROOT_ENTITY_TYPE}" \
    --data-urlencode "queryPath=\$ ? (@.name == \"${ROOT_ENTITY_NAME}\")" \
    "${APP_URL}/metacatalog/v1/entity" 2>/dev/null) || return 1
  [ -n "${out}" ] && [ "${out}" != "[]" ]
}

if model_loaded; then
  echo "[bulk-loader] model already loaded (trait '${MODEL_SENTINEL_TRAIT}' exists), skipping"
else
  echo "[bulk-loader] loading model from ${MODEL_FILE}"
  http_code=$(curl -s -o /tmp/model.out -w "%{http_code}" \
    -X POST \
    -u "${AUTH}" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@${MODEL_FILE}" \
    "${APP_URL}/metacatalog/v1/bulk-creation")
  if [ "${http_code}" != "204" ]; then
    echo "[bulk-loader] model load failed with HTTP ${http_code}" >&2
    cat /tmp/model.out >&2
    exit 1
  fi
  echo "[bulk-loader] model loaded"
fi

if instances_loaded; then
  echo "[bulk-loader] aggregate '${ROOT_ENTITY_NAME}' (${ROOT_ENTITY_TYPE}) already exists, skipping"
else
  echo "[bulk-loader] loading aggregate instances from ${INSTANCES_FILE}"
  http_code=$(curl -s -o /tmp/instances.out -w "%{http_code}" \
    -X POST \
    -u "${AUTH}" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@${INSTANCES_FILE}" \
    "${APP_URL}/metacatalog/v1/aggregate/yaml")
  if [ "${http_code}" != "204" ]; then
    echo "[bulk-loader] aggregate instances load failed with HTTP ${http_code}" >&2
    cat /tmp/instances.out >&2
    exit 1
  fi
  echo "[bulk-loader] aggregate instances loaded"
fi
echo "[bulk-loader] done"
