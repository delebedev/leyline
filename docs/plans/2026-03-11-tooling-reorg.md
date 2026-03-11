# Tooling Reorg Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize leyline's developer tooling into `tools/{wire,tape,arena,scry}` with gh-style CLI ergonomics, co-located docs, and thin `bin/` wrappers.

**Architecture:** Four self-contained tool directories under `tools/`. `wire` and `tape` are leyline-specific (recordings, protos). `arena` and `scry` are standalone black-box tools. Each tool owns its source, tests, and docs. `bin/` keeps thin entry-point wrappers. `just` recipes become thin delegations.

**Tech Stack:** Python 3 (all CLIs), existing Kotlin tooling module (tape's backend via `java -cp`), Swift (arena native binaries).

**Spec:** `docs/specs/2026-03-11-tooling-vision-design.md`

---

## Chunk 1: wire (smallest, already 90% done)

### Task 1: Create tools/wire/ directory and move fd-inspect.py

**Files:**
- Create: `tools/wire/wire.py`
- Create: `tools/wire/__init__.py`
- Modify: `bin/wire` (new thin wrapper)

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p tools/wire
```

- [ ] **Step 2: Copy fd-inspect.py to tools/wire/wire.py with proper CLI**

Copy `tooling/scripts/fd-inspect.py` → `tools/wire/wire.py`. Replace the `if __name__` dispatch block with argparse-based CLI that supports `wire <verb>` and `wire --help`. Keep all existing functions unchanged.

The new `main()` should:
- Use `argparse` with subparsers for each command: `tail`, `search`, `show`, `raw`, `summary`, `pairs`, `response`, `response-all`, `coverage`, `keys`, `since`, `cards`, `flow`
- Add `--session` optional arg to commands that use `find_jsonl()` (currently they always use latest)
- Add `--json` flag stub (for future, no-op for now)
- Print help with examples when no subcommand given

```python
def main():
    parser = argparse.ArgumentParser(
        prog="wire",
        description="Front Door frame inspection tool.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""\
            examples:
              wire tail              # last 20 FD frames
              wire tail 50           # last 50
              wire search Deck       # search payloads for "Deck"
              wire show 42           # full payload for seq 42
              wire summary           # cmdType breakdown
              wire flow              # chronological request→response flow
              wire coverage          # handled vs observed CmdTypes
        """),
    )
    subs = parser.add_subparsers(dest="command")

    # tail
    p = subs.add_parser("tail", help="Last N frames (default 20)")
    p.add_argument("n", nargs="?", type=int, default=20)
    p.add_argument("--session", "-s", help="Session name/substring")

    # search
    p = subs.add_parser("search", help="Search payloads + cmdTypes")
    p.add_argument("term")
    p.add_argument("--session", "-s")

    # show
    p = subs.add_parser("show", help="Full payload for a seq number")
    p.add_argument("seq", type=int)
    p.add_argument("--session", "-s")

    # ... (all 13 subcommands)

    args = parser.parse_args()
    if args.command is None:
        parser.print_help()
        sys.exit(0)
    # dispatch
```

Key changes from fd-inspect.py:
- `find_jsonl()` accepts optional `session` kwarg (already does — wire through from args)
- Each `cmd_*` function signature unchanged (they already work)
- Add `import argparse, textwrap` at top

- [ ] **Step 3: Create empty `__init__.py`**

```bash
touch tools/wire/__init__.py
```

- [ ] **Step 4: Create bin/wire wrapper**

```python
#!/usr/bin/env python3
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tools', 'wire'))
from wire import main
main()
```

Make executable: `chmod +x bin/wire`

- [ ] **Step 5: Verify wire works**

```bash
bin/wire --help
bin/wire tail 5       # should show last 5 FD frames (or error if no recordings)
bin/wire summary      # should show cmdType breakdown
```

Expected: help text with all subcommands listed, or graceful "no recordings" error.

- [ ] **Step 6: Update just/fd.just to delegate to wire**

Change `_fd` variable:
```just
_fd := "python3 tools/wire/wire.py"
```

All recipes stay identical — they just point to the new location.

- [ ] **Step 7: Verify just recipes still work**

```bash
just fd-tail 5
just fd-summary
```

- [ ] **Step 8: Commit**

```bash
git add tools/wire/ bin/wire just/fd.just
git commit -m "refactor(tooling): create wire CLI from fd-inspect.py

Move FD frame inspection to tools/wire/wire.py with argparse CLI.
bin/wire is the new entry point. just fd-* recipes delegate to it.
tooling/scripts/fd-inspect.py kept for now (remove in cleanup task)."
```

### Task 2: Move wire docs

**Files:**
- Move: `docs/recording-cli.md` → (stays, wire-specific parts extracted later)
- No wire-specific docs exist yet — wire's CLI help IS the docs for now

This is a no-op for now. Wire's `--help` with examples is sufficient. Doc migration happens in Task 8.

---

## Chunk 2: tape skeleton (pure Python, reads jsonl)

### Task 3: Create tools/tape/ with session subcommands

**Files:**
- Create: `tools/tape/tape.py` — entry point with argparse noun-verb structure
- Create: `tools/tape/sessions.py` — pure Python session commands (reads jsonl directly)
- Create: `tools/tape/__init__.py`
- Create: `bin/tape` — thin wrapper

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p tools/tape
touch tools/tape/__init__.py
```

- [ ] **Step 2: Write `tools/tape/sessions.py` — pure Python session reader**

This module handles commands that read jsonl directly without needing JVM:
- `session list` — scan `recordings/*/` dirs, show timestamps + file sizes
- `session latest` — print path to most recent recording
- `session show` — compact summary (frame counts, duration, card count from objects)
- `session find` — grep across session jsonl files for a keyword

```python
"""Pure Python session inspection — reads jsonl directly, no JVM needed."""
import json
import os
import glob
from datetime import datetime


def find_sessions(recordings_dir="recordings"):
    """List all recording session directories, newest first."""
    dirs = sorted(glob.glob(os.path.join(recordings_dir, "*/")), reverse=True)
    sessions = []
    for d in dirs:
        name = os.path.basename(d.rstrip("/"))
        if name == "latest":
            continue
        has_fd = os.path.exists(os.path.join(d, "capture", "fd-frames.jsonl"))
        has_md = os.path.exists(os.path.join(d, "md-frames.jsonl"))
        sessions.append({"name": name, "path": d, "has_fd": has_fd, "has_md": has_md})
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
    matches = sorted(glob.glob(os.path.join(recordings_dir, f"*{session_hint}*/")))
    return matches[-1] if matches else None


def cmd_list(recordings_dir="recordings"):
    """List all recording sessions."""
    sessions = find_sessions(recordings_dir)
    if not sessions:
        print("No recordings found. Run: just serve-proxy")
        return
    print(f"{'Session':<30}  {'FD':>3}  {'MD':>3}")
    print("-" * 40)
    for s in sessions:
        fd = "yes" if s["has_fd"] else " - "
        md = "yes" if s["has_md"] else " - "
        print(f"{s['name']:<30}  {fd:>3}  {md:>3}")


def cmd_latest(recordings_dir="recordings"):
    """Print path to most recent session."""
    path = resolve_session(None, recordings_dir)
    if path:
        print(path.rstrip("/"))
    else:
        print("No recordings found.", file=sys.stderr)


def cmd_show(session=None, recordings_dir="recordings"):
    """Compact summary of a session."""
    path = resolve_session(session, recordings_dir)
    if not path:
        print(f"Session not found: {session}")
        return
    name = os.path.basename(path.rstrip("/"))
    print(f"Session: {name}")
    # Count FD frames
    fd_path = os.path.join(path, "capture", "fd-frames.jsonl")
    if os.path.exists(fd_path):
        with open(fd_path) as f:
            fd_count = sum(1 for _ in f)
        print(f"  FD frames: {fd_count}")
    # Count MD frames
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
    analysis = os.path.join(path, "analysis.json")
    if os.path.exists(analysis):
        print(f"  Analysis: yes")


def cmd_find(keyword, recordings_dir="recordings"):
    """Search across all sessions for a keyword in jsonl files."""
    sessions = find_sessions(recordings_dir)
    keyword_lower = keyword.lower()
    for s in sessions:
        for jsonl_name in ["md-frames.jsonl", "capture/fd-frames.jsonl", "events.jsonl"]:
            fpath = os.path.join(s["path"], jsonl_name)
            if not os.path.exists(fpath):
                continue
            with open(fpath) as f:
                for i, line in enumerate(f, 1):
                    if keyword_lower in line.lower():
                        print(f"{s['name']}/{jsonl_name}:{i}: {line.strip()[:120]}")
```

- [ ] **Step 3: Write `tools/tape/tape.py` — main entry point**

The main entry point uses argparse with noun-verb subcommands. Pure Python commands dispatch directly. Kotlin-backed commands shell out to `java -cp`.

```python
#!/usr/bin/env python3
"""tape — recording analysis, proto inspection, conformance."""
import argparse
import os
import subprocess
import sys
import textwrap

# Resolve project dir (tape lives in tools/tape/)
TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(os.path.dirname(TOOLS_DIR))


def _classpath():
    """Read the Gradle-generated classpath file."""
    cp_file = os.path.join(PROJECT_DIR, "build", "classpath.txt")
    if not os.path.exists(cp_file):
        print("error: build/classpath.txt not found. Run: just build", file=sys.stderr)
        sys.exit(1)
    with open(cp_file) as f:
        return f.read().strip()


def _java(*args):
    """Run a Kotlin main class via java -cp."""
    cp = _classpath()
    cmd = ["java", "-cp", cp] + list(args)
    result = subprocess.run(cmd, cwd=PROJECT_DIR)
    sys.exit(result.returncode)


def main():
    parser = argparse.ArgumentParser(
        prog="tape",
        description="Recording analysis, proto inspection, conformance.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""\
            noun groups:
              session     Recording session inspection
              proto       Protobuf decode, trace, accumulate
              annotation  Annotation ID analysis and variance
              segment     Conformance segment mining
              conform     Closed-loop conformance comparison
              fd          Front Door raw frame decoding

            examples:
              tape session list           # list all recordings
              tape session show           # summary of latest session
              tape proto decode <dir>     # decode protobuf payloads
              tape annotation ranges      # ID range classification
        """),
    )
    subs = parser.add_subparsers(dest="noun")

    # --- session ---
    session_p = subs.add_parser("session", help="Recording session inspection")
    session_subs = session_p.add_subparsers(dest="verb")

    session_subs.add_parser("list", help="List all recording sessions")

    p = session_subs.add_parser("show", help="Compact session summary")
    p.add_argument("session", nargs="?", help="Session name/substring (default: latest)")

    p = session_subs.add_parser("latest", help="Print path to most recent session")

    p = session_subs.add_parser("find", help="Search keyword across sessions")
    p.add_argument("keyword")

    # Kotlin-backed session commands
    p = session_subs.add_parser("actions", help="Action timeline")
    p.add_argument("session")
    p.add_argument("--card", default="")
    p.add_argument("--actor", default="")
    p.add_argument("--limit", default="500")

    p = session_subs.add_parser("turns", help="Turn/phase/step timeline")
    p.add_argument("session")

    p = session_subs.add_parser("cards", help="Card names in session")
    p.add_argument("session")

    p = session_subs.add_parser("compare", help="Compare two recordings")
    p.add_argument("left")
    p.add_argument("right")

    p = session_subs.add_parser("analyze", help="Run SessionAnalyzer")
    p.add_argument("session", nargs="?", default="")
    p.add_argument("--force", action="store_true")

    p = session_subs.add_parser("violations", help="Show invariant violations")
    p.add_argument("session", nargs="?", default="")

    p = session_subs.add_parser("mechanics", help="Cross-session mechanic coverage")

    # --- proto ---
    proto_p = subs.add_parser("proto", help="Protobuf decode, trace, accumulate")
    proto_subs = proto_p.add_subparsers(dest="verb")

    p = proto_subs.add_parser("decode", help="Decode protobuf payloads")
    p.add_argument("path", nargs="?", help="File or directory")

    p = proto_subs.add_parser("trace", help="Trace an ID across payloads")
    p.add_argument("id")
    p.add_argument("dir", nargs="?", default="")

    p = proto_subs.add_parser("accumulate", help="Decode + accumulate state snapshots")
    p.add_argument("dir")
    p.add_argument("output", nargs="?", default="")

    p = proto_subs.add_parser("priority", help="Priority analysis report")
    p.add_argument("dir")

    proto_subs.add_parser("diff-prep", help="Dump payloads as text for diffing")
    proto_subs.add_parser("diff", help="Diff stub vs real captures")

    p = proto_subs.add_parser("compare", help="Structural comparison")
    p.add_argument("args", nargs="*")

    p = proto_subs.add_parser("extract", help="Save last S→C payload as template")
    p.add_argument("--name", default="extracted")

    # --- annotation ---
    ann_p = subs.add_parser("annotation", help="Annotation ID analysis")
    ann_subs = ann_p.add_subparsers(dest="verb")

    p = ann_subs.add_parser("variance", help="Cross-session annotation variance")
    p.add_argument("args", nargs="*")

    p = ann_subs.add_parser("contract", help="Full annotation contract")
    p.add_argument("type")
    p.add_argument("session", nargs="?", default="")
    p.add_argument("effect_id", nargs="?", default="")

    p = ann_subs.add_parser("ranges", help="ID range classification")
    p.add_argument("session", nargs="?", default="")

    p = ann_subs.add_parser("analyze", help="Provenance + pattern analysis")
    p.add_argument("session", nargs="?", default="")
    p.add_argument("--include-details", action="store_true")

    p = ann_subs.add_parser("detail", help="Deep dive on one annotation type")
    p.add_argument("type")
    p.add_argument("session", nargs="?", default="")
    p.add_argument("--include-details", action="store_true")

    # --- segment ---
    seg_p = subs.add_parser("segment", help="Conformance segment mining")
    seg_subs = seg_p.add_subparsers(dest="verb")

    p = seg_subs.add_parser("list", help="List segment categories")
    p.add_argument("session", nargs="?")

    p = seg_subs.add_parser("extract", help="Extract segments by category")
    p.add_argument("category")
    p.add_argument("session", nargs="?")

    p = seg_subs.add_parser("template", help="Extract + templatize")
    p.add_argument("category")
    p.add_argument("session", nargs="?")
    p.add_argument("--index", type=int, default=0)

    # --- conform ---
    conf_p = subs.add_parser("conform", help="Conformance comparison")
    conf_subs = conf_p.add_subparsers(dest="verb")

    p = conf_subs.add_parser("run", help="Bind + hydrate + diff")
    p.add_argument("template")
    p.add_argument("engine_output")

    conf_subs.add_parser("index", help="Overall conformance score")

    # --- fd ---
    fd_p = subs.add_parser("fd", help="FD raw frame decoding")
    fd_subs = fd_p.add_subparsers(dest="verb")

    p = fd_subs.add_parser("decode", help="Re-decode FD raw frames")
    p.add_argument("dir", nargs="?", default="")

    # --- Parse and dispatch ---
    args = parser.parse_args()

    if args.noun is None:
        parser.print_help()
        sys.exit(0)

    dispatch(args)


