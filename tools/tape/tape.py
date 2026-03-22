#!/usr/bin/env python3
"""tape — recording analysis, proto inspection, conformance."""

import argparse
import os
import subprocess
import sys
import textwrap


# ---------------------------------------------------------------------------
# Path resolution
# ---------------------------------------------------------------------------

TAPE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(os.path.dirname(TAPE_DIR))

CLASSPATH_FILE = os.path.join(PROJECT_DIR, "target", "classpath.txt")
LOGBACK_CLI = os.path.join(PROJECT_DIR, "app", "main", "resources", "logback-cli.xml")

# JVM flags matching justfile's _cli helper
_JVM_BASE = (
    "-Dio.netty.tryReflectionSetAccessible=true "
    "--add-opens java.base/jdk.internal.misc=ALL-UNNAMED "
    "--add-opens java.base/java.nio=ALL-UNNAMED"
)
_JVM_CLI = (
    f"{_JVM_BASE} "
    f"-Dlogback.configurationFile={LOGBACK_CLI} "
    "-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener"
)


def _classpath():
    """Read the Gradle-generated classpath file."""
    if not os.path.exists(CLASSPATH_FILE):
        print(
            f"error: {CLASSPATH_FILE} not found. Run: just build",
            file=sys.stderr,
        )
        sys.exit(1)
    with open(CLASSPATH_FILE) as f:
        base_cp = f.read().strip()
    # Module class dirs prepended so fresh classes take precedence over stale jars.
    # Matches justfile _cp. Fixes: dev-build + CLI tools seeing old jar bytecode.
    modules = ["matchdoor", "tooling", "frontdoor", "account", "app"]
    module_dirs = []
    for mod in modules:
        module_dirs.append(
            os.path.join(PROJECT_DIR, mod, "build", "classes", "kotlin", "main")
        )
        module_dirs.append(
            os.path.join(PROJECT_DIR, mod, "build", "classes", "java", "main")
        )
        module_dirs.append(os.path.join(PROJECT_DIR, mod, "build", "resources", "main"))
    root_dirs = [
        os.path.join(PROJECT_DIR, "build", "classes", "kotlin", "main"),
        os.path.join(PROJECT_DIR, "build", "classes", "java", "main"),
        os.path.join(PROJECT_DIR, "build", "resources", "main"),
    ]
    return ":".join(module_dirs) + ":" + base_cp + ":" + ":".join(root_dirs)


def _java(*args):
    """Run a Kotlin main class via java -cp (read-only, no port kill)."""
    cp = _classpath()
    java_home = os.environ.get("JAVA_HOME", "")
    java_bin = os.path.join(java_home, "bin", "java") if java_home else "java"
    cmd = [java_bin] + _JVM_CLI.split() + ["-cp", cp] + list(args)
    result = subprocess.run(cmd, cwd=PROJECT_DIR)
    sys.exit(result.returncode)


def _resolve_md_payloads(session_hint):
    """Resolve a session hint to the MD payloads directory.

    Checks seat subdirs first (new format), falls back to flat capture/payloads.
    """
    from sessions import resolve_session

    recordings_dir = os.path.join(PROJECT_DIR, "recordings")
    session_path = resolve_session(session_hint, recordings_dir)
    if not session_path:
        print(f"error: no session matching '{session_hint}'", file=sys.stderr)
        sys.exit(1)

    capture = os.path.join(session_path, "capture")

    # New format: seat-N/md-payloads/
    if os.path.isdir(capture):
        for entry in sorted(os.listdir(capture)):
            candidate = os.path.join(capture, entry, "md-payloads")
            if entry.startswith("seat-") and os.path.isdir(candidate):
                bins = [f for f in os.listdir(candidate) if f.endswith(".bin")]
                if bins:
                    return candidate

    # Flat format: capture/payloads/
    flat = os.path.join(capture, "payloads")
    if os.path.isdir(flat):
        bins = [f for f in os.listdir(flat) if f.endswith(".bin") and "MD" in f]
        if bins:
            return flat

    print(f"error: no MD payloads found in session '{session_hint}'", file=sys.stderr)
    sys.exit(1)


def _resolve_session_path(session_hint):
    """Resolve a session hint to its directory path."""
    from sessions import resolve_session

    recordings_dir = os.path.join(PROJECT_DIR, "recordings")
    path = resolve_session(session_hint, recordings_dir)
    if not path:
        print(f"error: no session matching '{session_hint}'", file=sys.stderr)
        sys.exit(1)
    return path


