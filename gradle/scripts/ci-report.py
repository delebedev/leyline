#!/usr/bin/env python3
"""Generate a combined CI report (coverage + slow tests) as GitHub-flavored markdown.

Usage: ci-report.py [--jacoco-xml FILE ...] [--test-xml-dir DIR ...]

Outputs markdown to stdout suitable for posting as a PR comment.
"""

import argparse
import xml.etree.ElementTree as ET
from pathlib import Path

SLOW_THRESHOLD = 3.0


def parse_jacoco(paths: list[Path]) -> list[tuple]:
    """Parse JaCoCo XML reports. Returns (module_rows, total_covered, total_lines).

    When multiple XMLs report the same module from different suites (gate vs
    integration), they appear as separate rows — XML-level merging can't
    compute a true line-level union.  The suite label is inferred from the
    artifact path (coverage-gate/ vs coverage-integration/).
    """
    module_rows = []
    seen: dict[str, int] = {}  # track duplicate module names

    for path in paths:
        if not path.exists():
            continue
        tree = ET.parse(path)
        root = tree.getroot()
        module = root.get("name", path.parent.parent.parent.name)

        covered = 0
        missed = 0
        for counter in root.findall("counter"):
            if counter.get("type") == "LINE":
                covered += int(counter.get("covered", 0))
                missed += int(counter.get("missed", 0))

        total = covered + missed
        if total == 0:
            continue

        # Disambiguate duplicate modules with suite label from artifact path
        # e.g. ci-artifacts/coverage-integration/matchdoor/... → "int"
        if module in seen:
            suite = _suite_label(path)
            if suite:
                module = f"{module} ({suite})"
        seen[module] = seen.get(module, 0) + 1

        pct = 100 * covered / total
        module_rows.append((module, pct, covered, total))

    return module_rows


def _suite_label(path: Path) -> str:
    """Extract suite label from artifact path like 'ci-artifacts/coverage-gate/...'."""
    for part in path.parts:
        if part.startswith("coverage-"):
            tag = part.removeprefix("coverage-")
            return {"gate": "gate", "integration": "int"}.get(tag, tag)
    return ""


def parse_test_results(dirs: list[Path]) -> tuple[int, int, int, list, list]:
    """Parse JUnit XML test results. Returns (total, failed, skipped, failures, slow_tests)."""
    total = 0
    failed = 0
    skipped = 0
    failures = []
    slow_tests = []

    for d in dirs:
        if not d.is_dir():
            continue
        for xml_file in sorted(d.rglob("TEST-*.xml")):
            try:
                tree = ET.parse(xml_file)
            except ET.ParseError:
                continue
            for tc in tree.getroot().findall("testcase"):
                cls = tc.get("classname", "").rsplit(".", 1)[-1]
                name = tc.get("name", "")
                t = float(tc.get("time", "0"))
                total += 1

                fail_el = tc.find("failure")
                err_el = tc.find("error")
                skip_el = tc.find("skipped")

                if fail_el is not None or err_el is not None:
                    failed += 1
                    el = fail_el if fail_el is not None else err_el
                    msg = el.get("message", "")[:120]
                    failures.append((f"{cls}.{name}", t, msg))
                elif skip_el is not None:
                    skipped += 1
                elif t >= SLOW_THRESHOLD:
                    slow_tests.append((f"{cls}.{name}", t))

    return total, failed, skipped, failures, slow_tests


def format_report(
    module_rows,
    total_tests, failed_tests, skipped_tests, failures, slow_tests,
    job_name="Gate",
) -> str:
    lines = []
    lines.append(f"## CI Report — {job_name}")
    lines.append("")

    # Test summary
    passed = total_tests - failed_tests - skipped_tests
    if total_tests > 0:
        if failed_tests == 0:
            lines.append(f"**Tests:** {passed}/{passed} passed")
        else:
            lines.append(f"**Tests:** {passed}/{total_tests} passed, **{failed_tests} failed**")
        if skipped_tests > 0:
            lines[-1] += f" ({skipped_tests} skipped)"
        lines.append("")

    # Failures
    if failures:
        lines.append("<details><summary>Failed tests</summary>")
        lines.append("")
        for ident, t, msg in failures:
            lines.append(f"- `{ident}` ({t:.1f}s)")
            if msg:
                lines.append(f"  > {msg}")
        lines.append("")
        lines.append("</details>")
        lines.append("")

    # Coverage table — grand total uses only the first (gate) row per base module
    # to avoid inflating the number when integration adds a second row.
    if module_rows:
        seen_base = set()
        deduped_covered = 0
        deduped_lines = 0
        for module, _pct, covered, total in module_rows:
            base = module.split(" (")[0]
            if base not in seen_base:
                seen_base.add(base)
                deduped_covered += covered
                deduped_lines += total
        grand_pct = 100 * deduped_covered / deduped_lines if deduped_lines > 0 else 0
        lines.append(f"**Coverage:** {grand_pct:.1f}% ({deduped_covered}/{deduped_lines} lines)")
        lines.append("")
        lines.append("| Module | Coverage | Lines |")
        lines.append("|--------|----------|-------|")
        for module, pct, covered, total in sorted(module_rows, key=lambda r: r[1]):
            bar = coverage_bar(pct)
            lines.append(f"| {module} | {bar} {pct:.0f}% | {covered}/{total} |")
        lines.append("")

    # Slow tests
    if slow_tests:
        lines.append(f"<details><summary>Slow tests (&gt;{SLOW_THRESHOLD:.0f}s): {len(slow_tests)}</summary>")
        lines.append("")
        for ident, t in sorted(slow_tests, key=lambda x: -x[1]):
            lines.append(f"- `{ident}` ({t:.1f}s)")
        lines.append("")
        lines.append("</details>")

    return "\n".join(lines) + "\n"


def coverage_bar(pct: float) -> str:
    """Simple text coverage indicator."""
    if pct >= 80:
        return "🟢"
    elif pct >= 50:
        return "🟡"
    else:
        return "🔴"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jacoco-xml", action="append", default=[], help="JaCoCo XML report path (repeatable)")
    parser.add_argument("--test-xml-dir", action="append", default=[], help="JUnit XML result directory (repeatable)")
    parser.add_argument("--job-name", default="Gate", help="Job name for the header")
    args = parser.parse_args()

    jacoco_paths = [Path(p) for p in args.jacoco_xml]
    test_dirs = [Path(p) for p in args.test_xml_dir]

    module_rows = parse_jacoco(jacoco_paths)
    total_tests, failed_tests, skipped_tests, failures, slow_tests = parse_test_results(test_dirs)

    report = format_report(
        module_rows,
        total_tests, failed_tests, skipped_tests, failures, slow_tests,
        job_name=args.job_name,
    )
    print(report)


if __name__ == "__main__":
    main()