def dispatch(args):
    """Route to Python or Kotlin implementation."""
    from tape import sessions  # local import avoids circular

    noun = args.noun
    verb = args.verb

    if verb is None:
        # Print help for the noun group
        print(f"tape {noun}: specify a verb. Use 'tape {noun} --help'", file=sys.stderr)
        sys.exit(1)

    # --- Pure Python commands ---
    if noun == "session":
        if verb == "list":
            return sessions.cmd_list()
        if verb == "show":
            return sessions.cmd_show(getattr(args, "session", None))
        if verb == "latest":
            return sessions.cmd_latest()
        if verb == "find":
            return sessions.cmd_find(args.keyword)

        # Kotlin-backed session commands
        if verb == "actions":
            extra = []
            if args.card:
                extra += ["--card", args.card]
            if args.actor:
                extra += ["--actor", args.actor]
            extra += ["--limit", args.limit]
            _java("leyline.debug.RecordingCliKt", "actions", args.session, *extra)
        if verb == "turns":
            _java("leyline.debug.RecordingCliKt", "turninfo", args.session)
        if verb == "cards":
            # This is a bash recipe — shell out to just
            subprocess.run(["just", "cards-in-session", args.session], cwd=PROJECT_DIR)
            return
        if verb == "compare":
            _java("leyline.debug.RecordingCliKt", "compare", args.left, args.right)
        if verb == "analyze":
            extra = ["--force"] if args.force else []
            _java("leyline.analysis.AnalysisCliKt", "analyze", args.session, *extra)
        if verb == "violations":
            _java("leyline.analysis.AnalysisCliKt", "violations", args.session)
        if verb == "mechanics":
            _java("leyline.analysis.AnalysisCliKt", "mechanics")

    # --- annotation: Python for ranges/analyze/detail, Kotlin for variance/contract ---
    if noun == "annotation":
        if verb == "ranges":
            _run_annotation_ids("ranges", args.session)
        if verb == "analyze":
            extra = ["--include-details"] if args.include_details else []
            _run_annotation_ids("analyze", args.session, *extra)
        if verb == "detail":
            extra = ["--include-details"] if args.include_details else []
            _run_annotation_ids("detail", args.type, args.session, *extra)
        if verb == "variance":
            _java("leyline.debug.AnnotationVarianceKt", *args.args)
        if verb == "contract":
            _java("leyline.analysis.AnalysisCliKt", "annotation-contract",
                   args.session, args.type, args.effect_id)

    # --- segment: pure Python ---
    if noun == "segment":
        _run_segments(verb, args)

    # --- conform: stub for now ---
    if noun == "conform":
        if verb == "run":
            _run_segments("diff", [args.template, args.engine_output])
        if verb == "index":
            print("tape conform index: not yet implemented")
            sys.exit(1)

    # --- proto: all Kotlin ---
    if noun == "proto":
        _dispatch_proto(verb, args)

    # --- fd: Kotlin ---
    if noun == "fd":
        if verb == "decode":
            _java("leyline.debug.DecodeFdCaptureKt", args.dir)


