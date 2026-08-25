#!/usr/bin/env bash
# Provisioning script for IcebergTableOutputPortType (see application-docker.yaml):
# invoked by ScriptProvisioningTask as `bash <this> <provision|unprovision|authorize|reject>`
# with the port entity's JSON values on standard input, e.g.
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
# The other two directions are the authorization lifecycle, which is independent of
# provisioning: `authorize` stamps the access decision onto the table itself as
# Iceberg table properties (`access.status`, `access.granted-to` from the port's
# `grantee`), `reject` sets the status to REJECTED and removes the grantee. Both are
# ordinary Iceberg commits, so the decision is visible to PyIceberg, Spark and Trino —
# and, because the catalog caches each table's metadata on its IcebergTable entity,
# in the metacatalog UI and over SPARQL too.
#
# Authorizing a table that does not exist fails, naming it: a grant on nothing is not a
# grant, and the aggregate has to be provisioned first. Rejecting one that does not
# exist is a no-op, for the same reason unprovisioning a missing table is — a
# withdrawal that finds nothing to withdraw has already succeeded.
#
# Requires bash, curl and jq (installed in the runtime image). Reaches the catalog
# at ICEBERG_CATALOG_URL and the metacatalog API at METACATALOG_API_URL (the
# script runs inside the app container, so it defaults to localhost), with the
# HTTP Basic credentials from METACATALOG_USER/METACATALOG_PASSWORD (compose sets
# all of these).
set -euo pipefail

OPERATION="${1:?usage: provision-iceberg-table.sh <provision|unprovision|authorize|reject>}"
VALUES="$(cat)"

CATALOG_URL="${ICEBERG_CATALOG_URL:-http://iceberg-catalog:8181}"
PREFIX="${ICEBERG_CATALOG_PREFIX:-metacatalog}"
BASE="${CATALOG_URL}/v1/${PREFIX}"

API="${METACATALOG_API_URL:-http://localhost:8080}/metacatalog/v1"
API_AUTH="${METACATALOG_USER:-admin}:${METACATALOG_PASSWORD:-admin}"

NAMESPACE="$(jq -r '.namespace' <<<"${VALUES}")"
TABLE="$(jq -r '.name' <<<"${VALUES}")"
# A port with no grantee is granted to everyone: there is no identity provider behind this demo,
# and inventing a placeholder principal would read as one.
GRANTEE="$(jq -r '.grantee // "everyone"' <<<"${VALUES}")"

RESOURCE_LABEL="${NAMESPACE}.${TABLE}"
# The lookup/link/unlink helpers are shared with provision-hive-table.sh; their subtleties are
# documented there and are the same either side of the two catalogs.
# shellcheck source=metacatalog-api.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/metacatalog-api.sh"

table_entity_id() {
  entity_id IcebergTable "\$ ? (@.name == \"${TABLE}\" && @.namespaceKey == \"${NAMESPACE}\")"
}

port_entity_id() {
  entity_id IcebergTableOutputPortType "\$ ? (@.name == \"${TABLE}\" && @.namespace == \"${NAMESPACE}\")"
}

# Connect the port to the materialized IcebergTable entity that fulfills it.
link_port_to_table() {
  local table_id port_id
  table_id="$(table_entity_id)"
  [[ -n "${table_id}" ]] || fail "no IcebergTable entity found for ${NAMESPACE}.${TABLE}"
  port_id="$(port_entity_id)"
  [[ -n "${port_id}" ]] || fail "no IcebergTableOutputPortType entity found for ${NAMESPACE}.${TABLE}"
  ensure_link "${port_id}" "${table_id}"
}

# Remove the port->table link if present, so the table can be dropped.
unlink_port_from_table() {
  local table_id port_id
  table_id="$(table_entity_id)"
  [[ -n "${table_id}" ]] || return 0
  port_id="$(port_entity_id)"
  [[ -n "${port_id}" ]] || return 0
  remove_link "${port_id}" "${table_id}"
}

table_exists() {
  curl -sf -o /dev/null "${BASE}/namespaces/${NAMESPACE}/tables/${TABLE}"
}

# Commits the given MetadataUpdate list against the table. `requirements` is deliberately empty:
# the catalog's own compare-and-swap on the metadata pointer is what makes a commit safe, and an
# assertion here would only add a second, weaker one.
commit_updates() { # <updates-json-array>
  local commit
  commit="$(jq -n --arg ns "${NAMESPACE}" --arg t "${TABLE}" --argjson updates "$1" \
    '{identifier: {namespace: [$ns], name: $t}, requirements: [], updates: $updates}')"
  request '200' -X POST -H 'Content-Type: application/json' -d "${commit}" \
    "${BASE}/namespaces/${NAMESPACE}/tables/${TABLE}" >/dev/null
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

  link_port_to_table
  ;;

unprovision)
  # The link would make the drop refuse the table's aggregate delete; remove it first.
  unlink_port_from_table

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

authorize)
  table_exists || fail "table ${NAMESPACE}.${TABLE} does not exist; provision the data product before authorizing it"
  commit_updates "$(jq -n --arg g "${GRANTEE}" \
    '[{action: "set-properties", updates: {"access.status": "AUTHORIZED", "access.granted-to": $g}}]')"
  echo "granted ${GRANTEE} access to ${NAMESPACE}.${TABLE}"
  ;;

reject)
  if ! table_exists; then
    echo "table ${NAMESPACE}.${TABLE} does not exist; nothing to withdraw"
  else
    # The status is set rather than removed: REJECTED is an answer, and a table with no access
    # property at all is one nobody has decided about yet. The grantee goes, because it no longer
    # holds.
    commit_updates '[{"action": "set-properties", "updates": {"access.status": "REJECTED"}},
                     {"action": "remove-properties", "removals": ["access.granted-to"]}]'
    echo "withdrew access to ${NAMESPACE}.${TABLE}"
  fi
  ;;

*)
  fail "unknown operation: ${OPERATION}"
  ;;
esac
