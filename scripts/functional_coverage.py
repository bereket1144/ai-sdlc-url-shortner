#!/usr/bin/env python3
"""Generate the functional coverage report from the requirements and the test results.

    python3 scripts/functional_coverage.py [--reports <surefire-reports dir>] [--jacoco <jacoco.csv>] [--out <file>]

Every acceptance criterion of the requirements files (docs/platform/requirements/*.md, except the backlog) is listed
with the tests that prove it. A test proves the criteria whose id appears in its @DisplayName (for example
"AC-AGT-12.2 a complete answer passes", or "AC-AGT-63.3, 63.4 ..." for two criteria). A criterion with no test is named
with the reason, so a gap is never hidden. Unit coverage numbers come from the JaCoCo csv of the same build.
Nothing here is judged by a model: the report is plain code over files.
"""
import argparse
import csv
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REQUIREMENTS = ROOT / "docs/platform/requirements"
TESTS = ROOT / "agent-controller/src/test/java"

STORY = re.compile(r"^#{2,3} ([A-Z]+-\d+) (.+)$")
STATUS = re.compile(r"^Status: *(\w+)")
CRITERION = re.compile(r"^- (AC-[A-Z]+-\d+\.\d+) (.*)$")
AC_START = re.compile(r"(?:AC-)?(AGT|UI|RUN|EXEC|FLOW|SWAG|BUG)-(\d+\.\d+)((?:\s*(?:,|and)\s*\d+\.\d+)*)")

# Why a criterion has no automated proof, when that is by design. Anything else is reported as a plain gap.
REASONS = [
    (re.compile(r"AC-UI-\d"), "the page's own behaviour: checked by hand in a browser (no browser test)"),
    (re.compile(r"AC-AGT-(46\.4|47\.3|50\.6)$"), "the page's own behaviour: checked by hand in a browser (no browser test)"),
    (re.compile(r"AC-AGT-(49\.5|67\.7)$"), "a documentation criterion: checked by reading the file"),
    (re.compile(r"AC-AGT-12\.4$"), "holds while every stage is on its dummy node, which the unchanged FLOW tests prove; no test names it"),
    (re.compile(r"AC-AGT-(12\.6|62\.1|62\.16|63\.7)$"), "an opt-in live check against a real model, skipped by design in the normal build"),
    (re.compile(r"AC-RUN-(1\.1|2\.[1-4])$"), "the create and refuse behaviour is exercised by RunFullStackTest and RunErrorFormatFullContextTest, but no display name carries the criterion id"),
    (re.compile(r"AC-RUN-3\.4$"), "exercised by HealthEndpointTest, which names it in a comment and not in a display name"),
    (re.compile(r"AC-EXEC-1\.1$"), "superseded by the graph of sdlc-flow.md; the one-node workflow no longer exists"),
    (re.compile(r"AC-AGT-62\.11$"), "no test names it; the end-to-end test of AC-AGT-62.15 posts a requirement and the run goes on by itself"),
    (re.compile(r"AC-AGT-61\.\d"), "a GitHub Actions workflow: it can only be proved by running it on GitHub"),
]


def reason(criterion):
    for pattern, text in REASONS:
        if pattern.search(criterion):
            return text
    return "no test names this criterion"


def read_requirements():
    """{file: [story]} where a story is {id, title, status, criteria: [(id, text)]}; stale and deferred stories are left out."""
    files = {}
    for path in sorted(REQUIREMENTS.glob("*.md")):
        if "backlog" in path.name:
            continue
        stories, current = [], None
        for line in path.read_text(encoding="utf-8").splitlines():
            m = STORY.match(line)
            if m:
                current = {"id": m.group(1), "title": m.group(2), "status": "unknown", "criteria": []}
                stories.append(current)
                continue
            if current is None:
                continue
            m = STATUS.match(line)
            if m and current["status"] == "unknown":
                current["status"] = m.group(1)
                continue
            m = CRITERION.match(line)
            if m:
                current["criteria"].append((m.group(1), m.group(2)))
        stories = [s for s in stories if s["criteria"] and s["status"] not in ("stale", "deferred")]
        if stories:
            files[path.name] = stories
    return files