def _run_annotation_ids(*cli_args):
    """Shell out to annotation-ids.py (later: inline the code)."""
    script = os.path.join(TOOLS_DIR, "annotation_ids.py")
    args = [a for a in cli_args if a]
    subprocess.run([sys.executable, script] + args, cwd=PROJECT_DIR)


def _run_segments(verb, args):
    """Shell out to md-segments.py (later: inline the code)."""
    script = os.path.join(TOOLS_DIR, "segments.py")
    if verb == "list":
        cli_args = ["list"]
        if args.session:
            cli_args.append(args.session)
    elif verb == "extract":
        cli_args = ["extract", args.category]
        if args.session:
            cli_args.append(args.session)
    elif verb == "template":
        cli_args = ["template", args.category]
        if args.session:
            cli_args.append(args.session)
        if args.index:
            cli_args += ["--index", str(args.index)]
    elif verb == "diff":
        # args is a list [template, engine_output] from conform run
        cli_args = ["diff"] + list(args)
    else:
        print(f"Unknown segment verb: {verb}", file=sys.stderr)
        sys.exit(1)
    subprocess.run([sys.executable, script] + cli_args, cwd=PROJECT_DIR)


def _dispatch_proto(verb, args):
    """Dispatch proto subcommands to Kotlin."""
    if verb == "decode":
        path = args.path or ""
        if path:
            _java("leyline.debug.DecodeCaptureKt", path)
        else:
            _java("leyline.debug.DecodeCaptureKt")
    elif verb == "trace":
        _java("leyline.debug.TraceKt", args.id, args.dir)
    elif verb == "accumulate":
        extra = [args.output] if args.output else []
        _java("leyline.recording.RecordingDecoderMainKt", args.dir, "--accumulate", *extra)
    elif verb == "priority":
        _java("leyline.recording.RecordingDecoderMainKt", args.dir, "--priority", "--seat", "0")
    elif verb == "compare":
        _java("leyline.conformance.CompareMainKt", *args.args)
    elif verb == "extract":
        print("tape proto extract: use 'just proto-extract' for now")
        sys.exit(1)
    elif verb in ("diff-prep", "diff"):
        print(f"tape proto {verb}: use 'just proto-{verb}' for now (bash script)")
        sys.exit(1)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Create bin/tape wrapper**

