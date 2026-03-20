from __future__ import annotations

import json
import re
import time
from pathlib import Path

from .common import try_remove
from .debug_api import fetch_api
from .macos import capture_window, mtga_window_bounds, ocr
from .shell import run


def cmd_issues(args: list[str]) -> None:
    days = 1
    if args:
        try:
            days = int(args[0])
        except ValueError:
            pass

    sessions_dir = Path("/tmp/arena/sessions")
    if not sessions_dir.exists():
        print("No session logs found")
        return

    issues: dict[str, dict] = {}
    total_commands = 0
    total_errors = 0
    total_sessions = 0

    cutoff = time.time() - days * 86400
    for date_dir in sorted(sessions_dir.iterdir(), reverse=True):
        if not date_dir.is_dir():
            continue
        for log_file in sorted(date_dir.iterdir(), reverse=True):
            if not log_file.name.endswith(".jsonl"):
                continue
            if log_file.stat().st_mtime < cutoff:
                continue
            total_sessions += 1
            for line in log_file.read_text().splitlines():
                if not line.strip():
                    continue
                total_commands += 1
                if '"error":true' not in line:
                    continue
                total_errors += 1
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue
                cmd = entry.get("cmd", "?")
                cmd_args = json.dumps(entry.get("args", []))
                stderr = entry.get("stderr", "")
                key = f"{cmd}|{cmd_args}"
                if key in issues:
                    issues[key]["count"] += 1
                else:
                    issues[key] = {
                        "cmd": cmd,
                        "args": cmd_args,
                        "stderr": stderr,
                        "count": 1,
                    }

    print(f"=== Arena Session Summary (last {days}d) ===")
    print(
        f"Sessions: {total_sessions} | Commands: {total_commands} | Errors: {total_errors}"
    )
    if not issues:
        print("No issues found.")
        return
    print()
    print("Issues (by frequency):")
    for issue in sorted(issues.values(), key=lambda i: i["count"], reverse=True):
        print(f"  {issue['count']}x  {issue['cmd']} {issue['args']}")
        if issue["stderr"]:
            print(f"       → {issue['stderr'][:120]}")


def cmd_health(args: list[str]) -> None:
    checks: list[tuple[str, bool, str]] = []

    state = fetch_api("/api/state")
    server_ok = state is not None
    checks.append(
        (
            "Server (:8090)",
            server_ok,
            "ok" if server_ok else "not responding — run: just serve",
        )
    )

    code, out, _ = run("lsof", "-i", ":30010", "-sTCP:LISTEN")
    listen_ok = code == 0 and "LISTEN" in out
    checks.append(
        ("Port :30010 LISTEN", listen_ok, "ok" if listen_ok else "not listening")
    )

    code, out, _ = run("lsof", "-i", ":30010", "-sTCP:ESTABLISHED")
    conn_ok = code == 0 and "ESTABLISHED" in out
    checks.append(
        (
            "Client connected",
            conn_ok,
            "ok" if conn_ok else "MTGA not connected — run: arena launch",
        )
    )

    bounds = mtga_window_bounds()
    win_ok = bounds is not None
    win_msg = (
        f"ok ({bounds[2]}x{bounds[3]} at {bounds[0]},{bounds[1]})"
        if bounds
        else "MTGA window not found"
    )
    checks.append(("MTGA window", win_ok, win_msg))

    ocr_ok = False
    ocr_count = 0
    if win_ok:
        img = "/tmp/arena/_health_capture.png"
        cap_bounds = capture_window(img)
        if cap_bounds:
            code, stdout, _ = ocr(img)
            if code == 0 and stdout.strip():
                items = json.loads(stdout)
                ocr_count = len(items)
                ocr_ok = ocr_count > 0
        try_remove(img)
    checks.append(("OCR", ocr_ok, f"ok ({ocr_count} items)" if ocr_ok else "failed"))

    scale_msg = "unknown"
    if win_ok:
        img = "/tmp/arena/_health_hires.png"
        capture_window(img, hires=True)
        _, sips_out, _ = run("sips", "-g", "pixelWidth", str(img))
        m = re.search(r"pixelWidth:\s*(\d+)", sips_out)
        if m and bounds:
            img_w = int(m.group(1))
            ratio = img_w / bounds[2] if bounds[2] > 0 else 0
            scale_msg = f"{ratio:.0f}x (image {img_w}px, window {bounds[2]} logical)"
        try_remove(img)
    checks.append(("Display scale", True, scale_msg))

    all_ok = all(ok for _, ok, _ in checks)
    for name, ok, msg in checks:
        status = "PASS" if ok else "FAIL"
        print(f"  [{status}] {name}: {msg}")

    if all_ok:
        print("\nAll checks passed. Ready for automation.")
    else:
        print("\nSome checks failed. Fix before automating.")
        raise SystemExit(1)