def _card_grpids(name):
    """Look up card grpIds by name (partial LIKE match) from Arena's sqlite DB."""
    import glob as globmod
    import sqlite3

    arena_db_dir = os.path.join(
        os.environ.get("HOME", "/tmp"),
        "Library/Application Support/com.wizards.mtga/Downloads/Raw",
    )
    dbs = globmod.glob(os.path.join(arena_db_dir, "Raw_CardDatabase_*.mtga"))
    if not dbs:
        print(f"error: Arena card DB not found in {arena_db_dir}", file=sys.stderr)
        sys.exit(1)
    db = dbs[0]
    conn = sqlite3.connect(db)
    rows = conn.execute(
        "SELECT c.GrpId, l.Loc FROM Cards c "
        "JOIN Localizations_enUS l ON c.TitleId = l.LocId "
        "WHERE l.Formatted = 1 AND l.Loc LIKE ?",
        (f"%{name}%",),
    ).fetchall()
    conn.close()
    return rows


def _find_card(name, session_hint):
    """Find card instanceIds in a session's md-frames.jsonl."""
    import json

    rows = _card_grpids(name)
    if not rows:
        print(f"No cards matching '{name}' in Arena DB")
        return

    grpids = {r[0] for r in rows}
    card_name = rows[0][1]
    print(f"Card: {card_name}  grpIds: {sorted(grpids)}")

    session_path = _resolve_session_path(session_hint) if session_hint else os.path.join(PROJECT_DIR, "recordings", "latest")
    jsonl = os.path.join(session_path, "md-frames.jsonl")
    if not os.path.isfile(jsonl):
        print(f"error: no md-frames.jsonl in {session_path}", file=sys.stderr)
        sys.exit(1)

    # Zone name lookup
    zone_names = {}
    instances = {}  # instanceId → {grpId, zones: [(gsId, zoneId)]}

    for line in open(jsonl):
        raw = line.strip()
        # Quick pre-filter
        if not any(str(gid) in raw for gid in grpids):
            continue
        obj = json.loads(raw)
        gs_id = obj.get("gsId", 0)

        # Learn zone names
        for z in obj.get("zones", []):
            zid = z.get("zoneId")
            ztype = z.get("type", "")
            if zid and ztype:
                zone_names[zid] = ztype

        for go in obj.get("objects", []):
            if go.get("grpId") in grpids:
                iid = go["instanceId"]
                zid = go.get("zoneId", 0)
                if iid not in instances:
                    instances[iid] = {"grpId": go["grpId"], "zones": []}
                zones = instances[iid]["zones"]
                if not zones or zones[-1][1] != zid:
                    zones.append((gs_id, zid))

    if not instances:
        print(f"Not found in session.")
        return

    print(f"\n{'instanceId':>12}  {'grpId':>6}  zone transitions")
    print("-" * 60)
    for iid in sorted(instances):
        info = instances[iid]
        transitions = " → ".join(
            f"{zone_names.get(z, f'zone{z}')}(gs{gs})"
            for gs, z in info["zones"]
        )
        print(f"{iid:>12}  {info['grpId']:>6}  {transitions}")


def _run_python(script_name, *cli_args):
    """Run a sibling Python script."""
    script = os.path.join(TAPE_DIR, script_name)
    args_list = [a for a in cli_args if a]
    result = subprocess.run([sys.executable, script] + args_list, cwd=PROJECT_DIR)
    if result.returncode:
        sys.exit(result.returncode)


# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------


