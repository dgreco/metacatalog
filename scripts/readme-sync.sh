#!/bin/bash
# Writes the two host-flavoured READMEs from one source of prose.
#
# GitHub renders `.github/README.md` in preference to the root one; GitLab renders only the root
# one. That is the only mechanism by which the same badge can lead somewhere different depending
# on where it is being read, and the coverage badge needs exactly that: each host's copy must open
# the report that host's own pipeline published, never the other's.
#
# Two flavours, one body. What differs is confined to the badge block between the markers, plus
# the `../` every relative link needs in the GitHub copy — it lives one directory down, so `LICENSE`
# there would mean `.github/LICENSE`.
#
#   ./scripts/readme-sync.sh           rewrite both files
#   ./scripts/readme-sync.sh --check   fail if either is stale (what CI runs)
set -eu
export LC_ALL=C

root="$(cd "$(dirname "$0")/.." && pwd)"
source_file="$root/README.md"
github_file="$root/.github/README.md"
check=""
[ "${1:-}" = "--check" ] && check=1

# The figures the static badges show. GitLab's coverage badge is live (from the `coverage:`
# keyword), so only the GitHub flavour needs the number; the test count is static on both.
# Refresh them when the figures under "Coverage" in README.md move — the two are meant to agree.
COVERAGE="55.1"
TESTS="345"

GITHUB_REPO="https://github.com/dgreco/metacatalog"
GITLAB_REPO="https://gitlab.davidgreco.it/dgreco/metacatalog"

# The reports, one per host. Both are published by that host's own pipeline: `coverage-pages` in
# .github/workflows/ci.yml, `coverage-wiki` in .gitlab-ci.yml.
GITHUB_REPORT="https://dgreco.github.io/metacatalog/"
# A wiki page rather than an HTML report: without Pages, GitLab refuses to render an HTML
# artifact in the browser at all — "the source could not be displayed because it is stored as a
# job artifact" — because inline artifact serving is done by the Pages daemon. Markdown it renders
# natively, so the `coverage-wiki` job writes the report there on every default-branch pipeline.
# If Pages is ever enabled on the instance, this is the one line to change — the `pages` job
# already publishes there and un-skips itself.
GITLAB_REPORT="$GITLAB_REPO/-/wikis/Coverage"

# Each host gets its own status badge and its own coverage badge, and every link stays on the
# host doing the rendering.
badges() {   # $1 = github | gitlab
  local host="$1" status coverage
  if [ "$host" = github ]; then
    status="[![CI]($GITHUB_REPO/actions/workflows/ci.yml/badge.svg?branch=main)]($GITHUB_REPO/actions/workflows/ci.yml)"
    coverage="[![coverage](https://img.shields.io/badge/coverage-${COVERAGE}%25-brightgreen)]($GITHUB_REPORT)"
  else
    status="[![pipeline]($GITLAB_REPO/badges/main/pipeline.svg)]($GITLAB_REPO/-/pipelines)"
    coverage="[![coverage]($GITLAB_REPO/badges/main/coverage.svg)]($GITLAB_REPORT)"
  fi
  cat <<BADGES
$status
$coverage
[![tests](https://img.shields.io/badge/tests-${TESTS}-brightgreen)](#coverage)
[![license](https://img.shields.io/github/license/dgreco/metacatalog?color=blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange)](#technology-stack)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)](#technology-stack)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18%2B-4169E1)](#prerequisites)
BADGES
}

# Replaces whatever sits between the markers with this host's badges.
flavour() {  # $1 = host, reads the source on stdin
  # Through the environment, not -v: awk's -v does not take embedded newlines, and the badge
  # block is several lines.
  BADGES="$(badges "$1")" awk '
    /<!-- BADGES:START -->/ { print; print ENVIRON["BADGES"]; skip = 1; next }
    /<!-- BADGES:END -->/   { skip = 0 }
    !skip { print }
  '
}

# `.github/README.md` sits one directory below the paths the body was written against.
descend() {
  # perl, not sed: the rewrite needs a negative lookahead to leave absolute links, anchors and
  # mailto alone, and neither BSD nor GNU sed has one. perl-base is essential in Debian, so the
  # bare image the verify job runs on already has it.
  perl -pe 's{\]\((?!https?://|#|\.\./|mailto:)}{](../}g'
}

if [ ! -f "$source_file" ]; then
  echo "readme-sync: no README.md at $source_file" >&2
  exit 1
fi
if ! grep -q '<!-- BADGES:START -->' "$source_file"; then
  echo "readme-sync: README.md has no <!-- BADGES:START --> marker" >&2
  exit 1
fi

gitlab_out="$(flavour gitlab < "$source_file")"
github_out="$(printf '%s\n' "<!-- Generated from ../README.md by scripts/readme-sync.sh. Edit that one. -->" \
              && flavour github < "$source_file" | descend)"

if [ -n "$check" ]; then
  status=0
  printf '%s\n' "$gitlab_out" | diff -q - "$source_file" >/dev/null \
    || { echo "readme-sync: README.md is stale — run ./scripts/readme-sync.sh" >&2; status=1; }
  printf '%s\n' "$github_out" | diff -q - "$github_file" >/dev/null 2>&1 \
    || { echo "readme-sync: .github/README.md is stale — run ./scripts/readme-sync.sh" >&2; status=1; }
  [ $status -eq 0 ] && echo "readme-sync: both READMEs are current"
  exit $status
fi

mkdir -p "$(dirname "$github_file")"
printf '%s\n' "$gitlab_out" > "$source_file"
printf '%s\n' "$github_out" > "$github_file"
echo "readme-sync: wrote README.md (GitLab) and .github/README.md (GitHub)"
