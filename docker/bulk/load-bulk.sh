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
# The model probe checks every trait, entity type and trait relationship the file
# declares, not one name standing in for the file. A single sentinel silently
# skipped the load whenever the model gained something while keeping its first
# declaration: that is what left `IcebergTableOutputPort DEPENDS_ON
# IcebergTableTrait` uncreated on any volume predating it, so provisioning got as
# far as creating real Iceberg tables and then failed linking the port to them.
# The instance probe still uses the root instance's type and name; override with
# ROOT_ENTITY_TYPE / ROOT_ENTITY_NAME.
#
# Known gap: the Mappings section is not probed — there is no read-by-name for a
# mapping — so a model changed only there still reads as fully loaded.
set -eu

APP_URL="${APP_URL:-http://app:8080}"
MODEL_FILE="${MODEL_FILE:-/bulk/bulk-model.yaml}"
INSTANCES_FILE="${INSTANCES_FILE:-/bulk/bulk-instances.yaml}"

# HTTP Basic credentials matching application-docker.yaml (admin / admin).
AUTH="${METACATALOG_USER:-admin}:${METACATALOG_PASSWORD:-admin}"

# Sentinels for the instance probe, read from the file unless overridden.
ROOT_ENTITY_TYPE="${ROOT_ENTITY_TYPE:-$(sed -n 's/^entityType:[[:space:]]*//p' "${INSTANCES_FILE}" | head -1 | tr -d '"')}"
ROOT_ENTITY_NAME="${ROOT_ENTITY_NAME:-$(sed -n 's/^[[:space:]]*name:[[:space:]]*//p' "${INSTANCES_FILE}" | head -1 | tr -d '"')}"

echo "[bulk-loader] waiting for app health at ${APP_URL}/actuator/health"
until curl -fsS "${APP_URL}/actuator/health" | grep -q '"status":"UP"'; do
  echo "[bulk-loader] app not healthy yet, retrying in 3s..."
  sleep 3
done
echo "[bulk-loader] app is healthy"

# The bulk YAML has a fixed shape — section headers at column 0, entries two spaces in,
# their fields four — so awk over it is enough to enumerate what the file declares.
declared_names() { # <section> <key>
  awk -v want_section="$1" -v key="$2" '
    /^[A-Za-z][A-Za-z]*:[[:space:]]*$/ { s = $1; sub(/:$/, "", s); section = s; next }
    section != want_section { next }
    $0 ~ "^  - " key ":" {
      line = $0
      sub("^  - " key ":[[:space:]]*", "", line)
      gsub(/"/, "", line)
      print line
    }
  ' "${MODEL_FILE}"
}

declared_relationships() { # source/type/target per line
  awk '
    /^[A-Za-z][A-Za-z]*:[[:space:]]*$/ { s = $1; sub(/:$/, "", s); section = s; next }
    section != "Relationships" { next }
    /^  - sourceTrait:/ { src = $3; gsub(/"/, "", src) }
    /^    relationshipType:/ { rel = $2; gsub(/"/, "", rel) }
    /^    targetTrait:/ { tgt = $2; gsub(/"/, "", tgt); print src "/" rel "/" tgt }
  ' "${MODEL_FILE}"
}

trait_exists() {
  curl -fsS -o /dev/null -u "${AUTH}" "${APP_URL}/metacatalog/v1/trait/$1" 2>/dev/null
}

entity_type_exists() {
  curl -fsS -o /dev/null -u "${AUTH}" "${APP_URL}/metacatalog/v1/entity-type/$1" 2>/dev/null
}

relationship_exists() { # <source>/<type>/<target>
  rest="${1#*/}"
  curl -fsS -u "${AUTH}" \
    "${APP_URL}/metacatalog/v1/trait/link/${1%%/*}/${rest%%/*}" 2>/dev/null \
    | grep -q "\"name\":\"${rest##*/}\""
}

# Counts what the model declares against what the catalog already has.
MODEL_PRESENT=0
MODEL_MISSING=0
note() { # <label> <exists?>
  if [ "$2" = yes ]; then
    MODEL_PRESENT=$((MODEL_PRESENT + 1))
  else
    MODEL_MISSING=$((MODEL_MISSING + 1))
    echo "[bulk-loader] not yet in the catalog: $1"
  fi
}

survey_model() {
  for name in $(declared_names Traits name); do
    if trait_exists "${name}"; then note "trait ${name}" yes; else note "trait ${name}" no; fi
  done
  for name in $(declared_names EntityTypes name); do
    if entity_type_exists "${name}"; then
      note "entity type ${name}" yes
    else
      note "entity type ${name}" no
    fi
  done
  for triple in $(declared_relationships); do
    if relationship_exists "${triple}"; then
      note "relationship ${triple}" yes
    else
      note "relationship ${triple}" no
    fi
  done
}

instances_loaded() {
  out=$(curl -fsS -u "${AUTH}" --get \
    --data-urlencode "entityTypeName=${ROOT_ENTITY_TYPE}" \
    --data-urlencode "queryPath=\$ ? (@.name == \"${ROOT_ENTITY_NAME}\")" \
    "${APP_URL}/metacatalog/v1/entity" 2>/dev/null) || return 1
  [ -n "${out}" ] && [ "${out}" != "[]" ]
}

survey_model
if [ "${MODEL_MISSING}" -eq 0 ]; then
  echo "[bulk-loader] model already loaded (${MODEL_PRESENT} declaration(s) present), skipping"
elif [ "${MODEL_PRESENT}" -gt 0 ]; then
  # Part of the model is here and part is not, which this loader cannot repair: the bulk
  # endpoint is all-or-nothing and dies on the first name that already exists. Saying so is
  # the whole point — skipping quietly is what let a half-applied model reach provisioning,
  # where it failed much later and somewhere unrelated.
  echo "[bulk-loader] ${MODEL_MISSING} declaration(s) from ${MODEL_FILE} are missing while" >&2
  echo "[bulk-loader] ${MODEL_PRESENT} are already present. The model file has changed since" >&2
  echo "[bulk-loader] this volume was created, and a partial load is not something the bulk" >&2
  echo "[bulk-loader] endpoint can do. Recreate the volume: docker compose down -v" >&2
  exit 1
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
