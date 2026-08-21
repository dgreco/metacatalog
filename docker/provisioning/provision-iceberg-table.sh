#!/usr/bin/env bash
# Provisioning script for IcebergTableOutputPortType (see application-docker.yaml):
# invoked by ScriptProvisioningTask as `bash <this> <provision|unprovision>` with the
# port entity's JSON values on standard input, e.g.
#   {"name":"customers","namespace":"sales_analytics","columns":[{"name":"customer_id","type":"long","required":true}, ...]}
# On provision it creates the namespace (if missing) and the table in the Iceberg
# REST catalog, then links the port to the materialized IcebergTable entity
# (`port DEPENDS_ON table`, sanctioned by the trait relationship the demo model
# declares — the port's contract is fulfilled by the table); on unprovision it
# removes that link first — the drop deletes the table as an aggregate, which is
# refused while an outside link exists — then drops the table (purging its
# data/metadata files) and removes the
# namespace once its last table is gone. Both directions are idempotent —
# re-provisioning an existing table and unprovisioning a missing one succeed as
# no-ops — because the provisioning procedures may retry and the demo stack
# restarts freely.
#
# The task hands the script only the port's *values* (no entity id), so both ends
# of the link are looked up by name/namespace through the metacatalog REST API:
# the port by (name, namespace), the table entity by (name, namespaceKey — equal
# to the namespace for the single-level namespaces this demo uses).
#
# Requires bash, curl and jq (installed in the runtime image). Reaches the catalog
# at ICEBERG_CATALOG_URL and the metacatalog API at METACATALOG_API_URL (the
# script runs inside the app container, so it defaults to localhost), with the
# HTTP Basic credentials from METACATALOG_USER/METACATALOG_PASSWORD (compose sets
# all of these).
set -euo pipefail

OPERATION="${1:?usage: provision-iceberg-table.sh <provision|unprovision>}"
VALUES="$(cat)"

CATALOG_URL="${ICEBERG_CATALOG_URL:-http://iceberg-catalog:8181}"
PREFIX="${ICEBERG_CATALOG_PREFIX:-metacatalog}"
BASE="${CATALOG_URL}/v1/${PREFIX}"

API="${METACATALOG_API_URL:-http://localhost:8080}/metacatalog/v1"
API_AUTH="${METACATALOG_USER:-admin}:${METACATALOG_PASSWORD:-admin}"

NAMESPACE="$(jq -r '.namespace' <<<"${VALUES}")"
TABLE="$(jq -r '.name' <<<"${VALUES}")"

fail() { echo "$1" >&2; exit 1; }

# Per-invocation, because the resources of one aggregate have no dependencies between
# them and are provisioned in parallel: several copies of this script run at once in
# the same container, and a shared path would have them overwrite each other's bodies —
# so a failure could report the response of a different table entirely, and that text is
# what ends up in provisioningResult.
RESPONSE_BODY="$(mktemp)"
trap 'rm -f "${RESPONSE_BODY}"' EXIT

# request <expected-code-regex> <curl args...> — body lands in ${RESPONSE_BODY}
request() {
  local expected="$1"; shift
  local code
  code=$(curl -s -o "${RESPONSE_BODY}" -w '%{http_code}' "$@")
  [[ "${code}" =~ ^(${expected})$ ]] || fail "unexpected HTTP ${code} from ${*: -1}: $(cat "${RESPONSE_BODY}")"
  echo "${code}"
}

# entity_id <entityTypeName> <queryPath> — the single matching entity's id, empty if none.
#
# A lookup that finds nothing is empty and successful, including when the request itself
# fails because the entity type does not exist: unprovisioning has to tolerate a catalog
# that was never provisioned, and `set -e` would otherwise abort the script at the
# assignment — before the caller's `|| return 0` could express that.
#
# More than one match is fatal. The caller is about to link or unlink exactly one entity,
# and nothing stops two data products from exposing a port of the same name in the same
# namespace; picking arbitrarily would wire the wrong port to the wrong table and report
# success.
entity_id() {
  local body count
  if ! body=$(curl -sf -u "${API_AUTH}" --get \
      --data-urlencode "entityTypeName=$1" \
      --data-urlencode "queryPath=$2" \
      "${API}/entity"); then
    return 0
  fi
  count=$(jq 'length' <<<"${body}")
  ((count <= 1)) || fail "found ${count} $1 entities matching ${NAMESPACE}.${TABLE}; refusing to guess which one this port means"
  jq -r '.[0].id // empty' <<<"${body}"
}

