#!/bin/sh
# One-shot bulk loader invoked by docker-compose.
# Waits for the app to be healthy, then POSTs the model definition first and
# the aggregate instances second. Exits non-zero on any failure so compose
# surfaces the error.
set -eu

APP_URL="${APP_URL:-http://app:8080}"
MODEL_FILE="${MODEL_FILE:-/bulk/bulk-model.yaml}"
INSTANCES_FILE="${INSTANCES_FILE:-/bulk/bulk-instances.yaml}"

# HTTP Basic credentials matching application-docker.yaml (admin / admin).
AUTH="${METACATALOG_USER:-admin}:${METACATALOG_PASSWORD:-admin}"

echo "[bulk-loader] waiting for app health at ${APP_URL}/actuator/health"
until curl -fsS "${APP_URL}/actuator/health" | grep -q '"status":"UP"'; do
  echo "[bulk-loader] app not healthy yet, retrying in 3s..."
  sleep 3
done
echo "[bulk-loader] app is healthy"

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
echo "[bulk-loader] done"
