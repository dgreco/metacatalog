#!/usr/bin/env bash
# Dispatcher for the data product's output ports, invoked by ScriptProvisioningTask as
# `bash <this> <provision|unprovision|authorize|reject>` with the port entity's JSON values on
# standard input. The last two are the authorization lifecycle: the ports carry
# AuthorizableResource as well as their own port trait, so the same task serves both procedures
# and the operation is the only thing that tells them apart.
#
# It exists because the demo aggregate has two kinds of output port and the provisioning
# configuration has room for one script. `application.config.provisioning.tasks` is keyed by task
# name — `script` — and that entry carries a single `path`, so two entity types handled by the
# script task share one entry and therefore one script. Rather than change the task to pass the
# entity type, the port says what it is by its own shape.
#
# The discriminator is which key the port carries: an Iceberg port has `namespace`, a Hive port has
# `database`. That is a property of the two schemas in docker/bulk/iceberg-demo-model.yaml, and
# changing either of them means changing this line.
set -euo pipefail

OPERATION="${1:?usage: provision-output-port.sh <provision|unprovision|authorize|reject>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VALUES="$(cat)"

if jq -e 'has("namespace")' >/dev/null 2>&1 <<<"${VALUES}"; then
  echo "[dispatch] Iceberg table output port -> Iceberg REST catalog"
  exec bash "${HERE}/provision-iceberg-table.sh" "${OPERATION}" <<<"${VALUES}"
elif jq -e 'has("database")' >/dev/null 2>&1 <<<"${VALUES}"; then
  echo "[dispatch] Hive table output port -> Hive Metastore"
  exec bash "${HERE}/provision-hive-table.sh" "${OPERATION}" <<<"${VALUES}"
else
  echo "port carries neither 'namespace' (Iceberg) nor 'database' (Hive); cannot tell what it is" >&2
  exit 1
fi