```python
#!/usr/bin/env python3
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tools', 'tape'))
from tape import main
main()
```

`chmod +x bin/tape`

- [ ] **Step 5: Verify tape session commands work**

```bash
bin/tape --help
bin/tape session list
bin/tape session show
bin/tape session latest
```

- [ ] **Step 6: Commit**

```bash
git add tools/tape/ bin/tape
git commit -m "feat(tooling): create tape CLI skeleton with session subcommands

tape.py is the entry point with noun-verb argparse structure.
sessions.py handles pure Python commands (list, show, latest, find).
Kotlin-backed commands shell out to java -cp."
```

### Task 4: Wire tape's Kotlin-backed commands

**Files:**
- Modify: `tools/tape/tape.py` (already has dispatch stubs)

The Kotlin dispatch is already wired in Task 3's `tape.py`. This task verifies it works.

- [ ] **Step 1: Verify Kotlin-backed commands**

```bash
bin/tape session actions <session>           # needs a real session name
bin/tape proto decode <dir>
bin/tape annotation variance
```

If `build/classpath.txt` exists, these should work. If not, `just build` first.

- [ ] **Step 2: Fix any dispatch issues found in step 1**

Adjust argument passing in `_dispatch_proto`, `dispatch` as needed based on test results.

- [ ] **Step 3: Commit fixes if any**

