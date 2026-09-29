#!/usr/bin/env bash
# The build-and-test step, shared by GitLab CI and GitHub Actions so the two pipelines
# cannot drift: whatever passes here passes there. Runs from the repository root.
#
# `install` rather than `verify` because the modules depend on each other through the
# local repository, and the image scripts package single modules afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."

mvn clean install spotless:check test -B "$@"
