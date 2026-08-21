# Shared helpers for talking to the metacatalog REST API from a provisioning script.
# Sourced, not executed:  source "$(dirname "${BASH_SOURCE[0]}")/metacatalog-api.sh"
#
# Both provisioning scripts connect their output port to the resource they materialised — the
# Iceberg one to its IcebergTable, the Hive one to its HiveTable — and the lookup, linking and
# unlinking are identical either side. The subtleties below were paid for once and should not be
# rediscovered in a copy.
#
# The caller must have set API, API_AUTH and RESOURCE_LABEL (used only in error messages).

fail() { echo "$1" >&2; exit 1; }

# Per-invocation, because the resources of one aggregate have no dependencies between them and are
# provisioned in parallel: several copies of a script run at once in the same container, and a
# shared path would have them overwrite each other's bodies — so a failure could report the response
# of a different table entirely, and that text is what ends up in provisioningResult.
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
# A lookup that finds nothing is empty and successful, including when the request itself fails
# because the entity type does not exist: unprovisioning has to tolerate a catalog that was never
# provisioned, and `set -e` would otherwise abort the script at the assignment — before the caller's
# `|| return 0` could express that.
#
# More than one match is fatal. The caller is about to link or unlink exactly one entity, and
# nothing stops two data products from exposing a port of the same name in the same namespace;
# picking arbitrarily would wire the wrong port to the wrong table and report success.
entity_id() {
  local body count
  if ! body=$(curl -sf -u "${API_AUTH}" --get \
      --data-urlencode "entityTypeName=$1" \
      --data-urlencode "queryPath=$2" \
      "${API}/entity"); then
    return 0
  fi
  count=$(jq 'length' <<<"${body}")
  ((count <= 1)) || fail "found ${count} $1 entities matching ${RESOURCE_LABEL}; refusing to guess which one this port means"
  jq -r '.[0].id // empty' <<<"${body}"
}

# link_exists <sourceId> <targetId> — is the source already DEPENDS_ON-linked to the target?
link_exists() {
  curl -sf -u "${API_AUTH}" "${API}/entity/link/$1/DEPENDS_ON" \
    | jq -e --arg id "$2" 'any(.[]; .id == $id)' >/dev/null
}

# ensure_link <portId> <resourceId> — connect the port to the resource that fulfils it.
ensure_link() {
  if link_exists "$1" "$2"; then
    echo "output port already linked to its resource entity"
  else
    request '204' -X POST -u "${API_AUTH}" -H 'Content-Type: application/json' \
      -d "$(jq -n --arg s "$1" --arg t "$2" \
        '{sourceEntityId: $s, relationshipTypeName: "DEPENDS_ON", targetEntityId: $t}')" \
      "${API}/entity/link" >/dev/null
    echo "linked output port $1 DEPENDS_ON resource entity $2"
  fi
}

# remove_link <portId> <resourceId> — take the link away before the resource is dropped.
#
# Load-bearing ordering: dropping the resource deletes it as an aggregate, and AggregateService
# refuses that while a member is linked from outside the aggregate. Unlink first or the drop fails.
remove_link() {
  if link_exists "$1" "$2"; then
    request '204' -X DELETE -u "${API_AUTH}" "${API}/entity/link/$1/DEPENDS_ON/$2" >/dev/null
    echo "unlinked output port $1 from resource entity $2"
  fi
}