```bash
git add tools/tape/tape.py
git commit -m "fix(tooling): fix tape Kotlin dispatch argument passing"
```

### Task 5: Move annotation-ids.py and md-segments.py into tape

**Files:**
- Copy: `tooling/scripts/annotation-ids.py` → `tools/tape/annotation_ids.py`
- Copy: `tooling/scripts/md-segments.py` → `tools/tape/segments.py`

- [ ] **Step 1: Copy scripts**

```bash
cp tooling/scripts/annotation-ids.py tools/tape/annotation_ids.py
cp tooling/scripts/md-segments.py tools/tape/segments.py
```

- [ ] **Step 2: Verify tape dispatches to local copies**

```bash
bin/tape annotation ranges
bin/tape segment list
```

- [ ] **Step 3: Update just/proto.just to point to new locations**

Change `_ann_ids`:
```just
_ann_ids := "python3 tools/tape/annotation_ids.py"
```

- [ ] **Step 4: Verify just recipes**

```bash
just ann-id-ranges
```

- [ ] **Step 5: Commit**

```bash
git add tools/tape/annotation_ids.py tools/tape/segments.py just/proto.just
git commit -m "refactor(tooling): move annotation-ids.py and md-segments.py into tools/tape/

annotation_ids.py and segments.py now live under tools/tape/.
just recipes updated to point to new locations.
Original scripts in tooling/scripts/ kept for now."
```

