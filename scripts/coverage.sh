#!/usr/bin/env bash
# Prints one line of coverage totals from the per-module JaCoCo CSVs, in a shape both pipelines
# can read:
#
#   TOTAL coverage: 61.2% instructions (61234/100000), 48.9% branches, 63.0% lines [10 modules]
#
# GitLab's `coverage:` keyword scrapes the first number out of the job log; the GitHub job appends
# the same line to the run summary. One script, so the two cannot disagree about what the
# project's coverage is — which is the whole reason the number is computed here rather than by a
# regex in each pipeline.
#
# Per-module rather than an aggregate report: the Iceberg catalog and the Hive Metastore are
# independent applications that nothing else depends on, so no single module's `report-aggregate`
# could fold them in. Summing the module CSVs credits a class to the module that owns it, wherever
# the test that exercised it lives.
set -eu

# The C locale, or awk formats 73.3 as "73,3" wherever the developer's locale says so — and the
# pipelines scrape this line with a regex that expects a dot.
export LC_ALL=C

root="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC2207
files=($(find "$root" -path "*/metacatalog-*/target/site/jacoco/jacoco.csv" -not -path "*/docker/*" | sort))

if [ ${#files[@]} -eq 0 ]; then
  echo "coverage: no JaCoCo report found — run 'mvn verify' first" >&2
  exit 1
fi

# JaCoCo's CSV columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,
# BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...
awk -F, -v modules="${#files[@]}" '
  FNR == 1 || $1 == "GROUP" { next }
  {
    im += $4; ic += $5
    bm += $6; bc += $7
    lm += $8; lc += $9
  }
  END {
    if (im + ic == 0) { print "coverage: the reports are empty (no instructions recorded)" > "/dev/stderr"; exit 1 }
    printf "TOTAL coverage: %.1f%% instructions (%d/%d), %.1f%% branches, %.1f%% lines [%d modules]\n",
           100 * ic / (im + ic), ic, im + ic,
           (bm + bc > 0 ? 100 * bc / (bm + bc) : 0),
           (lm + lc > 0 ? 100 * lc / (lm + lc) : 0),
           modules
  }
' "${files[@]}"