def dispatch(args):
    """Route to Python or Kotlin implementation."""
    noun = args.noun
    verb = getattr(args, "verb", None)

    # Verb-less nouns (standalone commands)
    verbless = {"gre-types"}
    if verb is None and noun not in verbless:
        print(
            f"tape {noun}: specify a verb. Use 'tape {noun} --help'",
            file=sys.stderr,
        )
        sys.exit(1)

    # --- session ---
    if noun == "session":
        from sessions import (
            cmd_list,
            cmd_show,
            cmd_latest,
            cmd_find,
        )

        if verb == "list":
            return cmd_list()
        if verb == "show":
            return cmd_show(getattr(args, "session", None))
        if verb == "latest":
            return cmd_latest()
        if verb == "find":
            return cmd_find(args.keyword)

        # Kotlin-backed session commands
        if verb == "actions":
            extra = []
            if args.card:
                extra += ["--card", args.card]
            if args.actor:
                extra += ["--actor", args.actor]
            extra += ["--limit", args.limit]
            _java(
                "leyline.debug.RecordingCliKt",
                "actions",
                args.session,
                *extra,
            )
        if verb == "turns":
            extra = []
            if args.start is not None:
                extra += ["--start", str(args.start)]
            if args.finish is not None:
                extra += ["--finish", str(args.finish)]
            _java("leyline.debug.RecordingCliKt", "turninfo", args.session, *extra)
        if verb == "compare":
            _java(
                "leyline.debug.RecordingCliKt",
                "compare",
                args.left,
                args.right,
            )
        if verb == "analyze":
            extra = ["--force"] if args.force else []
            _java(
                "leyline.analysis.AnalysisCliKt",
                "analyze",
                args.session,
                *extra,
            )
        if verb == "violations":
            _java("leyline.analysis.AnalysisCliKt", "violations", args.session)
        if verb == "mechanics":
            _java("leyline.analysis.AnalysisCliKt", "mechanics")
        if verb == "latest-analyzed":
            _java("leyline.analysis.AnalysisCliKt", "latest")

    # --- annotation ---
    if noun == "annotation":
        if verb == "ranges":
            _run_python("annotation_ids.py", "ranges", args.session)
        elif verb == "analyze":
            extra = ["--include-details"] if args.include_details else []
            _run_python("annotation_ids.py", "analyze", args.session, *extra)
        elif verb == "detail":
            extra = ["--include-details"] if args.include_details else []
            _run_python("annotation_ids.py", "detail", args.type, args.session, *extra)
        elif verb == "variance":
            _java("leyline.debug.AnnotationVarianceKt", *args.extra)
        elif verb == "contract":
            _java(
                "leyline.analysis.AnalysisCliKt",
                "annotation-contract",
                args.session,
                args.type,
                args.effect_id,
            )

    # --- gsm ---
    if noun == "gsm":
        from gsm import cmd_filter

        if verb == "filter":
            cmd_filter(args)
        return

    # --- segment ---
    if noun == "segment":
        if verb == "list":
            _run_python("segments.py", "list", args.session or "")
        elif verb == "extract":
            _run_python("segments.py", "extract", args.category, args.session or "")
        elif verb == "template":
            cli_args = ["template", args.category]
            if args.session:
                cli_args.append(args.session)
            if args.index:
                cli_args += ["--index", str(args.index)]
            _run_python("segments.py", *cli_args)
        elif verb == "show":
            cli_args = ["show", args.category]
            if args.session:
                cli_args.append(args.session)
            if args.index:
                cli_args += ["--index", str(args.index)]
            _run_python("segments.py", *cli_args)

    # --- rec (recording helpers) ---
    if noun == "rec":
        if verb == "annotations":
            _run_python("segments.py", "annotations", args.type, args.session or "")
        elif verb == "zones":
            _run_python("segments.py", "zones", args.session or "")

    # --- conform ---
    if noun == "conform":
        if verb == "run":
            extra = []
            if getattr(args, "json", False):
                extra.append("--json")
            if getattr(args, "golden", None):
                extra += ["--golden", args.golden]
            _run_python("segments.py", "diff", args.template, args.engine, *extra)
        elif verb == "index":
            print("tape conform index: not yet implemented", file=sys.stderr)
            sys.exit(1)

    # --- proto ---
    if noun == "proto":
        if verb == "decode":
            if args.path:
                _java("leyline.debug.DecodeCaptureKt", args.path)
            else:
                payloads = os.path.join(
                    PROJECT_DIR,
                    "recordings",
                    "latest",
                    "capture",
                    "payloads",
                )
                _java("leyline.debug.DecodeCaptureKt", payloads)
        elif verb == "trace":
            extra = []
            if args.start is not None:
                extra += ["--start", str(args.start)]
            if args.finish is not None:
                extra += ["--finish", str(args.finish)]
            trace_dir = args.dir or ""
            if not trace_dir and args.session is not None:
                trace_dir = _resolve_md_payloads(args.session)
            _java("leyline.debug.TraceKt", args.id, trace_dir, *extra)
        elif verb == "find-card":
            _find_card(args.name, args.session)
        elif verb == "accumulate":
            extra = [args.output] if args.output else []
            if args.start is not None:
                extra += ["--start", str(args.start)]
            if args.finish is not None:
                extra += ["--finish", str(args.finish)]
            _java(
                "leyline.recording.RecordingDecoderMainKt",
                args.dir,
                "--accumulate",
                *extra,
            )
        elif verb == "priority":
            _java(
                "leyline.recording.RecordingDecoderMainKt",
                args.dir,
                "--priority",
                "--seat",
                "0",
            )
        elif verb == "compare":
            _java("leyline.conformance.CompareMainKt", *args.extra)
        elif verb == "inspect":
            _java("leyline.debug.InspectKt", args.file)
        elif verb == "decode-recording":
            extra = [args.output] if args.output else []
            _java("leyline.recording.RecordingDecoderMainKt", args.dir, *extra)
        elif verb in ("diff-prep", "diff", "extract"):
            print(
                f"tape proto {verb}: use 'just proto-{verb}' (bash script)",
                file=sys.stderr,
            )
            sys.exit(1)

    # --- gre-types ---
    if noun == "gre-types":
        extra = ["--unhandled"] if getattr(args, "unhandled", False) else []
        _java("leyline.analysis.AnalysisCliKt", "gre-types", *extra)

    # --- fd ---
    if noun == "fd":
        if verb == "decode":
            dir_arg = args.dir
            if not dir_arg:
                # Find latest capture dir
                import glob

                caps = sorted(
                    glob.glob(os.path.join(PROJECT_DIR, "recordings", "*", "capture")),
                    reverse=True,
                )
                if not caps:
                    print("No captures found.", file=sys.stderr)
                    sys.exit(1)
                dir_arg = caps[0]
            _java("leyline.debug.DecodeFdCaptureKt", dir_arg)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


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
              gre-types   Cross-session GRE message type coverage
              fd          Front Door raw frame decoding

            examples:
              tape session list           list all recordings
              tape session show           summary of latest session
              tape session show 03-06     summary of session matching "03-06"
              tape session find Deck      search keyword across sessions
              tape proto decode <dir>     decode protobuf payloads
              tape annotation ranges      ID range classification
              tape segment list           list segment categories

            turn range (--start N, --finish N):
              tape session turns <s> --finish 5          turns 1-5
              tape session turns <s> --start 3 --finish 5  turns 3-5
              tape proto accumulate <dir> --finish 3     snapshots through turn 3
              tape proto trace <id> <dir> --start 4      trace from turn 4 on
        """),
    )
    subs = parser.add_subparsers(dest="noun")

    # --- session ---
    s = subs.add_parser("session", help="Recording session inspection")
    ss = s.add_subparsers(dest="verb")

    ss.add_parser("list", help="List all recording sessions")

    p = ss.add_parser("show", help="Compact session summary")
    p.add_argument(
        "session", nargs="?", help="Session name/substring (default: latest)"
    )

    ss.add_parser("latest", help="Print path to most recent session")

    p = ss.add_parser("find", help="Search keyword across sessions")
    p.add_argument("keyword")

    p = ss.add_parser("actions", help="Action timeline (Kotlin)")
    p.add_argument("session")
    p.add_argument("--card", default="")
    p.add_argument("--actor", default="")
    p.add_argument("--limit", default="500")

    p = ss.add_parser("turns", help="Turn/phase/step timeline (--start/--finish)")
    p.add_argument("session")
    p.add_argument("--start", type=int, default=None, help="Only include turns >= N")
    p.add_argument("--finish", type=int, default=None, help="Only include turns <= N")

    p = ss.add_parser("compare", help="Compare two recordings (Kotlin)")
    p.add_argument("left")
    p.add_argument("right")

    p = ss.add_parser("analyze", help="Run SessionAnalyzer (Kotlin)")
    p.add_argument("session", nargs="?", default="")
    p.add_argument("--force", action="store_true")

    p = ss.add_parser("violations", help="Show invariant violations (Kotlin)")
    p.add_argument("session", nargs="?", default="")

    ss.add_parser("mechanics", help="Cross-session mechanic coverage (Kotlin)")

    ss.add_parser("latest-analyzed", help="Summary + analysis of most recent (Kotlin)")

    # --- proto ---
    s = subs.add_parser("proto", help="Protobuf decode, trace, accumulate")
    ss = s.add_subparsers(dest="verb")

    p = ss.add_parser("decode", help="Decode MD payloads")
    p.add_argument("path", nargs="?", help="File or directory")

    p = ss.add_parser("inspect", help="Inspect a .bin template")
    p.add_argument("file")

    p = ss.add_parser("trace", help="Trace an ID across payloads (--start/--finish)")
    p.add_argument("id")
    p.add_argument("dir", nargs="?", default="")
    p.add_argument("-s", "--session", default=None, help="Session name or substring (resolves MD payloads automatically)")
    p.add_argument("--start", type=int, default=None, help="Only include turns >= N")
    p.add_argument("--finish", type=int, default=None, help="Only include turns <= N")

    p = ss.add_parser("find-card", help="Find card instanceIds in a session by name")
    p.add_argument("name", help="Card name (partial match)")
    p.add_argument("-s", "--session", default=None, help="Session name or substring")

    p = ss.add_parser("decode-recording", help="Decode recording to JSONL")
    p.add_argument("dir")
    p.add_argument("output", nargs="?", default="")

    p = ss.add_parser(
        "accumulate", help="Decode + accumulate snapshots (--start/--finish)"
    )
    p.add_argument("dir")
    p.add_argument("output", nargs="?", default="")
    p.add_argument("--start", type=int, default=None, help="Only include turns >= N")
    p.add_argument("--finish", type=int, default=None, help="Only include turns <= N")

    p = ss.add_parser("priority", help="Priority analysis report")
    p.add_argument("dir")

    p = ss.add_parser("compare", help="Structural comparison")
    p.add_argument("extra", nargs="*")

    # --- annotation ---
    s = subs.add_parser("annotation", help="Annotation ID analysis")
    ss = s.add_subparsers(dest="verb")

    p = ss.add_parser("ranges", help="ID range classification")
    p.add_argument("session", nargs="?", default="")

    p = ss.add_parser("analyze", help="Provenance + pattern analysis")
    p.add_argument("session", nargs="?", default="")
    p.add_argument("--include-details", action="store_true")

    p = ss.add_parser("detail", help="Deep dive on one annotation type")
    p.add_argument("type")
    p.add_argument("session", nargs="?", default="")
    p.add_argument("--include-details", action="store_true")

    p = ss.add_parser("variance", help="Cross-session annotation variance (Kotlin)")
    p.add_argument("extra", nargs="*")

    p = ss.add_parser("contract", help="Full annotation contract (Kotlin)")
    p.add_argument("session")
    p.add_argument("type")
    p.add_argument("effect_id", nargs="?", default="")

    # --- gsm ---
    from gsm import build_parser as build_gsm_parser

    build_gsm_parser(subs)

    # --- segment ---
    s = subs.add_parser("segment", help="Conformance segment mining")
    ss = s.add_subparsers(dest="verb")

    p = ss.add_parser("list", help="List segment categories")
    p.add_argument("session", nargs="?")

    p = ss.add_parser("extract", help="Extract segments by category")
    p.add_argument("category")
    p.add_argument("session", nargs="?")

    p = ss.add_parser("template", help="Extract + templatize")
    p.add_argument("category")
    p.add_argument("session", nargs="?")
    p.add_argument("--index", type=int, default=0)

    p = ss.add_parser("show", help="Show prompt lifecycle with full proto fields")
    p.add_argument("category")
    p.add_argument("session", nargs="?")
    p.add_argument("--index", type=int, default=0)

    # --- rec (recording helpers) ---
    s = subs.add_parser("rec", help="Recording research helpers")
    ss = s.add_subparsers(dest="verb")

    p = ss.add_parser("annotations", help="Extract annotations by type from recording")
    p.add_argument(
        "type", help="Annotation type name (e.g. ZoneTransfer, LayeredEffectCreated)"
    )
    p.add_argument("session", nargs="?")

    p = ss.add_parser("zones", help="Zone map from recording (from first full GSM)")
    p.add_argument("session", nargs="?")

    # --- conform ---
    s = subs.add_parser("conform", help="Conformance comparison")
    ss = s.add_subparsers(dest="verb")

    p = ss.add_parser("run", help="Bind + hydrate + diff")
    p.add_argument("template")
    p.add_argument("engine")
    p.add_argument("--json", action="store_true", help="Output structured JSON result")
    p.add_argument("--golden", help="Compare against golden file; exit 2 on regression")

    ss.add_parser("index", help="Overall conformance score")

    # --- gre-types ---
    s = subs.add_parser("gre-types", help="Cross-session GRE message type coverage (Kotlin)")
    s.add_argument("--unhandled", action="store_true", help="Show only unimplemented types")

    # --- fd ---
    s = subs.add_parser("fd", help="FD raw frame decoding (Kotlin)")
    ss = s.add_subparsers(dest="verb")

    p = ss.add_parser("decode", help="Re-decode FD raw frames")
    p.add_argument("dir", nargs="?", default="")

    # --- Parse and dispatch ---
    args = parser.parse_args()
    if args.noun is None:
        parser.print_help()
        sys.exit(0)

    dispatch(args)


if __name__ == "__main__":
    main()