### Task 6: Update just/recording.just to delegate to tape

**Files:**
- Modify: `just/recording.just`

- [ ] **Step 1: Add tape wrappers for Kotlin-backed recording commands**

For commands that tape already handles via Kotlin dispatch, add thin just wrappers that delegate to `bin/tape`:

```just
# (keep existing recipes unchanged for now — they work fine)
# Future: replace with tape delegations as tape stabilizes
```

Actually, the just recipes already work via direct `java -cp`. tape's Kotlin dispatch does the same thing. No change needed now — just recipes and tape coexist. The recipes will be deprecated over time as users adopt `tape`.

- [ ] **Step 2: Commit (no-op, skip if nothing changed)**

---

## Chunk 3: arena and scry reorg (file moves)

### Task 7: Create tools/arena/ and move files

**Files:**
- Create: `tools/arena/arena.py` (move from `bin/arena.py`)
- Create: `tools/arena/screens.py` (move from `bin/arena_screens.py`)
- Create: `tools/arena/record.py` (move from `bin/arena-record`)
- Create: `tools/arena/annotate.py` (move from `bin/arena-annotate`)
- Create: `tools/arena/native/` (move compiled binaries)
- Create: `tools/arena/swift/` (move Swift sources)
- Create: `tools/arena/models/` (move ML models)
- Modify: `bin/arena` (update to thin wrapper)

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p tools/arena/native tools/arena/swift tools/arena/models tools/arena/tests
```

- [ ] **Step 2: Move arena.py**

```bash
cp bin/arena.py tools/arena/arena.py
```

- [ ] **Step 3: Update tools/arena/arena.py path resolution**

The current `arena.py` resolves native binaries relative to `__file__`:
```python
BIN_DIR = str(Path(__file__).resolve().parent)
```

Change to resolve against `native/` subdir:
```python
ARENA_DIR = str(Path(__file__).resolve().parent)
NATIVE_DIR = os.path.join(ARENA_DIR, "native")
```

Update all references to `BIN_DIR` for native binaries (click, ocr, window-bounds) to use `NATIVE_DIR`. Keep `PROJECT_DIR` resolution unchanged (walks up to find justfile).

Key replacements in arena.py:
- `os.path.join(BIN_DIR, "click")` → `os.path.join(NATIVE_DIR, "click")`
- `os.path.join(BIN_DIR, "ocr")` → `os.path.join(NATIVE_DIR, "ocr")`
- `os.path.join(BIN_DIR, "window-bounds")` → `os.path.join(NATIVE_DIR, "window-bounds")`
- For `detect.swift` compilation: `os.path.join(BIN_DIR, "detect.swift")` → `os.path.join(ARENA_DIR, "swift", "detect.swift")`
- ML model: `os.path.join(BIN_DIR, "models", ...)` → `os.path.join(ARENA_DIR, "models", ...)`

Also update the `arena_screens` import:
```python
# Was: from arena_screens import ...
# Now: from screens import ...
```

- [ ] **Step 4: Move arena_screens.py**

```bash
cp bin/arena_screens.py tools/arena/screens.py
```

- [ ] **Step 5: Move support scripts**

```bash
cp bin/arena-record tools/arena/record.py
cp bin/arena-annotate tools/arena/annotate.py
```

- [ ] **Step 6: Move native binaries and Swift sources**

```bash
cp bin/click tools/arena/native/click
cp bin/ocr tools/arena/native/ocr
cp bin/window-bounds tools/arena/native/window-bounds
cp bin/click.swift tools/arena/swift/click.swift
cp bin/ocr.swift tools/arena/swift/ocr.swift
cp bin/detect.swift tools/arena/swift/detect.swift
cp bin/window-bounds.swift tools/arena/swift/window-bounds.swift
```

- [ ] **Step 7: Move ML model (if not gitignored)**

```bash
cp -r bin/models/card_detector.mlmodel tools/arena/models/ 2>/dev/null || true
```

- [ ] **Step 8: Move test file**

```bash
cp bin/test_arena_scry.py tools/arena/tests/test_arena_scry.py
```

- [ ] **Step 9: Update bin/arena wrapper**

```bash
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")/../tools/arena" && pwd)"
exec python3 "$DIR/arena.py" "$@"
```

- [ ] **Step 10: Verify arena works**

```bash
bin/arena --help
bin/arena ocr 2>/dev/null || echo "ok (no window)"
```

- [ ] **Step 11: Commit**

```bash
git add tools/arena/ bin/arena
git commit -m "refactor(tooling): move arena CLI to tools/arena/

