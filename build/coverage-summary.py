#!/usr/bin/env python3
"""Parse JaCoCo XML report and print per-class coverage sorted by percentage."""

import sys
import xml.etree.ElementTree as ET


def main():
    path = (
        sys.argv[1]
        if len(sys.argv) > 1
        else "target/site/jacoco/jacoco.xml"
    )
    min_lines = int(sys.argv[2]) if len(sys.argv) > 2 else 15

    tree = ET.parse(path)
    root = tree.getroot()

    # Package-level summary
    pkg_rows = []
    for pkg in root.findall(".//package"):
        name = pkg.get("name").replace("/", ".")
        for c in pkg.findall("counter"):
            if c.get("type") == "LINE":
                missed, covered = int(c.get("missed")), int(c.get("covered"))
                total = missed + covered
                if total > 0:
                    pkg_rows.append((100 * covered / total, covered, total, name))

    # Class-level detail
    cls_rows = []
    total_covered = 0
    total_lines = 0
    for pkg in root.findall(".//package"):
        pname = pkg.get("name").replace("/", ".")
        for cls in pkg.findall("class"):
            cname = cls.get("name").split("/")[-1]
            # Skip synthetic Kotlin classes (lambdas, companions with 1 line)
            if "." in cname or "$" in cname:
                continue
            for c in cls.findall("counter"):
                if c.get("type") == "LINE":
                    missed, covered = int(c.get("missed")), int(c.get("covered"))
                    total = missed + covered
                    total_covered += covered
                    total_lines += total
                    if total >= min_lines:
                        cls_rows.append(
                            (100 * covered / total, covered, total, f"{pname}.{cname}")
                        )

    # Print
    if total_lines > 0:
        print(
            f"Overall: {100 * total_covered / total_lines:.1f}% ({total_covered}/{total_lines} lines)\n"
        )

    print("By package:")
    for pct, cov, tot, name in sorted(pkg_rows, key=lambda r: r[0]):
        print(f"  {pct:5.1f}%  {cov:3d}/{tot:3d}  {name}")

    print(f"\nBy class (>={min_lines} lines):")
    for pct, cov, tot, name in sorted(cls_rows, key=lambda r: r[0]):
        print(f"  {pct:5.1f}%  {cov:3d}/{tot:3d}  {name}")


if __name__ == "__main__":
    main()
