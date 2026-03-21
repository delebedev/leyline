"""GSM-level query tool — filter, project, aggregate across recordings.

Pure Python, reads md-frames.jsonl directly. No JVM needed.

Examples:
    tape gsm filter --has DamageDealt --has PhaseOrStepModified
    tape gsm filter --has DamageDealt --show annotations --sample 3
    tape gsm filter --has Scry --not-has ResolutionComplete --show types
    tape gsm filter --has DamageDealt --group-by types
"""

import argparse
import json
import os
import sys
from collections import Counter

from sessions import find_sessions, resolve_session


def _load_gsms(session_path):
    """Load GSMs from md-frames.jsonl."""
    md_path = os.path.join(session_path, "md-frames.jsonl")
    if not os.path.exists(md_path):
        return []
    gsms = []
    with open(md_path) as f:
        for line in f:
            try:
                m = json.loads(line)
            except json.JSONDecodeError:
                continue
            # Only GSMs with annotations
            if m.get("greType") != "GameStateMessage":
                continue
            gsms.append(m)
    return gsms


def _annotation_types(gsm):
    """Extract set of annotation type names from a GSM."""
    types = set()
    for a in gsm.get("annotations", []):
        types.update(a.get("types", []))
    return types


def _matches(gsm, has_types, not_has_types, phase_filter):
    """Check if a GSM matches all filter predicates."""
    types = _annotation_types(gsm)
    for t in has_types:
        if t not in types:
            return False
    for t in not_has_types:
        if t in types:
            return False
    if phase_filter:
        turn_info = gsm.get("turnInfo", {})
        step = turn_info.get("step", "")
        phase = turn_info.get("phase", "")
        if phase_filter not in (step, phase):
            return False
    return True


def _format_types(gsm):
    """One-line annotation type summary."""
    types = sorted(_annotation_types(gsm))
    return ", ".join(types)


def _format_annotations(gsm):
    """Full annotation details."""
    lines = []
    for a in gsm.get("annotations", []):
        types = a.get("types", [])
        aff = a.get("affectorId", 0)
        affected = a.get("affectedIds", [])
        details = a.get("details", {})
        lines.append(f"  {types} affector={aff} affected={affected} details={details}")
    return "\n".join(lines)


def _format_objects(gsm):
    """Object summary."""
    objs = gsm.get("objects", [])
    if not objs:
        return "  (no objects)"
    lines = []
    for o in objs:
        iid = o.get("instanceId", "?")
        grp = o.get("grpId", "?")
        zone = o.get("zoneId", "?")
        attack = o.get("attackState", "")
        block = o.get("blockState", "")
        flags = []
        if attack:
            flags.append(f"attack={attack}")
        if block:
            flags.append(f"block={block}")
        extra = " " + " ".join(flags) if flags else ""
        lines.append(f"  iid={iid} grp={grp} zone={zone}{extra}")
    return "\n".join(lines)


def _format_full(gsm):
    """Full GSM dump (compact JSON)."""
    return json.dumps(gsm, separators=(",", ":"))


def _gsm_header(gsm, session_name):
    """One-line GSM identifier."""
    ti = gsm.get("turnInfo", {})
    turn = ti.get("turn", "?")
    phase = ti.get("phase", "?")
    step = ti.get("step", "?")
    gs_id = gsm.get("gsId", "?")
    idx = gsm.get("index", "?")
    n_ann = len(gsm.get("annotations", []))
    n_obj = len(gsm.get("objects", []))
    return f"[{session_name} idx={idx} gs={gs_id} T{turn} {phase}/{step}] {n_ann} anns, {n_obj} objs"


def cmd_filter(args):
    """Filter GSMs by predicates and display."""
    recordings_dir = os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
        "recordings",
    )

    has_types = args.has or []
    not_has_types = args.not_has or []
    phase_filter = args.phase
    show = args.show or "types"
    sample = args.sample
    group_by = args.group_by

    # Resolve sessions
    if args.session:
        path = resolve_session(args.session, recordings_dir)
        if not path:
            print(f"error: no session matching '{args.session}'", file=sys.stderr)
            sys.exit(1)
        sessions = [{"name": os.path.basename(path.rstrip("/")), "path": path}]
    else:
        sessions = find_sessions(recordings_dir)

    # Collect matches
    matches = []
    for s in sessions:
        gsms = _load_gsms(s["path"])
        for gsm in gsms:
            if _matches(gsm, has_types, not_has_types, phase_filter):
                matches.append((s["name"], gsm))

    if not matches:
        print("No matching GSMs found.", file=sys.stderr)
        sys.exit(0)

    # Group-by mode
    if group_by == "types":
        combos = Counter()
        for _, gsm in matches:
            key = tuple(sorted(_annotation_types(gsm)))
            combos[key] += 1
        print(f"{len(matches)} matching GSMs across {len(sessions)} sessions\n")
        for combo, count in combos.most_common():
            print(f"  {count:3d}x  {', '.join(combo)}")
        return

    # Sample mode
    if sample and sample < len(matches):
        # Spread samples across sessions
        step = len(matches) // sample
        matches = [matches[i * step] for i in range(sample)]

    print(f"{len(matches)} matching GSMs\n")

    for session_name, gsm in matches:
        print(_gsm_header(gsm, session_name))
        if show == "types":
            print(f"  types: {_format_types(gsm)}")
        elif show == "annotations":
            print(_format_annotations(gsm))
        elif show == "objects":
            print(_format_objects(gsm))
        elif show == "full":
            print(_format_full(gsm))
        print()


def build_parser(subparsers):
    """Register gsm subcommands on a parent subparser."""
    s = subparsers.add_parser("gsm", help="GSM-level query and analysis")
    ss = s.add_subparsers(dest="verb")

    p = ss.add_parser("filter", help="Filter GSMs by annotation predicates")
    p.add_argument("--has", action="append", help="Require annotation type (repeatable)")
    p.add_argument("--not-has", action="append", help="Exclude annotation type (repeatable)")
    p.add_argument("--phase", help="Filter by phase/step name")
    p.add_argument("--show", choices=["types", "annotations", "objects", "full"], default="types",
                   help="What to display (default: types)")
    p.add_argument("--sample", type=int, help="Limit to N evenly-spaced samples")
    p.add_argument("--group-by", choices=["types"], help="Aggregate mode")
    p.add_argument("session", nargs="?", help="Session hint (default: all)")