table_entity_id() {
  entity_id IcebergTable "\$ ? (@.name == \"${TABLE}\" && @.namespaceKey == \"${NAMESPACE}\")"
}

port_entity_id() {
  entity_id IcebergTableOutputPortType "\$ ? (@.name == \"${TABLE}\" && @.namespace == \"${NAMESPACE}\")"
}

# link_exists <portId> <tableId> — is the port already DEPENDS_ON-linked to the table?
link_exists() {
  curl -sf -u "${API_AUTH}" "${API}/entity/link/$1/DEPENDS_ON" \
    | jq -e --arg id "$2" 'any(.[]; .id == $id)' >/dev/null
}

# Connect the port to the materialized IcebergTable entity that fulfills it.
ensure_link() {
  local table_id port_id
  table_id="$(table_entity_id)"
  [[ -n "${table_id}" ]] || fail "no IcebergTable entity found for ${NAMESPACE}.${TABLE}"
  port_id="$(port_entity_id)"
  [[ -n "${port_id}" ]] || fail "no IcebergTableOutputPortType entity found for ${NAMESPACE}.${TABLE}"
  if link_exists "${port_id}" "${table_id}"; then
    echo "output port already linked to its table entity"
  else
    request '204' -X POST -u "${API_AUTH}" -H 'Content-Type: application/json' \
      -d "$(jq -n --arg s "${port_id}" --arg t "${table_id}" \
        '{sourceEntityId: $s, relationshipTypeName: "DEPENDS_ON", targetEntityId: $t}')" \
      "${API}/entity/link" >/dev/null
    echo "linked output port ${port_id} DEPENDS_ON table entity ${table_id}"
  fi
}

# Remove the port→table link if present, so the table can be dropped (the drop
# deletes the table as an aggregate, which refuses any link leaving it).
remove_link() {
  local table_id port_id
  table_id="$(table_entity_id)"
  [[ -n "${table_id}" ]] || return 0
  port_id="$(port_entity_id)"
  [[ -n "${port_id}" ]] || return 0
  if link_exists "${port_id}" "${table_id}"; then
    request '204' -X DELETE -u "${API_AUTH}" \
      "${API}/entity/link/${port_id}/DEPENDS_ON/${table_id}" >/dev/null
    echo "unlinked output port ${port_id} from table entity ${table_id}"
  fi
}

case "${OPERATION}" in

provision)
  # Namespace: create if missing (409 = already there, fine).
  request '200|409' -X POST -H 'Content-Type: application/json' \
    -d "$(jq -n --arg ns "${NAMESPACE}" '{namespace: [$ns]}')" \
    "${BASE}/namespaces" >/dev/null

  # Table: create if missing (an existing one is an idempotent success — but the
  # link is still ensured below, so a retry after a half-done run completes it).
  if curl -sf -o /dev/null "${BASE}/namespaces/${NAMESPACE}/tables/${TABLE}"; then
    echo "table ${NAMESPACE}.${TABLE} already exists"
  else
    # Build the CreateTableRequest from the port's column list; the server assigns
    # fresh field ids, ours only need to be unique.
    REQUEST="$(jq '{
        name: .name,
        schema: {
          type: "struct",
          "schema-id": 0,
          fields: [ .columns | to_entries[] | {
            id: (.key + 1),
            name: .value.name,
            type: .value.type,
            required: (.value.required // false)
          } ]
        }
      }' <<<"${VALUES}")"
    request '200' -X POST -H 'Content-Type: application/json' \
      -d "${REQUEST}" "${BASE}/namespaces/${NAMESPACE}/tables" >/dev/null
    echo "created table ${NAMESPACE}.${TABLE}"
  fi

  ensure_link
  ;;

unprovision)
  # The link would make the drop refuse the table's aggregate delete; remove it first.
  remove_link

  code=$(request '204|404' -X DELETE \
    "${BASE}/namespaces/${NAMESPACE}/tables/${TABLE}?purgeRequested=true")
  if [[ "${code}" == 404 ]]; then
    echo "table ${NAMESPACE}.${TABLE} already gone"
  else
    echo "dropped table ${NAMESPACE}.${TABLE}"
  fi
  # Drop the namespace once empty (409 = still has tables, 404 = already gone).
  request '204|404|409' -X DELETE "${BASE}/namespaces/${NAMESPACE}" >/dev/null
  ;;

*)
  fail "unknown operation: ${OPERATION}"
  ;;
esac