def criteria_in(display_name):
    """The criterion ids named in a display name: 'AC-AGT-63.3, 63.4' gives AC-AGT-63.3 and AC-AGT-63.4."""
    found = []
    for m in AC_START.finditer(display_name):
        prefix = m.group(1)
        found.append("AC-%s-%s" % (prefix, m.group(2)))
        for extra in re.findall(r"\d+\.\d+", m.group(3)):
            found.append("AC-%s-%s" % (prefix, extra))
    return found


def read_test_sources():
    """{(class, method): display name} from the @DisplayName annotations of the test sources."""
    names = {}
    # The criterion ids come first, so the first string literal is enough (names may be joined with + over lines).
    display = re.compile(r'@DisplayName\(\s*"((?:[^"\\]|\\.)*)"')
    method = re.compile(r"\bvoid\s+(\w+)\s*\(")
    for path in TESTS.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        cls = path.stem
        for m in display.finditer(text):
            after = method.search(text, m.end())
            if after and after.start() - m.end() < 400:
                names[(cls, after.group(1))] = m.group(1).replace('\\"', '"')
    return names


def read_results(reports):
    """{(class, method or display name): outcome} from the surefire xml reports."""
    results = []
    for path in sorted(Path(reports).glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        for case in suite.iter("testcase"):
            cls = case.get("classname", "").split(".")[-1]
            name = case.get("name", "")
            if case.find("failure") is not None or case.find("error") is not None:
                outcome = "FAILED"
            elif case.find("skipped") is not None:
                outcome = "SKIPPED"
            else:
                outcome = "passed"
            results.append((cls, name, outcome))
    return results


def prove(results, sources):
    """{criterion: [(class, label, outcome)]} joining criteria named in display names with the results."""
    by_method = defaultdict(list)
    for cls, name, outcome in results:
        method = re.sub(r"\(.*\)(\[\d+\])?$", "", name).strip()  # parameterized: method(String)[1]
        by_method[(cls, method)].append((name, outcome))
    proofs = defaultdict(list)
    seen = set()
    for (cls, method), display in sources.items():
        ids = criteria_in(display)
        if not ids:
            continue
        outcomes = by_method.get((cls, method)) or [(display, "not run")]
        verdict = "passed" if all(o == "passed" for _, o in outcomes) else (
            "FAILED" if any(o == "FAILED" for _, o in outcomes) else outcomes[0][1])
        for criterion in ids:
            key = (criterion, cls, method)
            if key not in seen:
                seen.add(key)
                proofs[criterion].append((cls, method, verdict))
    # Reports whose test name is the display name (when the sources could not be matched).
    for cls, name, outcome in results:
        for criterion in criteria_in(name):
            if not any(p[0] == cls for p in proofs[criterion]):
                proofs[criterion].append((cls, name[:70], outcome))
    return proofs


def jacoco(csv_path):
    rows = list(csv.DictReader(open(csv_path, encoding="utf-8")))
    totals = {}
    for kind in ("INSTRUCTION", "LINE", "BRANCH", "METHOD"):
        covered = sum(int(r[kind + "_COVERED"]) for r in rows)
        missed = sum(int(r[kind + "_MISSED"]) for r in rows)
        totals[kind] = (covered, covered + missed)
    gaps = sorted(((int(r["LINE_MISSED"]) + int(r["BRANCH_MISSED"]), r["PACKAGE"].split(".")[-1], r["CLASS"],
                    int(r["LINE_MISSED"]), int(r["BRANCH_MISSED"])) for r in rows
                   if int(r["LINE_MISSED"]) or int(r["BRANCH_MISSED"])), reverse=True)
    return totals, gaps


def pct(pair):
    return "%d/%d (%.1f%%)" % (pair[0], pair[1], 100.0 * pair[0] / pair[1] if pair[1] else 100.0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", default=str(ROOT / "agent-controller/target/surefire-reports"))
    parser.add_argument("--jacoco", default=str(ROOT / "agent-controller/target/site/jacoco/jacoco.csv"))
    parser.add_argument("--out", default=str(ROOT / "reports/coverage/functional-coverage.md"))
    args = parser.parse_args()

    files = read_requirements()
    results = read_results(args.reports)
    proofs = prove(results, read_test_sources())
    totals, gaps = jacoco(args.jacoco)

    tests_run = len(results)
    failed = sum(1 for _, _, o in results if o == "FAILED")
    skipped = sum(1 for _, _, o in results if o == "SKIPPED")
    all_criteria = [(f, s, c) for f, stories in files.items() for s in stories for c in s["criteria"]]
    passing = [c for _, _, (c, _) in all_criteria if proofs.get(c) and all(p[2] == "passed" for p in proofs[c])]
    failing = [c for _, _, (c, _) in all_criteria if proofs.get(c) and any(p[2] == "FAILED" for p in proofs[c])]
    missing = [(f, s, c) for f, s, c in all_criteria if not proofs.get(c[0])]

    out = []
    out.append("# Functional and unit coverage report\n")
    out.append("Generated by `scripts/functional_coverage.py` from the acceptance criteria in "
               "`docs/platform/requirements/` and the test results of one build. A test proves the criteria named in "
               "its `@DisplayName`. Stories marked `stale` or `deferred` are not part of this report (see "
               "`agent-pipeline-backlog.md`). Nothing in this file is written by hand.\n")
    out.append("## Summary\n")
    out.append("| Measure | Value |\n| --- | --- |")
    out.append("| Tests run | %d (%d failed, %d skipped) |" % (tests_run, failed, skipped))
    out.append("| Acceptance criteria in scope | %d |" % len(all_criteria))
    out.append("| With a passing proving test | %d (%.1f%%) |" % (len(passing), 100.0 * len(passing) / max(1, len(all_criteria))))
    out.append("| With a failing proving test | %d |" % len(failing))
    out.append("| Without a proving test | %d |" % len(missing))
    out.append("| Unit coverage, lines | %s |" % pct(totals["LINE"]))
    out.append("| Unit coverage, branches | %s |" % pct(totals["BRANCH"]))
    out.append("| Unit coverage, methods | %s |" % pct(totals["METHOD"]))
    out.append("")
    out.append("The target is 100% of criteria proved and 100% unit coverage. Both are missed; every gap is named below "
               "with its reason, and the build gate is 85% until the coverage sweep (ADR 0008).\n")

    out.append("## Criteria without a proving test\n")
    if missing:
        out.append("| Criterion | Story | Reason |\n| --- | --- | --- |")
        for f, s, (cid, _) in missing:
            out.append("| %s | %s %s | %s |" % (cid, s["id"], s["title"][:50], reason(cid)))
    else:
        out.append("None.")
    out.append("")
    if failing:
        out.append("## Criteria whose proving test fails\n")
        out.append(", ".join(failing) + "\n")

    out.append("## Unit coverage gaps (classes with missed lines or branches)\n")
    out.append("| Package | Class | Missed lines | Missed branches |\n| --- | --- | ---: | ---: |")
    for _, package, cls, lines, branches in gaps[:25]:
        out.append("| %s | %s | %d | %d |" % (package, cls, lines, branches))
    if len(gaps) > 25:
        out.append("\n%d more classes have smaller gaps; the full list is `target/site/jacoco/index.html`." % (len(gaps) - 25))
    out.append("\nThe gaps are mostly error paths of the process, git and Docker adapters and defensive checks that no "
               "criterion asks for; the light-test rule (ADR 0008) leaves them to a later sweep.\n")

    out.append("## Criteria and their proving tests\n")
    for f, stories in files.items():
        out.append("### %s\n" % f)
        for s in stories:
            out.append("**%s %s** (status: %s)\n" % (s["id"], s["title"], s["status"]))
            out.append("| Criterion | Proving tests | Result |\n| --- | --- | --- |")
            for cid, _text in s["criteria"]:
                tests = proofs.get(cid)
                if tests:
                    names = "<br>".join("%s.%s" % (c, m) for c, m, _ in tests[:4]) + (
                        "<br>+%d more" % (len(tests) - 4) if len(tests) > 4 else "")
                    result = "passed" if all(t[2] == "passed" for t in tests) else ", ".join(sorted({t[2] for t in tests}))
                else:
                    names, result = "none", "not proved"
                out.append("| %s | %s | %s |" % (cid, names, result))
            out.append("")
    Path(args.out).write_text("\n".join(out) + "\n", encoding="utf-8")
    print("criteria %d, passing %d, failing %d, without a test %d; tests %d (failed %d, skipped %d); wrote %s" % (
        len(all_criteria), len(passing), len(failing), len(missing), tests_run, failed, skipped, args.out))


if __name__ == "__main__":
    sys.exit(main())