arena.py, screens.py, record.py, annotate.py under tools/arena/.
Native binaries in native/, Swift source in swift/, ML models in models/.
bin/arena is now a thin wrapper."
```

### Task 8: Create tools/scry/ and move files

**Files:**
- Create: `tools/scry/cli.py` (move from `bin/scry_lib/cli.py`)
- Create: `tools/scry/lib/` (move from `bin/scry_lib/`)
- Modify: `bin/scry` (update wrapper)

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p tools/scry/lib tools/scry/tests
```

- [ ] **Step 2: Move scry_lib contents**

```bash
cp bin/scry_lib/*.py tools/scry/lib/
```

- [ ] **Step 3: Create tools/scry/cli.py entry point**

```python
#!/usr/bin/env python3
"""scry — Game state from Player.log."""
import sys
import os

# Add lib/ to path for internal imports
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from cli import main

if __name__ == "__main__":
    main()
```

Wait — scry_lib modules import each other as `from scry_lib.X import Y`. We need to preserve this import structure. Better approach: keep the `scry_lib` package name.

**Revised approach:** Create `tools/scry/scry_lib/` as the package, with `tools/scry/cli.py` as the entry point.

```bash
mkdir -p tools/scry/scry_lib tools/scry/tests
cp bin/scry_lib/*.py tools/scry/scry_lib/
touch tools/scry/scry_lib/__init__.py
```

- [ ] **Step 4: Create tools/scry/cli.py**

```python
#!/usr/bin/env python3
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scry_lib.cli import main
main()
```

- [ ] **Step 5: Update bin/scry wrapper**

```python
#!/usr/bin/env python3
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tools', 'scry'))
from scry_lib.cli import main
main()
```

- [ ] **Step 6: Verify scry works**

```bash
bin/scry --help
bin/scry state --no-cards 2>/dev/null || echo "ok (no log)"
```

- [ ] **Step 7: Commit**

```bash
git add tools/scry/ bin/scry
git commit -m "refactor(tooling): move scry to tools/scry/

scry_lib/ package preserved under tools/scry/.
bin/scry updated as thin wrapper."
```

---

## Chunk 4: Doc migration and cleanup

### Task 9: Move docs to tool directories

**Files:**
- Move: `docs/arena-cli.md` → `tools/arena/docs/cli.md`
- Move: `docs/arena-nav.md` → `tools/arena/docs/nav.md`
- Move: `docs/autoplay/zone-aware-ocr.md` → `tools/arena/docs/zone-aware-ocr.md`
- Move: `docs/autoplay/forge-mode-notes.md` → `tools/arena/docs/forge-mode-notes.md`
- Move: `docs/recording-cli.md` → `tools/tape/docs/cli.md`
- Move: `docs/conformance-pipeline.md` → `tools/tape/docs/conformance.md`
- Move: `docs/conformance-levers.md` → `tools/tape/docs/levers.md`

- [ ] **Step 1: Create doc directories**

```bash
mkdir -p tools/arena/docs tools/tape/docs tools/wire/docs tools/scry/docs
```

- [ ] **Step 2: Move arena docs**

```bash
git mv docs/arena-cli.md tools/arena/docs/cli.md
git mv docs/arena-nav.md tools/arena/docs/nav.md
git mv docs/autoplay/zone-aware-ocr.md tools/arena/docs/zone-aware-ocr.md
git mv docs/autoplay/forge-mode-notes.md tools/arena/docs/forge-mode-notes.md
```

