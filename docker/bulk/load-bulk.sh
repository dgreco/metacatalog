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
# declaration: a trait relationship added to the model later then stayed uncreated
# on any volume predating it, and the first run that needed it failed halfway.
# For the same reason it also checks the *traits each entity type carries*, not
# just that the type exists: a model change can leave every name exactly as it
# was and still be a change, which is what adding `Authorizable` /
# `AuthorizableResource` to the demo did. Without this the loader would report
# the model as fully present and the authorization demo would quietly not work
# on any volume created before it.
# The instance probe still uses the root instance's type and name; override with
# ROOT_ENTITY_TYPE / ROOT_ENTITY_NAME.
#
# The instances file may hold more than the aggregate: documents after the first are its
# companions — the domain the demo's data product depends on. A companion that is already in the
# catalog is not loaded again: its document is left out and what referred to it by `ref` is pointed
# at the existing entity by id, which the aggregate endpoint accepts in `dependsOn`. Otherwise
# re-creating the aggregate after it was deleted from the UI would leave a second domain of the
# same name behind every time.
#
# Once the instances are loaded the aggregate is given its second version, from
# NEXT_VERSION_FILE: the demo's data product is versionable, and a history with one version in it
# shows nothing of how versioning works. It is done only right after a load — an aggregate that is
# already there keeps whatever history it has.
#
# Known gap: the Mappings section is not probed — there is no read-by-name for a
# mapping — so a model changed only there still reads as fully loaded.
set -eu

APP_URL="${APP_URL:-http://app:8080}"
MODEL_FILE="${MODEL_FILE:-/bulk/bulk-model.yaml}"
INSTANCES_FILE="${INSTANCES_FILE:-/bulk/bulk-instances.yaml}"
NEXT_VERSION_FILE="${NEXT_VERSION_FILE:-/bulk/bulk-instances-v2.yaml}"

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

