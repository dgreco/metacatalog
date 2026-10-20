#!/usr/bin/env bash
# Sets the project version in every POM at once: the parent and all ten modules move together,
# so a release never ships modules that disagree about which release they belong to.
#
#   scripts/set-version.sh 1.1.0             # cutting a release on its release/X.Y branch
#   scripts/set-version.sh 1.2.0-SNAPSHOT    # opening the next cycle on main
#
# The version is the POMs' only home. Nothing else in the tree spells it out — the compose files
# build images as :local, and the running application reads its version from the build — so no
# other file needs changing. See README § Releases and Versioning for the whole workflow.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:?usage: scripts/set-version.sh <X.Y.Z | X.Y.Z-SNAPSHOT>}"
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]]; then
  echo "Not a semantic version: $VERSION (expected X.Y.Z or X.Y.Z-SNAPSHOT)" >&2
  exit 1
fi

# The version lives in three places, and only these are edited: the root POM's own coordinates,
# its `project.version` property, and the <parent> block of each module (the modules themselves
# say ${project.version}). versions:set is not used because it also rewrites every
# ${project.version} reference into a literal, and misses the property.
perl -0pi -e "s{(<artifactId>metacatalog</artifactId>\\s*<version>)[^<]+(</version>)}{\${1}${VERSION}\${2}}g" \
  pom.xml metacatalog-*/pom.xml
perl -pi -e "s{<project.version>[^<]+</project.version>}{<project.version>${VERSION}</project.version>}" pom.xml

modules=(metacatalog-*/pom.xml)
expected=$((2 + ${#modules[@]}))
found=$(V="$VERSION" perl -0ne '
  $n += () = m{<artifactId>metacatalog</artifactId>\s*<version>\Q$ENV{V}\E</version>}g;
  $n += () = m{<project.version>\Q$ENV{V}\E</project.version>}g;
  END { print $n + 0 }' pom.xml "${modules[@]}")
if [ "$found" -ne "$expected" ]; then
  echo "Expected $expected occurrences of $VERSION across the POMs, found $found." >&2
  exit 1
fi
actual=$(mvn -q -B help:evaluate -Dexpression=project.version -DforceStdout)
if [ "$actual" != "$VERSION" ]; then
  echo "Maven reports $actual, not $VERSION." >&2
  exit 1
fi
echo "Version set to $VERSION in pom.xml and ${#modules[@]} modules."
