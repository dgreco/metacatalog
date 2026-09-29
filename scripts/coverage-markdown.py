#!/usr/bin/env python3
"""Renders the per-module JaCoCo CSVs as one Markdown coverage report.

For the GitLab mirror, which cannot display an HTML report at all: inline artifact serving is the
Pages daemon's job, and that instance has no Pages. A wiki page, though, is Markdown that GitLab
renders natively at a stable URL — so the report goes there instead, and the badge has somewhere
to point that opens in a browser rather than downloading.

What it loses against JaCoCo's HTML is the clickable source view. What it keeps is the part
anybody reads: the totals, and which packages are dark.

    coverage-markdown.py <heading> <jacoco.csv>... > page.md
"""

import csv
import sys
from collections import defaultdict
from pathlib import Path


def pct(missed, covered):
    total = missed + covered
    return 100.0 * covered / total if total else 100.0


def bar(value):
    """A ten-cell bar. Markdown has no charts, and a number in a table is easy to skim past."""
    filled = int(round(value / 10.0))
    return "█" * filled + "·" * (10 - filled)


class Totals:
    def __init__(self):
        self.instructions = [0, 0]
        self.branches = [0, 0]
        self.lines = [0, 0]

    def add(self, row):
        self.instructions[0] += int(row["INSTRUCTION_MISSED"])
        self.instructions[1] += int(row["INSTRUCTION_COVERED"])
        self.branches[0] += int(row["BRANCH_MISSED"])
        self.branches[1] += int(row["BRANCH_COVERED"])
        self.lines[0] += int(row["LINE_MISSED"])
        self.lines[1] += int(row["LINE_COVERED"])

    def row(self, name):
        i = pct(*self.instructions)
        return (f"| {name} | {bar(i)} {i:.1f}% | {pct(*self.branches):.1f}% | "
                f"{pct(*self.lines):.1f}% | {self.instructions[0] + self.instructions[1]:,} |")


def main(argv):
    if len(argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    heading = argv[1]

    overall, by_module, by_package = Totals(), defaultdict(Totals), defaultdict(Totals)
    for path in argv[2:]:
        # <module>/target/site/jacoco/jacoco.csv — the module is the directory name, which is
        # stable; the CSV's GROUP column carries the POM's display name, which is not.
        module = Path(path).resolve().parents[3].name
        with open(path, newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                overall.add(row)
                by_module[module].add(row)
                by_package[row["PACKAGE"]].add(row)

    header = ("| | instructions | branches | lines | size |\n"
              "|---|---|---|---|---|")

    print(f"# {heading}\n")
    print(header)
    print(overall.row("**whole tree**"))
    print("\n## By module\n")
    print(header)
    for name in sorted(by_module, key=lambda n: -pct(*by_module[n].instructions)):
        print(by_module[name].row(f"`{name}`"))
    print("""
A module's figure counts only what its own classes ran, wherever the test that ran them lives.
""")
    print("## By package\n")
    print(header)
    for name in sorted(by_package, key=lambda n: -pct(*by_package[n].instructions)):
        print(by_package[name].row(f"`{name}`"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