# Every (entity type, trait) pair the file declares, one "<type> <trait>" per line. `traits:` sits
# four spaces in and its entries six, which is what separates them from the `required:` lists inside
# a `schema:` block. One pass over the file for all of them, like the other two enumerators — asking
# per entity type re-scanned the whole file once per type.
declared_type_traits() {
  awk '
    /^[A-Za-z][A-Za-z]*:[[:space:]]*$/ { s = $1; sub(/:$/, "", s); section = s; next }
    section != "EntityTypes" { next }
    /^  - name:/ { name = $3; gsub(/"/, "", name); in_traits = 0; next }
    /^    traits:[[:space:]]*$/ { in_traits = 1; next }
    in_traits && /^      - / { t = $2; gsub(/"/, "", t); print name " " t; next }
    { in_traits = 0 }
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

# The traits the catalog has on an entity type, one per line. The DTO reports only the directly
# associated ones, which is exactly what the file declares, so the two are comparable as they
# stand. Parsed with sed because this runs in curlimages/curl, where there is no jq.
entity_type_traits() {
  curl -fsS -u "${AUTH}" "${APP_URL}/metacatalog/v1/entity-type/$1" 2>/dev/null \
    | sed -n 's/.*"traits":\[\([^]]*\)\].*/\1/p' \
    | tr ',' '\n' \
    | tr -d '"' \
    | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' \
    | grep -v '^$' || true
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
  declared_traits=$(declared_type_traits)
  for name in $(declared_names Traits name); do
    if trait_exists "${name}"; then note "trait ${name}" yes; else note "trait ${name}" no; fi
  done
  for name in $(declared_names EntityTypes name); do
    if entity_type_exists "${name}"; then
      note "entity type ${name}" yes
      # Only worth asking once the type is there; on a fresh volume the type itself is the
      # missing thing and listing each of its traits again would just be noise.
      # One GET per type, not one per declared trait: the DTO already carries the whole list.
      catalog_traits=$(entity_type_traits "${name}")
      for trait in $(echo "${declared_traits}" | awk -v want="${name}" '$1 == want { print $2 }'); do
        if echo "${catalog_traits}" | grep -qx "${trait}"; then
          note "trait ${trait} on entity type ${name}" yes
        else
          note "trait ${trait} on entity type ${name}" no
        fi
      done
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

# Every document after the first, as "<ref> <entity type> <name>" per line. A document's root
# fields sit at column 0 and its values two spaces in, which is what tells them from a part's.
declared_companions() {
  awk '
    function flush() { if (doc > 1 && ref != "" && type != "" && name != "") print ref, type, name }
    /^---[[:space:]]*$/ { flush(); doc++; ref = ""; type = ""; name = ""; next }
    BEGIN { doc = 1 }
    /^ref:/ { ref = $2; gsub(/"/, "", ref) }
    /^entityType:/ { type = $2; gsub(/"/, "", type) }
    /^  name:/ && name == "" { name = $2; gsub(/"/, "", name) }
    END { flush() }
  ' "${INSTANCES_FILE}"
}

# The id of the entity of the given type and name, or nothing.
entity_id() { # <entity type> <name>
  curl -fsS -u "${AUTH}" --get \
    --data-urlencode "entityTypeName=$1" \
    --data-urlencode "queryPath=\$ ? (@.name == \"$2\")" \
    "${APP_URL}/metacatalog/v1/entity" 2>/dev/null \
    | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1
}

# Writes the instances to load: the file as it is, minus every companion the catalog already has.
instances_to_load() { # <output file>
  cp "${INSTANCES_FILE}" "$1"
  declared_companions | while read -r ref type name; do
    id=$(entity_id "${type}" "${name}")
    [ -n "${id}" ] || continue
    echo "[bulk-loader] ${type} '${name}' already exists, reusing it" >&2
    # Drop the companion's own document, then turn the list entries naming its ref into its id.
    awk -v want="${ref}" '
      function flush() { if (!skip) printf "%s", buf; buf = ""; skip = 0 }
      /^---[[:space:]]*$/ { flush() }
      { buf = buf $0 "\n" }
      $0 ~ "^ref:[[:space:]]*\"?" want "\"?[[:space:]]*$" { skip = 1 }
      END { flush() }
    ' "$1" > "$1.tmp"
    sed "s/^\([[:space:]]*-[[:space:]]*\)\"${ref}\"[[:space:]]*$/\1\"${id}\"/" "$1.tmp" > "$1"
    rm -f "$1.tmp"
  done
}

# Gives the aggregate just loaded its second version: the parts NEXT_VERSION_FILE lists, appended
# to the aggregate as the API serves it and sent back as one update.
#
# The aggregate's YAML ends with the root's `parts`, a list whose items start at column 0 — which
# is how the file's own items are written, so appending them extends that list.
record_next_version() {
  [ -f "${NEXT_VERSION_FILE}" ] || return 0
  root=$(entity_id "${ROOT_ENTITY_TYPE}" "${ROOT_ENTITY_NAME}")
  if [ -z "${root}" ]; then
    echo "[bulk-loader] aggregate '${ROOT_ENTITY_NAME}' not found, no second version recorded" >&2
    return 1
  fi
  # The resources of the first version are derived a moment after the load. Waiting for them is
  # not needed for the update to work; it makes the second version record them, so the history
  # shows what the first version turned into. Read with its derived entities, the aggregate lists
  # each under the part it was derived from, as that part's own `parts`. Bounded: a model that
  # derives nothing never has any.
  tries=0
  until curl -fsS -u "${AUTH}" "${APP_URL}/metacatalog/v1/aggregate/${root}/yaml?retrieveMappedEntities=true" 2>/dev/null \
      | grep -q '^  parts:'; do
    tries=$((tries + 1))
    [ "${tries}" -ge 10 ] && break
    sleep 1
  done
  curl -fsS -u "${AUTH}" -o /tmp/next-version.yaml \
    "${APP_URL}/metacatalog/v1/aggregate/${root}/yaml?retrieveMappedEntities=false"
  cp "${NEXT_VERSION_FILE}" /tmp/next-version-parts.yaml
  # {{EntityType/name}} is an entity that exists by now; what refers to it needs its id. Comment
  # lines are left out of the search: the file explains the notation by showing it.
  grep -v '^[[:space:]]*#' "${NEXT_VERSION_FILE}" | sed -n 's/.*{{\([^}]*\)}}.*/\1/p' | sort -u \
      | while read -r reference; do
    id=$(entity_id "${reference%%/*}" "${reference#*/}")
    if [ -z "${id}" ]; then
      echo "[bulk-loader] ${NEXT_VERSION_FILE} refers to {{${reference}}}, which does not exist" >&2
      exit 1
    fi
    sed "s|{{${reference}}}|${id}|g" /tmp/next-version-parts.yaml > /tmp/next-version-parts.tmp
    mv /tmp/next-version-parts.tmp /tmp/next-version-parts.yaml
  done
  cat /tmp/next-version-parts.yaml >> /tmp/next-version.yaml
  http_code=$(curl -s -o /tmp/next-version.out -w "%{http_code}" \
    -X PUT \
    -u "${AUTH}" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@/tmp/next-version.yaml" \
    "${APP_URL}/metacatalog/v1/aggregate/${root}/yaml")
  if [ "${http_code}" != "204" ]; then
    echo "[bulk-loader] recording the second version failed with HTTP ${http_code}" >&2
    cat /tmp/next-version.out >&2
    return 1
  fi
  echo "[bulk-loader] second version of '${ROOT_ENTITY_NAME}' recorded from ${NEXT_VERSION_FILE}"
}

if instances_loaded; then
  echo "[bulk-loader] aggregate '${ROOT_ENTITY_NAME}' (${ROOT_ENTITY_TYPE}) already exists, skipping"
else
  echo "[bulk-loader] loading aggregate instances from ${INSTANCES_FILE}"
  instances_to_load /tmp/instances.yaml
  http_code=$(curl -s -o /tmp/instances.out -w "%{http_code}" \
    -X POST \
    -u "${AUTH}" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@/tmp/instances.yaml" \
    "${APP_URL}/metacatalog/v1/aggregate/yaml")
  if [ "${http_code}" != "204" ]; then
    echo "[bulk-loader] aggregate instances load failed with HTTP ${http_code}" >&2
    cat /tmp/instances.out >&2
    exit 1
  fi
  echo "[bulk-loader] aggregate instances loaded"
  record_next_version
fi
echo "[bulk-loader] done"
