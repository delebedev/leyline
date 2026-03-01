#!/usr/bin/env python3
"""Parse Surefire JUnit XML reports and emit a compact test summary.

Usage: python3 test-summary.py <module-target-dir>
  e.g. python3 test-summary.py target

Reads junitreports/TEST-*.xml, writes <target>/test-summary.txt,
prints same summary to stdout.
"""

import os
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

STALENESS_SECONDS = 300
SLOW_THRESHOLD = 3.0


def find_reports(target_dir: Path) -> list[Path]:
    """Find TEST-*.xml files modified within the staleness window."""
    junit_dir = target_dir / "surefire-reports" / "junitreports"
    if not junit_dir.is_dir():
        return []
    cutoff = time.time() - STALENESS_SECONDS
    reports = []
    for f in sorted(junit_dir.glob("TEST-*.xml")):
        if f.stat().st_mtime >= cutoff:
            reports.append(f)
    return reports


def parse_reports(reports: list[Path]):
    """Return (tests, failures, errors, skipped, duration, failed_details, slow_tests)."""
    total = failures = errors = skipped = 0
    duration = 0.0
    failed_details = []  # (class.method, duration, message)
    slow_tests = []  # (class.method, duration)

    for report in reports:
        try:
            tree = ET.parse(report)
        except ET.ParseError:
            continue
        root = tree.getroot()

        for tc in root.findall("testcase"):
            cls = tc.get("classname", "")
            # shorten to simple class name
            cls = cls.rsplit(".", 1)[-1] if "." in cls else cls
            name = tc.get("name", "")
            t = float(tc.get("time", "0"))
            total += 1
            duration += t

            fail_el = tc.find("failure")
            err_el = tc.find("error")
            skip_el = tc.find("skipped")

            if fail_el is not None:
                failures += 1
                msg = _extract_message(fail_el)
                failed_details.append((f"{cls}.{name}", t, msg))
            elif err_el is not None:
                errors += 1
                msg = _extract_message(err_el)
                failed_details.append((f"{cls}.{name}", t, msg))
            elif skip_el is not None:
                skipped += 1
            else:
                if t >= SLOW_THRESHOLD:
                    slow_tests.append((f"{cls}.{name}", t))

    return total, failures, errors, skipped, duration, failed_details, slow_tests


def _extract_message(element) -> str:
    """Extract assertion/error message, skip stack frames."""
    msg = element.get("message", "")
    if not msg:
        text = element.text or ""
        # take first non-stack line
        for line in text.strip().splitlines():
            stripped = line.strip()
            if (
                stripped
                and not stripped.startswith("at ")
                and not stripped.startswith("...")
            ):
                msg = stripped
                break
    # first 3 meaningful lines, capped at 200 chars total
    lines = [l.strip() for l in msg.strip().splitlines() if l.strip()]
    lines = [l for l in lines if not l.startswith("at ") and not l.startswith("...")]
    result = "\n    ".join(lines[:3])
    return result[:200]


def format_summary(
    total, failures, errors, skipped, duration, failed_details, slow_tests
) -> str:
    """Build the summary string per spec."""
    lines = []

    fail_count = failures + errors
    if fail_count == 0:
        passed = total - skipped
        lines.append(f"PASS {passed}/{passed} in {duration:.1f}s")
        if skipped:
            lines.append(f"  ({skipped} skipped)")
    else:
        passed = total - fail_count - skipped
        parts = []
        if failures:
            parts.append(f"{failures} failure{'s' if failures != 1 else ''}")
        if errors:
            parts.append(f"{errors} error{'s' if errors != 1 else ''}")
        lines.append(f"FAIL {passed}/{total} ({', '.join(parts)}) in {duration:.1f}s")
        lines.append("")
        lines.append("FAILED:")
        for ident, t, msg in failed_details:
            lines.append(f"  {ident} ({t:.1f}s)")
            if msg:
                lines.append(f"    {msg}")

    if slow_tests:
        lines.append("")
        lines.append(f"SLOW (>{SLOW_THRESHOLD:.0f}s):")
        for ident, t in sorted(slow_tests, key=lambda x: -x[1]):
            lines.append(f"  {ident} ({t:.1f}s)")

    return "\n".join(lines) + "\n"


def main():
    if len(sys.argv) < 2:
        print("Usage: test-summary.py <module-target-dir>", file=sys.stderr)
        sys.exit(1)

    target_dir = Path(sys.argv[1])

    reports = find_reports(target_dir)
    if not reports:
        msg = "COMPILE_ERROR: no test results found. Check Maven output.\n"
        out_file = target_dir / "test-summary.txt"
        out_file.parent.mkdir(parents=True, exist_ok=True)
        out_file.write_text(msg)
        print(msg, end="")
        sys.exit(1)

    total, failures, errors, skipped, duration, failed_details, slow_tests = (
        parse_reports(reports)
    )
    summary = format_summary(
        total, failures, errors, skipped, duration, failed_details, slow_tests
    )

    out_file = target_dir / "test-summary.txt"
    out_file.write_text(summary)
    print(summary, end="")


if __name__ == "__main__":
    main()
