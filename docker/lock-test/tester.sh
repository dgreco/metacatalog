#!/bin/sh
# Combined loader + verifier for the advisory-lock test. Runs as a single
# container so that --abort-on-container-exit only triggers after verification
# is complete (not when the loader finishes, which would kill the apps before
# the scheduler processes the events).
#
# Steps:
#   1. Wait for app-1 to be healthy.
#   2. POST the model and the 200 aggregate instances (400 SOURCE_CREATED events).
#   3. Wait for the mapping updater to pick up and process the events.
#   4. Verify that at least one instance logged "Advisory lock not acquired"
#      while the other held the lock and processed events.
#   5. Exit 0 on success, 1 on failure.
set -eu

APP1="${APP1:-app-1}"
APP2="${APP2:-app-2}"
APP_URL="${APP_URL:-http://app-1:8080}"
MODEL_FILE="${MODEL_FILE:-/bulk-model/bulk-model.yaml}"
INSTANCES_FILE="${INSTANCES_FILE:-/bulk-instances/bulk-instances.yaml}"
AUTH="${METACATALOG_USER:-admin}:${METACATALOG_PASSWORD:-admin}"

docker() { command docker "$@"; }

count_log() {
  # count_log <container> <pattern>
  docker logs "$1" 2>&1 | grep -c "$2" || true
}

echo "[tester] waiting for app health at ${APP_URL}/actuator/health"
until curl -fsS "${APP_URL}/actuator/health" | grep -q '"status":"UP"'; do
  echo "[tester] app not healthy yet, retrying in 3s..."
  sleep 3
done
echo "[tester] app is healthy"

echo "[tester] loading model from ${MODEL_FILE}"
http_code=$(curl -s -o /tmp/model.out -w "%{http_code}" \
  -X POST \
  -u "${AUTH}" \
  -H "Content-Type: application/octet-stream" \
  --data-binary "@${MODEL_FILE}" \
  "${APP_URL}/metacatalog/v1/bulk-creation")
if [ "${http_code}" != "204" ]; then
  echo "[tester] FAIL: model load failed with HTTP ${http_code}" >&2
  cat /tmp/model.out >&2
  exit 1
fi
echo "[tester] model loaded"

echo "[tester] loading 200 aggregate instances (400 SOURCE_CREATED events) from ${INSTANCES_FILE}"
http_code=$(curl -s -o /tmp/instances.out -w "%{http_code}" \
  -X POST \
  -u "${AUTH}" \
  -H "Content-Type: application/octet-stream" \
  --data-binary "@${INSTANCES_FILE}" \
  "${APP_URL}/metacatalog/v1/aggregate/yaml")
if [ "${http_code}" != "204" ]; then
  echo "[tester] FAIL: aggregate instances load failed with HTTP ${http_code}" >&2
  cat /tmp/instances.out >&2
  exit 1
fi
echo "[tester] aggregate instances loaded"

# Wait for the mapping updater to pick up the SOURCE_CREATED events. The
# scheduler runs every 1s, so the 400 events are picked up on the next tick.
# Allow up to 60s for the events to appear in the logs.
echo "[tester] waiting for SOURCE_CREATED events to be picked up by the scheduler"
deadline=$(( $(date +%s) + 60 ))
found=""
while [ "$(date +%s)" -lt "$deadline" ]; do
  f1=$(docker logs "$APP1" 2>&1 | grep "Found [1-9][0-9]* SOURCE_CREATED PENDING events" | tail -n 1 || true)
  f2=$(docker logs "$APP2" 2>&1 | grep "Found [1-9][0-9]* SOURCE_CREATED PENDING events" | tail -n 1 || true)
  if [ -n "$f1" ] || [ -n "$f2" ]; then
    found="yes"
    break
  fi
  sleep 1
done

if [ -z "$found" ]; then
  echo "[tester] FAIL: no instance reported SOURCE_CREATED events to process" >&2
  echo "[tester] --- $APP1 logs (tail) ---" >&2
  docker logs "$APP1" 2>&1 | tail -n 20 >&2 || true
  echo "[tester] --- $APP2 logs (tail) ---" >&2
  docker logs "$APP2" 2>&1 | tail -n 20 >&2 || true
  exit 1
fi
echo "[tester] SOURCE_CREATED events were found:"
[ -n "$f1" ] && echo "  - $APP1: $f1"
[ -n "$f2" ] && echo "  - $APP2: $f2"

# The 400 events take several seconds to process (creating mapped entities +
# relationships). While one instance holds the advisory lock and processes them,
# the other instance's @Scheduled(fixedRate=1s) fires repeatedly and each tick
# fails pg_try_advisory_xact_lock(1). Wait a few seconds for those skip logs to
# accumulate, then check.
echo "[tester] waiting for lock contention to appear in logs"
sleep 8

# Criterion 1: at least one instance must have lost the advisory lock race.
skipped1=$(count_log "$APP1" "Advisory lock not acquired")
skipped2=$(count_log "$APP2" "Advisory lock not acquired")
total_skipped=$((skipped1 + skipped2))

if [ "$total_skipped" -eq 0 ]; then
  echo "[tester] FAIL: no instance ever logged 'Advisory lock not acquired'" >&2
  echo "[tester]       -- the advisory lock did not serialize the schedulers." >&2
  echo "[tester] --- $APP1 mapping-updater logs ---" >&2
  docker logs "$APP1" 2>&1 | grep -E "Advisory|mapped entities|SOURCE_" >&2 || true
  echo "[tester] --- $APP2 mapping-updater logs ---" >&2
  docker logs "$APP2" 2>&1 | grep -E "Advisory|mapped entities|SOURCE_" >&2 || true
  exit 1
fi
echo "[tester] advisory lock contention observed: $total_skipped skip log(s)"
echo "  - $APP1: $skipped1 skip(s)"
echo "  - $APP2: $skipped2 skip(s)"

# Criterion 2: no unexpected error while processing events.
errors1=$(count_log "$APP1" "Error creating mapped entities\|Unexpected error creating mapped entities\|Error updating mapped entities\|Unexpected error updating mapped entities")
errors2=$(count_log "$APP2" "Error creating mapped entities\|Unexpected error creating mapped entities\|Error updating mapped entities\|Unexpected error updating mapped entities")
if [ "$((errors1 + errors2))" -gt 0 ]; then
  echo "[tester] FAIL: processing errors found in logs" >&2
  echo "  - $APP1: $errors1 error(s)"
  echo "  - $APP2: $errors2 error(s)"
  exit 1
fi

echo "[tester] PASS: advisory lock correctly serializes the mapping updater across instances"
