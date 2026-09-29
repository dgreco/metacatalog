#!/usr/bin/env bash
# Assembles the per-module JaCoCo HTML reports into one browsable site, which is what both
# pipelines publish: GitHub to Pages from `coverage-pages`, GitLab to its own Pages from `pages`.
#
# JaCoCo writes one report per module and there is no aggregate (see scripts/coverage.sh for
# why), so this copies each module's report into a subdirectory and writes an index that shows
# every module's percentage and links to its report. The index is plain HTML with no scripts, so
# it renders wherever the site is served.
#
#   ./scripts/coverage-site.sh [output directory]      default: target/coverage-site
set -eu
export LC_ALL=C

root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$root/target/coverage-site}"

total="$("$root/scripts/coverage.sh")"
# A fresh directory every time, or a module that lost its report would keep showing the old one.
[ -d "$out" ] && rm -r "$out"
mkdir -p "$out"

rows=""
for csv in $(find "$root" -path "*/metacatalog-*/target/site/jacoco/jacoco.csv" -not -path "*/docker/*" | sort); do
  dir="$(dirname "$csv")"
  module="$(basename "$(cd "$dir/../../.." && pwd)")"
  mkdir -p "$out/$module"
  cp -R "$dir/." "$out/$module/"
  rows="$rows$(awk -F, -v m="$module" '
    FNR == 1 { next }
    { im += $4; ic += $5; bm += $6; bc += $7; lm += $8; lc += $9 }
    END {
      if (im + ic == 0) { printf "<tr><td><a href=\"%s/\">%s</a></td><td colspan=\"4\">no instructions recorded</td></tr>\n", m, m; exit }
      printf "<tr><td><a href=\"%s/\">%s</a></td><td>%.1f%%</td><td>%.1f%%</td><td>%.1f%%</td><td>%d</td></tr>\n",
             m, m, 100 * ic / (im + ic),
             (bm + bc > 0 ? 100 * bc / (bm + bc) : 0),
             (lm + lc > 0 ? 100 * lc / (lm + lc) : 0), im + ic
    }' "$csv")
"
done

cat > "$out/index.html" <<HTML
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Meta Catalog coverage</title>
<style>
  body { font: 15px/1.5 system-ui, sans-serif; margin: 2rem auto; max-width: 56rem; padding: 0 1rem; color: #1f2328; }
  table { border-collapse: collapse; width: 100%; }
  th, td { text-align: left; padding: .4rem .6rem; border-bottom: 1px solid #d0d7de; }
  th { font-weight: 600; }
  td:not(:first-child), th:not(:first-child) { text-align: right; font-variant-numeric: tabular-nums; }
  code { background: #f6f8fa; padding: .1rem .3rem; border-radius: 4px; }
</style>
</head>
<body>
<h1>Meta Catalog coverage</h1>
<p><code>$total</code></p>
<p>Per-module JaCoCo reports. A module's figure counts only its own classes, wherever the test that ran them lives.</p>
<table>
<thead><tr><th>module</th><th>instructions</th><th>branches</th><th>lines</th><th>size</th></tr></thead>
<tbody>
$rows</tbody>
</table>
</body>
</html>
HTML
echo "coverage-site: wrote $out"
