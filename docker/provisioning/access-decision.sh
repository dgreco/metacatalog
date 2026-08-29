# The vocabulary both catalogs record an access decision in.
# Sourced, not executed:  source "$(dirname "${BASH_SOURCE[0]}")/access-decision.sh"
#
# The whole point of the mixed demo is that one data product's ports answer the access question the
# same way whichever catalog published them: the Iceberg script writes these as table *properties*,
# the Hive CLI as table *parameters*, and a consumer of either reads the same names off the table it
# is already looking at. Renaming one side alone breaks exactly that, silently and only for one
# catalog's consumers — so the names, and the rendering of the values, live here, once, and both
# sides are handed them.
#
# The decision itself comes from the aggregate root, not from the port. ScriptProvisioningTask puts
# the grants the root's policy resolved for this resource in METACATALOG_ACCESS, as the same JSON
# array the catalog records in `effectiveGrants`:
#
#   [{"principal":{"id":"sales-analysts","type":"GROUP"},"permissions":["READ"]}]
#
# It is set only on `authorize` — `reject` withdraws everything and needs no list to say so — which
# is why the helpers below default it to an empty array rather than requiring it.

# The parameter/property recording whether access was granted; AUTHORIZED or REJECTED.
ACCESS_STATUS_KEY="access.status"

# Who holds anything at all: the principal ids, comma-separated. The queryable summary.
ACCESS_GRANTED_TO_KEY="access.granted-to"

# Which permissions were granted to anyone: the union, comma-separated. The other summary axis.
ACCESS_PERMISSIONS_KEY="access.permissions"

# The precise mapping the two summaries flatten: `id=PERM|PERM,id=PERM`.
ACCESS_GRANTS_KEY="access.grants"

# Every key `authorize` writes besides the status, and therefore exactly what `reject` removes.
# Deriving the removals from this list rather than restating them is what keeps a fifth key from
# being written by one direction and left behind by the other.
ACCESS_VALUE_KEYS=("${ACCESS_GRANTED_TO_KEY}" "${ACCESS_PERMISSIONS_KEY}" "${ACCESS_GRANTS_KEY}")

# The grants this run resolved, or an empty array when the operation carries none.
grants_json() {
  echo "${METACATALOG_ACCESS:-[]}"
}

# access_properties — the JSON object of value keys to set, rendered from the resolved grants.
#
# Three fixed keys rather than one per permission: a permission name is arbitrary text from the
# policy, and turning it into a property key would put the policy's vocabulary into the catalogs'
# key space, where a rename would strand the old key on every table already stamped with it.
#
# An empty grant list renders all three as empty strings, and that is a real answer, not a missing
# one: the status still says AUTHORIZED, and what it granted is nothing. A resource nobody has
# decided about carries no `access.*` keys at all, which is the distinction that matters.
access_properties() {
  jq -c \
    --arg grantedToKey "${ACCESS_GRANTED_TO_KEY}" \
    --arg permissionsKey "${ACCESS_PERMISSIONS_KEY}" \
    --arg grantsKey "${ACCESS_GRANTS_KEY}" \
    '{($grantedToKey):  ([.[].principal.id] | join(",")),
      ($permissionsKey): ([.[].permissions[]] | unique | join(",")),
      ($grantsKey):      ([.[] | .principal.id + "=" + (.permissions | join("|"))] | join(","))}' \
    <<<"$(grants_json)"
}

# A one-line human summary for the script's own output, e.g.
#   sales-analysts(GROUP)=READ, crm-pipeline(SERVICE)=WRITE
# or `nobody` when no grant reached this resource.
access_summary() {
  local summary
  summary="$(jq -r '[.[] | .principal.id + "(" + .principal.type + ")=" + (.permissions | join("|"))] | join(", ")' <<<"$(grants_json)")"
  echo "${summary:-nobody}"
}
