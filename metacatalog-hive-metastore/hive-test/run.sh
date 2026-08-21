#!/usr/bin/env bash
# Runs the Hive metastore client smoke test against a running stack.
#
# In a JDK 21 container, on the compose network, because HiveMetaStoreClient cannot run on the
# project's JDK 26 at all: it calls Subject.getSubject(), removed in JDK 24, and the escape hatch
# -Djava.security.manager=allow is rejected at VM startup on 26. That is a client-side limit — the
# server is fine on 26 — but it is why this test lives outside the Maven build.
#
#   make up-hive-d              # start the stack first
#   ./run.sh
#
# NETWORK and HIVE_METASTORE_URI can be overridden; the defaults match the compose stack.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK="${NETWORK:-metacatalog_default}"
URI="${HIVE_METASTORE_URI:-thrift://metacatalog-hive-metastore:9083}"

if ! docker network inspect "${NETWORK}" >/dev/null 2>&1; then
  echo "network ${NETWORK} not found — is the stack up? (make up-hive-d)" >&2
  exit 1
fi

# The maven cache is mounted so a re-run does not re-download the Hive tree.
exec docker run --rm \
  --network "${NETWORK}" \
  -e HIVE_METASTORE_URI="${URI}" \
  -v "${HERE}":/work \
  -v "${HOME}/.m2":/root/.m2 \
  -w /work \
  maven:3-eclipse-temurin-21 \
  mvn -B test
