#!/usr/bin/env bash
# Provisioning script for IcebergTableOutputPortType (see application-docker.yaml):
# invoked by ScriptProvisioningTask as `bash <this> <provision|unprovision>` with the
# port entity's JSON values on standard input, e.g.
#   {"name":"customers","namespace":"sales_analytics","columns":[{"name":"customer_id","type":"long","required":true}, ...]}
# On provision it creates the namespace (if missing) and the table in the Iceberg
# REST catalog; on unprovision it drops the table (purging its data/metadata files)
# and removes the namespace once its last table is gone. Both directions are
# idempotent — re-provisioning an existing table and unprovisioning a missing one
# succeed as no-ops — because the provisioning procedures may retry and the demo
# stack restarts freely.
#
# Requires bash, curl and jq (installed in the runtime image), and reaches the
# catalog at ICEBERG_CATALOG_URL (compose sets it; defaults to the service name).
set -euo pipefail

OPERATION="${1:?usage: provision-iceberg-table.sh <provision|unprovision>}"
VALUES="$(cat)"

CATALOG_URL="${ICEBERG_CATALOG_URL:-http://iceberg-catalog:8181}"
PREFIX="${ICEBERG_CATALOG_PREFIX:-metacatalog}"
BASE="${CATALOG_URL}/v1/${PREFIX}"

NAMESPACE="$(jq -r '.namespace' <<<"${VALUES}")"
TABLE="$(jq -r '.name' <<<"${VALUES}")"

fail() { echo "$1" >&2; exit 1; }

# request <expected-code-regex> <curl args...> — body lands in /tmp/response.json
request() {
  local expected="$1"; shift
  local code
  code=$(curl -s -o /tmp/response.json -w '%{http_code}' "$@")
  [[ "${code}" =~ ^(${expected})$ ]] || fail "unexpected HTTP ${code} from ${*: -1}: $(cat /tmp/response.json)"
  echo "${code}"
}

case "${OPERATION}" in

provision)
  # Namespace: create if missing (409 = already there, fine).
  request '200|409' -X POST -H 'Content-Type: application/json' \
    -d "$(jq -n --arg ns "${NAMESPACE}" '{namespace: [$ns]}')" \
    "${BASE}/namespaces" >/dev/null

  # Table already there? Idempotent success.
  if curl -sf -o /dev/null "${BASE}/namespaces/${NAMESPACE}/tables/${TABLE}"; then
    echo "table ${NAMESPACE}.${TABLE} already exists"
    exit 0
  fi

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
  ;;

unprovision)
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