- [ ] **Step 3: Move tape docs**

```bash
git mv docs/recording-cli.md tools/tape/docs/cli.md
git mv docs/conformance-pipeline.md tools/tape/docs/conformance.md
git mv docs/conformance-levers.md tools/tape/docs/levers.md
```

- [ ] **Step 4: Clean up empty docs/autoplay/ if empty**

```bash
rmdir docs/autoplay 2>/dev/null || true
```

- [ ] **Step 5: Update docs/index.md**

Add a "Developer Tools" section near the top:

```markdown
## Developer Tools

| Tool | Entry point | Purpose |
|------|-------------|---------|
| `tape` | `bin/tape` or `tape <noun> <verb>` | Recording analysis, proto inspection, conformance |
| `wire` | `bin/wire` or `wire <verb>` | Front Door frame inspection |
| `arena` | `bin/arena` or `arena <cmd>` | MTGA UI automation |
| `scry` | `bin/scry` or `scry <cmd>` | Game state from Player.log |

Each tool has docs in `tools/<name>/docs/`.
```

- [ ] **Step 6: Commit**

```bash
git add tools/*/docs/ docs/index.md
git commit -m "docs: migrate tool-specific docs to tools/*/docs/

arena-cli.md, arena-nav.md → tools/arena/docs/
recording-cli.md, conformance-*.md → tools/tape/docs/
Updated docs/index.md with tool entry points."
```

### Task 10: Update CLAUDE.md quick reference

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add tape and wire to Quick Reference section**

In the "Quick Reference" section of CLAUDE.md, add:

```markdown
- **Recording tools:** `tape session list`, `tape session show`, `tape proto decode`, `tape annotation ranges`. Full reference: `tools/tape/docs/cli.md`.
- **FD inspection:** `wire tail`, `wire search`, `wire show`, `wire flow`. Full reference: `tools/wire/docs/cli.md`.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add tape and wire to CLAUDE.md quick reference"
```

### Task 11: Clean up original script locations

**Files:**
- Remove old copies (keep originals as symlinks or just remove)

- [ ] **Step 1: Verify all tools work from new locations**

```bash
bin/wire --help
bin/tape --help
bin/tape session list
bin/arena --help
bin/scry --help
```

- [ ] **Step 2: Remove old arena files from bin/**

Once arena works from `tools/arena/`, trash the old copies in `bin/`:

```bash
trash bin/arena.py bin/arena_screens.py bin/arena-record bin/arena-annotate
trash bin/click bin/ocr bin/window-bounds
trash bin/click.swift bin/ocr.swift bin/detect.swift bin/window-bounds.swift
trash bin/test_arena_scry.py
trash -r bin/scry_lib
trash -r bin/models
```

Keep `bin/arena`, `bin/scry`, `bin/tape`, `bin/wire` (the thin wrappers).

- [ ] **Step 3: Verify everything still works after cleanup**

```bash
bin/wire tail 5
bin/tape session list
bin/arena --help
bin/scry --help
just fd-tail 5
just ann-id-ranges
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(tooling): remove old bin/ copies after tools/ migration

All tool source now lives under tools/. bin/ contains only thin wrappers.
Old scripts, binaries, and scry_lib/ removed from bin/."
```

- [ ] **Step 5: Trash old tooling/scripts/ copies**

```bash
trash tooling/scripts/fd-inspect.py tooling/scripts/md-segments.py tooling/scripts/annotation-ids.py
```

Only if tape/wire dispatch to local copies works.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(tooling): remove old tooling/scripts/ after tools/ migration"
```

---

## Summary

| Task | What | Commit |
|------|------|--------|
| 1 | wire CLI from fd-inspect.py | `refactor(tooling): create wire CLI` |
| 3 | tape skeleton + session commands | `feat(tooling): create tape CLI skeleton` |
| 4 | Verify tape Kotlin dispatch | fix commit if needed |
| 5 | Move annotation-ids + md-segments into tape | `refactor(tooling): move scripts into tape` |
| 7 | Move arena to tools/arena/ | `refactor(tooling): move arena CLI` |
| 8 | Move scry to tools/scry/ | `refactor(tooling): move scry` |
| 9 | Migrate docs | `docs: migrate tool-specific docs` |
| 10 | Update CLAUDE.md | `docs: add tape/wire to quick reference` |
| 11 | Clean up old locations | `refactor(tooling): remove old copies` |
