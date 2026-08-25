#!/usr/bin/env bash
# Provisioning script for HiveTableOutputPortType (see docker/bulk/iceberg-demo-model.yaml),
# reached through provision-output-port.sh with the port entity's JSON values on standard input:
#   {"name":"returns","database":"sales_analytics","location":"s3://...","columns":[...]}
#
# On provision it creates the database (if missing) and an external Parquet table in the Hive
# Metastore; on unprovision it drops the table. The database is left behind deliberately — sibling
# ports of the same data product share it, and the metastore refuses to drop a non-empty one.
#
# `authorize` and `reject` are the second, independent lifecycle: they stamp the access decision
# into the table's own parameters (`access.status`, `access.granted-to` from the port's `grantee`),
# the same two names the Iceberg script writes as table properties, so both catalogs answer the
# question the same way in their own vocabulary. A Hive-speaking consumer reads it off the table it
# is already looking at.
#
# All four directions are idempotent, because a provisioning procedure may retry and the demo stack
# restarts freely.
#
# The metastore speaks Thrift, not HTTP, so this cannot be curl. It shells out to hive-cli.jar,
# a small shaded Thrift client mounted into this container by docker-compose.hive-demo.yml. The
# jar uses Thrift's generated client rather than HiveMetaStoreClient, which is what lets it run on
# this image's JDK 26 at all — the wrapper calls Subject.getSubject(), removed in JDK 24, and
# -Djava.security.manager=allow is rejected at VM startup on 26.
#
# No Parquet files are written. This is an external table describing data a pipeline produces,
# which is how Hive external tables are normally used, and it is what makes the port readable by a
# Hive-speaking consumer without the demo needing a Parquet writer.
set -euo pipefail

OPERATION="${1:?usage: provision-hive-table.sh <provision|unprovision|authorize|reject>}"
VALUES="$(cat)"

METASTORE_URI="${HIVE_METASTORE_URI:-thrift://hive-metastore:9083}"
CLI="${HIVE_CLI_JAR:-/opt/metacatalog/provisioning/hive-cli/hive-cli.jar}"

API="${METACATALOG_API_URL:-http://localhost:8080}/metacatalog/v1"
API_AUTH="${METACATALOG_USER:-admin}:${METACATALOG_PASSWORD:-admin}"

DATABASE="$(jq -r '.database' <<<"${VALUES}")"
TABLE="$(jq -r '.name' <<<"${VALUES}")"
RESOURCE_LABEL="${DATABASE}.${TABLE}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=metacatalog-api.sh
source "${HERE}/metacatalog-api.sh"
# shellcheck source=access-decision.sh
source "${HERE}/access-decision.sh"

table_entity_id() {
  entity_id HiveTable "\$ ? (@.name == \"${TABLE}\" && @.databaseName == \"${DATABASE}\")"
}

port_entity_id() {
  entity_id HiveTableOutputPortType "\$ ? (@.name == \"${TABLE}\" && @.database == \"${DATABASE}\")"
}

# Connect the port to the HiveTable entity the metastore materialised from it, exactly as the
# Iceberg script connects its port to its IcebergTable: the port's contract is fulfilled by the
# table, so the port depends on it. Sanctioned by the
# `HiveTableOutputPort DEPENDS_ON HiveTableTrait` trait relationship the demo model declares.
link_port_to_table() {
  local table_id port_id
  table_id="$(table_entity_id)"
  [[ -n "${table_id}" ]] || fail "no HiveTable entity found for ${RESOURCE_LABEL}"
  port_id="$(port_entity_id)"
  [[ -n "${port_id}" ]] || fail "no HiveTableOutputPortType entity found for ${RESOURCE_LABEL}"
  ensure_link "${port_id}" "${table_id}"
}

# Removed *before* the drop: dropping the table deletes it as an aggregate of its partitions, and
# AggregateService refuses that while a member is linked from outside the aggregate.
unlink_port_from_table() {
  local table_id port_id
  table_id="$(table_entity_id)"
  [[ -n "${table_id}" ]] || return 0
  port_id="$(port_entity_id)"
  [[ -n "${port_id}" ]] || return 0
  remove_link "${port_id}" "${table_id}"
}

[[ -r "${CLI}" ]] || {
  echo "Hive CLI not found at ${CLI}. It is built by 'make hive-cli' and mounted by" >&2
  echo "docker-compose.hive-demo.yml; the demo cannot provision a Hive port without it." >&2
  exit 1
}

# The CLI takes the port definition as-is; it reads `database`, `name` -> table, `location`,
# `columns` and the optional `description`.
#
# `grantee` is resolved here rather than in the CLI, and the two access parameter names are handed
# to it rather than compiled into it: they are shared with the Iceberg script (see
# access-decision.sh), and a Java constant on this side could drift from the shell one on the other
# without anything failing — the two catalogs would just quietly stop agreeing.
REQUEST="$(jq --arg grantee "$(grantee_of "${VALUES}")" \
              --arg statusKey "${ACCESS_STATUS_KEY}" \
              --arg granteeKey "${ACCESS_GRANTED_TO_KEY}" \
           '{database: .database, table: .name, location: .location,
             description: .description, columns: .columns, grantee: $grantee,
             accessStatusKey: $statusKey, accessGrantedToKey: $granteeKey}' <<<"${VALUES}")"

case "${OPERATION}" in
provision)
  java -jar "${CLI}" create "${METASTORE_URI}" <<<"${REQUEST}"
  link_port_to_table
  ;;
unprovision)
  # The link goes first; see unlink_port_from_table.
  unlink_port_from_table
  java -jar "${CLI}" drop "${METASTORE_URI}" <<<"${REQUEST}"
  ;;
authorize)
  # No link work here: authorization does not create or destroy the table, it only records a
  # decision about the one provisioning already made.
  java -jar "${CLI}" authorize "${METASTORE_URI}" <<<"${REQUEST}"
  ;;
reject)
  java -jar "${CLI}" reject "${METASTORE_URI}" <<<"${REQUEST}"
  ;;
*)
  echo "unknown operation: ${OPERATION}" >&2
  exit 1
  ;;
esac
