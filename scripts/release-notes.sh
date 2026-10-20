#!/usr/bin/env bash
# Prints one version's section of CHANGELOG.md, without its heading: the release notes for the
# GitLab and GitHub Releases of that version, so the changelog stays their only source.
#
#   scripts/release-notes.sh 1.0.0 > notes.md
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:?usage: scripts/release-notes.sh <X.Y.Z>}"
notes=$(awk -v heading="## [${VERSION}]" '
  index($0, heading) == 1 { found = 1; next }
  found && /^## \[/ { exit }
  found && /^\[[^]]+\]: / { exit }
  found { print }
' CHANGELOG.md)
if [ -z "${notes//[[:space:]]/}" ]; then
  echo "CHANGELOG.md has no entry for ${VERSION}" >&2
  exit 1
fi
printf '%s\n' "$notes" | sed -e '/./,$!d'
