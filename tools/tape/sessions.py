"""Pure Python session inspection — reads jsonl directly, no JVM needed."""

import json
import os
import sys
import glob


def find_sessions(recordings_dir="recordings"):
    """List all recording session directories, newest first."""
    dirs = sorted(glob.glob(os.path.join(recordings_dir, "*", "")), reverse=True)
    sessions = []
    for d in dirs:
        name = os.path.basename(d.rstrip("/"))
        if name == "latest":
            continue
        has_fd = os.path.exists(os.path.join(d, "capture", "fd-frames.jsonl"))
        has_md = os.path.exists(os.path.join(d, "md-frames.jsonl"))
        has_analysis = os.path.exists(os.path.join(d, "analysis.json"))
        has_engine = os.path.isdir(os.path.join(d, "engine"))
        has_capture = os.path.isdir(os.path.join(d, "capture"))
        if has_capture:
            kind = "proxy"
        elif has_engine:
            kind = "engine"
        else:
            kind = "-"
        sessions.append(
            {
                "name": name,
                "path": d,
                "kind": kind,
                "has_fd": has_fd,
                "has_md": has_md,
                "has_analysis": has_analysis,
            }
        )
    return sessions


def resolve_session(session_hint=None, recordings_dir="recordings"):
    """Resolve a session hint (name, substring, or None=latest) to a path."""
    if session_hint is None:
        sessions = find_sessions(recordings_dir)
        if not sessions:
            return None
        return sessions[0]["path"]
    exact = os.path.join(recordings_dir, session_hint)
    if os.path.isdir(exact):
        return exact
    matches = sorted(glob.glob(os.path.join(recordings_dir, f"*{session_hint}*", "")))
    return matches[-1] if matches else None


def cmd_list(recordings_dir="recordings"):
    """List all recording sessions."""
    sessions = find_sessions(recordings_dir)
    if not sessions:
        print("No recordings found. Run: just serve-proxy")
        return
    print(f"{'Session':<30}  {'Type':<6}  {'FD':>3}  {'MD':>3}  {'Analysis':>8}")
    print("-" * 58)
    for s in sessions:
        kind = s["kind"]
        fd = "yes" if s["has_fd"] else " - "
        md = "yes" if s["has_md"] else " - "
        an = "yes" if s["has_analysis"] else " - "
        print(f"{s['name']:<30}  {kind:<6}  {fd:>3}  {md:>3}  {an:>8}")


def cmd_latest(recordings_dir="recordings"):
    """Print path to most recent session."""
    path = resolve_session(None, recordings_dir)
    if path:
        print(path.rstrip("/"))
    else:
        print("No recordings found.", file=sys.stderr)
        sys.exit(1)


def cmd_show(session=None, recordings_dir="recordings"):
    """Compact summary of a session."""
    path = resolve_session(session, recordings_dir)
    if not path:
        print(f"Session not found: {session}", file=sys.stderr)
        sys.exit(1)
    name = os.path.basename(path.rstrip("/"))
    print(f"Session: {name}")

    # FD frames
    fd_path = os.path.join(path, "capture", "fd-frames.jsonl")
    if os.path.exists(fd_path):
        with open(fd_path) as f:
            fd_count = sum(1 for _ in f)
        print(f"  FD frames: {fd_count}")

    # MD frames
    md_path = os.path.join(path, "md-frames.jsonl")
    if os.path.exists(md_path):
        with open(md_path) as f:
            md_count = sum(1 for _ in f)
        print(f"  MD frames: {md_count}")

    # Events
    ev_path = os.path.join(path, "events.jsonl")
    if os.path.exists(ev_path):
        with open(ev_path) as f:
            ev_count = sum(1 for _ in f)
        print(f"  Events: {ev_count}")

    # Analysis
    analysis_path = os.path.join(path, "analysis.json")
    if os.path.exists(analysis_path):
        try:
            with open(analysis_path) as f:
                analysis = json.load(f)
            violations = analysis.get("violations", [])
            mechanics = analysis.get("mechanics", [])
            print(
                f"  Analysis: yes ({len(violations)} violations, {len(mechanics)} mechanics)"
            )
        except Exception:
            print(f"  Analysis: yes (parse error)")

    # Raw capture
    capture_dir = os.path.join(path, "capture", "frames")
    if os.path.isdir(capture_dir):
        frame_files = os.listdir(capture_dir)
        print(f"  Raw frames: {len(frame_files)} files")


def cmd_find(keyword, recordings_dir="recordings"):
    """Search across all sessions for a keyword in jsonl files."""
    sessions = find_sessions(recordings_dir)
    keyword_lower = keyword.lower()
    hits = 0
    for s in sessions:
        for jsonl_name in [
            "md-frames.jsonl",
            "capture/fd-frames.jsonl",
            "events.jsonl",
        ]:
            fpath = os.path.join(s["path"], jsonl_name)
            if not os.path.exists(fpath):
                continue
            with open(fpath) as f:
                for i, line in enumerate(f, 1):
                    if keyword_lower in line.lower():
                        print(f"{s['name']}/{jsonl_name}:{i}: {line.strip()[:120]}")
                        hits += 1
                        if hits >= 200:
                            print(f"... (capped at 200 hits)", file=sys.stderr)
                            return
