# The vocabulary both catalogs record an access decision in.
# Sourced, not executed:  source "$(dirname "${BASH_SOURCE[0]}")/access-decision.sh"
#
# The whole point of the mixed demo is that one data product's ports answer the access question the
# same way whichever catalog published them: the Iceberg script writes these as table *properties*,
# the Hive CLI as table *parameters*, and a consumer of either reads the same two names off the
# table it is already looking at. Renaming one side alone breaks exactly that, silently and only
# for one catalog's consumers — so the names live here, once, and both sides are handed them.
#
# `grantee` is optional on a port: there is no identity provider behind this demo, and inventing a
# placeholder principal would read as one, so a port that names nobody is granted to everyone.

# The parameter/property recording whether access was granted; AUTHORIZED or REJECTED.
ACCESS_STATUS_KEY="access.status"

# The parameter/property naming who holds it. Removed on reject: it no longer holds.
ACCESS_GRANTED_TO_KEY="access.granted-to"

# Who a port with no `grantee` grants to.
DEFAULT_GRANTEE="everyone"

# grantee_of <values-json> — the port's grantee, or the default when it names nobody.
grantee_of() {
  jq -r --arg fallback "${DEFAULT_GRANTEE}" '.grantee // $fallback' <<<"$1"
}
