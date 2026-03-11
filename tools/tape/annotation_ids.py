#!/usr/bin/env python3
"""
Annotation ID semantic analyzer.

Classifies affectorId / affectedIds values by semantic role across
md-frames.jsonl recordings, using three layers:
  Layer 1 (ranges)   — numeric range classification (stateless)
  Layer 2 (analyze)  — instance ID provenance via object accumulator
  Layer 3 (patterns) — cross-field relationship analysis

Usage:
    python3 tooling/scripts/annotation-ids.py ranges [session]
    python3 tooling/scripts/annotation-ids.py analyze [session] [--include-details]
    python3 tooling/scripts/annotation-ids.py detail <AnnotationType> [session] [--include-details]

Session defaults to all recordings with md-frames.jsonl.
Substring matching supported (e.g. "TUTORIAL", "03-10").
"""

import sys
import json
import glob
import os
from collections import defaultdict, Counter


# ── Session resolution ──────────────────────────────────────────────


def find_md_sessions(session=None):
    """Return list of md-frames.jsonl paths. Fuzzy session match or all."""
    if session:
        path = f"recordings/{session}/md-frames.jsonl"
        if os.path.exists(path):
            return [path]
        matches = sorted(glob.glob(f"recordings/*{session}*/md-frames.jsonl"))
        if matches:
            return matches
        print(f"No md-frames.jsonl for session '{session}'", file=sys.stderr)
        sys.exit(1)
    files = sorted(glob.glob("recordings/*/md-frames.jsonl"))
    if not files:
        print("No md-frames.jsonl found. Run: just serve-proxy", file=sys.stderr)
        sys.exit(1)
    return files


def session_name(path):
    return os.path.basename(os.path.dirname(path))


def load_frames(path):
    frames = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                frames.append(json.loads(line))
    return frames


# ── Layer 1: Range classification ───────────────────────────────────


def classify_range(val):
    """Classify a single ID value by numeric range."""
    if val is None:
        return "absent"
    if val == 0:
        return "absent"
    if val < 0:
        return "sentinel"
    if val <= 2:
        return "seat"
    if 18 <= val <= 50:
        return "zone"
    if 51 <= val <= 99:
        # Static game-rule source IDs (e.g. 55-57 on LayeredEffect).
        # Not in gameObjects — rule engine internals.
        return "static"
    if 100 <= val < 4000:
        return "instance"
    if 4000 <= val < 7000:
        return "counter"
    if 7000 <= val < 9000:
        return "effect"
    if val >= 9000:
        return "synthetic"
    # 3–17: unclassified (gap between seat and zone)
    return f"unk({val})"


# ── Layer 2: Object accumulator ─────────────────────────────────────


class ObjectAccumulator:
    """Track object lifecycles across GSMs for provenance lookups."""

    def __init__(self):
        self.objects = {}  # instanceId -> ObjectInfo dict
        self.ability_ids = set()  # IDs seen in AbilityInstanceCreated

    def process_frame(self, frame):
        """Update accumulator from a GSM frame."""
        if frame.get("greType") != "GameStateMessage":
            return

        gsm_type = frame.get("gsmType", "")
        gs_id = frame.get("gsId", 0)

        if gsm_type == "Full":
            self._process_full(frame, gs_id)
        elif gsm_type == "Diff":
            self._process_diff(frame, gs_id)

        # Track ability instances from annotations
        for ann in frame.get("annotations", []):
            self._track_annotation(ann)

    def _process_full(self, frame, gs_id):
        """Full GSM: replace entire object map."""
        self.objects = {}
        for obj in frame.get("objects", []):
            iid = obj.get("instanceId")
            if iid is not None:
                self.objects[iid] = self._make_info(obj, gs_id)

    def _process_diff(self, frame, gs_id):
        """Diff GSM: merge objects, apply deletions."""
        for obj in frame.get("objects", []):
            iid = obj.get("instanceId")
            if iid is not None:
                self.objects[iid] = self._make_info(obj, gs_id)

        for iid in frame.get("diffDeletedInstanceIds", []):
            # Don't remove — keep for provenance lookups on later references
            if iid in self.objects:
                self.objects[iid]["deleted"] = True

    def _make_info(self, obj, gs_id):
        grp_id = obj.get("grpId", 0)
        zone_id = obj.get("zoneId", 0)
        owner = obj.get("ownerSeatId", obj.get("owner", 0))
        card_types = obj.get("cardTypes", [])
        return {
            "grpId": grp_id,
            "zoneId": zone_id,
            "cardTypes": card_types,
            "owner": owner,
            "born_gs": gs_id,
            "is_ability": False,
            "deleted": False,
        }

    def _track_annotation(self, ann):
        types = ann.get("types", [])
        if "AbilityInstanceCreated" in types:
            for aid in ann.get("affectedIds", []):
                self.ability_ids.add(aid)
                if aid not in self.objects:
                    self.objects[aid] = {
                        "grpId": 0,
                        "zoneId": 0,
                        "cardTypes": [],
                        "owner": 0,
                        "born_gs": 0,
                        "is_ability": True,
                        "deleted": False,
                    }
                else:
                    self.objects[aid]["is_ability"] = True

        if "ObjectIdChanged" in types:
            details = ann.get("details", {})
            orig = details.get("orig_id")
            new = details.get("new_id")
            if orig and new and orig in self.objects:
                info = dict(self.objects[orig])
                prev = info.get("prev_ids", [])
                info["prev_ids"] = prev + [orig]
                self.objects[new] = info
                self.objects[orig]["deleted"] = True

    def classify_id(self, val):
        """Classify an instance-range ID as card or ability."""
        if val in self.ability_ids:
            return "ability"
        info = self.objects.get(val)
        if info is None:
            return "unknown"
        if info.get("is_ability"):
            return "ability"
        return "card"

    def describe_id(self, val):
        """Human-readable description of an ID."""
        role = classify_range(val)
        if role != "instance":
            return role

        info = self.objects.get(val)
        if info is None:
            kind = "ability" if val in self.ability_ids else "unknown"
            return f"{kind}, id={val}"

        kind = "ability" if info.get("is_ability") else "card"
        grp = info.get("grpId", 0)
        zone = info.get("zoneId", 0)
        parts = [kind]
        if grp:
            parts.append(f"grpId:{grp}")
        if zone:
            parts.append(f"zone:{zone}")
        return ", ".join(parts)


# ── Layer 2: Provenance classification ──────────────────────────────


def classify_provenance(val, accum):
    """Classify an ID with provenance from accumulator."""
    role = classify_range(val)
    if role == "instance" and accum:
        return accum.classify_id(val)
    return role


# ── Layer 3: Pattern classification ─────────────────────────────────


def classify_pattern(affector_role, affected_roles):
    """Classify the relationship pattern between affector and affected."""
    if affector_role == "absent" and not affected_roles:
        return "empty"

    if affector_role == "absent":
        return "absent->target"

    if affector_role == "seat":
        if affected_roles and all(r == "seat" for r in affected_roles):
            return "self"
        return "player->target"

    if affector_role == "zone":
        return "zone->cards"

    # instance-range roles: card, ability, instance, unknown
    if affected_roles:
        if len(affected_roles) == 1 and affected_roles[0] == "seat":
            return "source->player"
        # Check for self-referential
        # (handled at annotation level, not role level)
        return "source->target"

    return "source->none"


# ── Detail key instance ID extraction ───────────────────────────────

# Detail keys known to carry instance IDs
DETAIL_INSTANCE_KEYS = frozenset(
    {
        "orig_id",
        "new_id",  # ObjectIdChanged
        "topIds",
        "bottomIds",  # Scry
        "id",  # ManaPaid
        "SourceParent",  # Qualification
        "promptParameters",  # TargetSpec
        "originalAbilityObjectZcid",  # AddAbility
    }
)

# Detail keys that look like IDs but aren't
DETAIL_SKIP_KEYS = frozenset(
    {
        "grpid",
        "abilityGrpId",
        "AbilityGrpId",
        "effect_id",
        "counter_type",
        "UniqueAbilityId",
        "promptId",
        "sourceAbilityGRPID",
    }
)


def extract_detail_ids(details):
    """Extract instance IDs from detail values. Returns {key: [values]}."""
    result = {}
    for k, v in details.items():
        if k in DETAIL_SKIP_KEYS:
            continue
        if k not in DETAIL_INSTANCE_KEYS:
            continue
        vals = v if isinstance(v, list) else [v]
        int_vals = [x for x in vals if isinstance(x, int) and x >= 100]
        if int_vals:
            result[k] = int_vals
    return result


# ── Data collection ─────────────────────────────────────────────────


class AnnotationStats:
    """Collects per-type statistics across annotations."""

    def __init__(self):
        # Layer 1: range distributions
        self.affector_ranges = defaultdict(Counter)  # type -> Counter of roles
        self.affected_ranges = defaultdict(Counter)  # type -> Counter of roles
        self.counts = Counter()  # type -> total count

        # Layer 2: provenance distributions (filled only with accumulator)
        self.affector_prov = defaultdict(Counter)
        self.affected_prov = defaultdict(Counter)

        # Layer 3: pattern distributions
        self.patterns = defaultdict(Counter)

        # Self-reference tracking (affectorId in affectedIds)
        self.self_refs = defaultdict(int)

        # Detail key instance IDs
        self.detail_ranges = defaultdict(lambda: defaultdict(Counter))
        self.detail_prov = defaultdict(lambda: defaultdict(Counter))

        # Examples for detail subcommand
        self.examples = defaultdict(list)  # type -> list of example dicts

    def record(self, ann, accum=None, include_details=False, session=None, gs_id=0):
        ann_types = ann.get("types", [])
        affector_raw = ann.get("affectorId")
        affected_raw = ann.get("affectedIds", [])
        details = ann.get("details", {})

        affector_range = classify_range(affector_raw)
        affected_ranges_list = [classify_range(a) for a in affected_raw]

        affector_prov = (
            classify_provenance(affector_raw, accum) if accum else affector_range
        )
        affected_prov_list = (
            [classify_provenance(a, accum) for a in affected_raw]
            if accum
            else affected_ranges_list
        )

        # Check self-reference
        is_self = (
            affector_raw is not None
            and affector_raw != 0
            and len(affected_raw) > 0
            and affector_raw in affected_raw
        )

        pattern = (
            "self" if is_self else classify_pattern(affector_prov, affected_prov_list)
        )

        for t in ann_types:
            self.counts[t] += 1
            self.affector_ranges[t][affector_range] += 1
            self.affected_ranges[t][_summarize_roles(affected_ranges_list)] += 1

            if accum:
                self.affector_prov[t][affector_prov] += 1
                self.affected_prov[t][_summarize_roles(affected_prov_list)] += 1

            self.patterns[t][pattern] += 1

            if is_self:
                self.self_refs[t] += 1

            if include_details:
                detail_ids = extract_detail_ids(details)
                for dk, vals in detail_ids.items():
                    for v in vals:
                        self.detail_ranges[t][dk][classify_range(v)] += 1
                        if accum:
                            self.detail_prov[t][dk][classify_provenance(v, accum)] += 1

            # Store examples (capped per type)
            if len(self.examples[t]) < 10:
                ex = {
                    "gs": gs_id,
                    "affectorId": affector_raw,
                    "affectedIds": affected_raw,
                    "details": details,
                    "session": session,
                }
                if accum and affector_raw is not None:
                    ex["affector_desc"] = accum.describe_id(affector_raw)
                if accum:
                    ex["affected_descs"] = [accum.describe_id(a) for a in affected_raw]
                self.examples[t].append(ex)


def _summarize_roles(roles):
    """Summarize a list of roles into a single string."""
    if not roles:
        return "empty"
    c = Counter(roles)
    if len(c) == 1:
        return list(c.keys())[0]
    parts = sorted(c.items(), key=lambda x: -x[1])
    return "+".join(f"{r}" for r, _ in parts)


# ── Processing ──────────────────────────────────────────────────────


def process_sessions(sessions, use_accumulator=False, include_details=False):
    """Process all sessions and return AnnotationStats."""
    stats = AnnotationStats()

    for path in sessions:
        sname = session_name(path)
        frames = load_frames(path)
        accum = ObjectAccumulator() if use_accumulator else None

        for frame in frames:
            if accum:
                accum.process_frame(frame)

            if frame.get("greType") != "GameStateMessage":
                continue

            gs_id = frame.get("gsId", 0)

            for ann in frame.get("annotations", []):
                stats.record(ann, accum, include_details, sname, gs_id)
            for ann in frame.get("persistentAnnotations", []):
                stats.record(ann, accum, include_details, sname, gs_id)

    return stats


# ── Output formatting ──────────────────────────────────────────────


def fmt_counter(counter, total):
    """Format a Counter as percentage distribution."""
    if total == 0:
        return "-"
    parts = []
    for role, count in counter.most_common():
        pct = 100 * count / total
        if pct >= 1:
            parts.append(f"{role}:{pct:.0f}%")
        elif count > 0:
            parts.append(f"{role}:<1%")
    return " ".join(parts) if parts else "-"


def print_ranges(stats):
    """Print Layer 1 output: range classification table."""
    types = sorted(stats.counts, key=lambda t: -stats.counts[t])
    if not types:
        print("No annotations found.")
        return

    max_name = max(len(t) for t in types)
    hdr_name = max(max_name, 25)

    print(
        f"{'Annotation Type':<{hdr_name}}  {'n':>5}  {'affectorId':<35}  {'affectedIds':<35}"
    )
    print("─" * (hdr_name + 80))

    for t in types:
        n = stats.counts[t]
        aff = fmt_counter(stats.affector_ranges[t], n)
        eff = fmt_counter(stats.affected_ranges[t], n)
        print(f"{t:<{hdr_name}}  {n:>5}  {aff:<35}  {eff:<35}")


def print_analyze(stats):
    """Print Layer 2+3 output: provenance + patterns."""
    types = sorted(stats.counts, key=lambda t: -stats.counts[t])
    if not types:
        print("No annotations found.")
        return

    max_name = max(len(t) for t in types)
    hdr_name = max(max_name, 25)

    print(
        f"{'Annotation Type':<{hdr_name}}  {'n':>5}  {'affectorId':<30}  {'affectedIds':<25}  {'Pattern':<25}"
    )
    print("─" * (hdr_name + 90))

    for t in types:
        n = stats.counts[t]
        aff = (
            fmt_counter(stats.affector_prov[t], n)
            if stats.affector_prov[t]
            else fmt_counter(stats.affector_ranges[t], n)
        )
        eff = (
            fmt_counter(stats.affected_prov[t], n)
            if stats.affected_prov[t]
            else fmt_counter(stats.affected_ranges[t], n)
        )
        pat = fmt_counter(stats.patterns[t], n)
        print(f"{t:<{hdr_name}}  {n:>5}  {aff:<30}  {eff:<25}  {pat:<25}")

    # Detail key analysis (if any)
    if stats.detail_ranges:
        print()
        print("Detail key instance IDs:")
        print("─" * 80)
        for t in types:
            if t not in stats.detail_prov and t not in stats.detail_ranges:
                continue
            dkeys = stats.detail_prov.get(t, stats.detail_ranges.get(t, {}))
            if not dkeys:
                continue
            for dk, counter in sorted(dkeys.items()):
                total = sum(counter.values())
                dist = fmt_counter(counter, total)
                print(f"  {t}.{dk}: {dist} (n={total})")


def print_detail(stats, ann_type):
    """Print detail view for a single annotation type."""
    if ann_type not in stats.counts:
        print(f"Annotation type '{ann_type}' not found.", file=sys.stderr)
        avail = sorted(stats.counts.keys())
        print(f"Available types: {', '.join(avail)}", file=sys.stderr)
        sys.exit(1)

    n = stats.counts[ann_type]
    nsessions = len(set(ex["session"] for ex in stats.examples.get(ann_type, [])))

    print(
        f"{ann_type} (n={n} across {nsessions} session{'s' if nsessions != 1 else ''})"
    )
    print()

    # Provenance summary
    aff_src = (
        stats.affector_prov[ann_type]
        if stats.affector_prov[ann_type]
        else stats.affector_ranges[ann_type]
    )
    eff_src = (
        stats.affected_prov[ann_type]
        if stats.affected_prov[ann_type]
        else stats.affected_ranges[ann_type]
    )
    print(f"  affectorId:  {fmt_counter(aff_src, n)}")
    print(f"  affectedIds: {fmt_counter(eff_src, n)}")
    print(f"  Pattern:     {fmt_counter(stats.patterns[ann_type], n)}")

    if ann_type in stats.self_refs:
        pct = 100 * stats.self_refs[ann_type] / n
        print(f"  Self-ref:    {pct:.0f}% (affectorId in affectedIds)")

    # Detail key analysis
    dkeys = stats.detail_prov.get(ann_type, stats.detail_ranges.get(ann_type, {}))
    if dkeys:
        print()
        print("  Detail instance IDs:")
        for dk, counter in sorted(dkeys.items()):
            total = sum(counter.values())
            print(f"    {dk}: {fmt_counter(counter, total)} (n={total})")

    # Examples
    examples = stats.examples.get(ann_type, [])
    if examples:
        print()
        print("  Examples:")
        for ex in examples:
            print(
                f"    gs={ex['gs']:<4}  affectorId={_fmt_id(ex.get('affectorId'), ex.get('affector_desc'))}"
            )
            affected = ex.get("affectedIds", [])
            descs = ex.get("affected_descs", [])
            if affected:
                parts = []
                for i, aid in enumerate(affected):
                    desc = descs[i] if i < len(descs) else None
                    parts.append(_fmt_id(aid, desc))
                print(f"           affectedIds=[{', '.join(parts)}]")
            details = ex.get("details", {})
            if details:
                det_str = json.dumps(details, separators=(",", ":"))
                if len(det_str) > 80:
                    det_str = det_str[:77] + "..."
                print(f"           details: {det_str}")
            print(f"           session: {ex.get('session', '?')}")
            print()


def _fmt_id(val, desc=None):
    if val is None:
        return "<absent>"
    if desc:
        return f"{val} ({desc})"
    return str(val)


# ── CLI dispatch ────────────────────────────────────────────────────


def usage():
    print(__doc__.strip(), file=sys.stderr)
    sys.exit(1)


def main():
    args = sys.argv[1:]
    if not args:
        usage()

    cmd = args[0]
    rest = args[1:]

    include_details = "--include-details" in rest
    rest = [a for a in rest if a != "--include-details"]

    if cmd == "ranges":
        session = rest[0] if rest else None
        sessions = find_md_sessions(session)
        print(
            f"Scanning {len(sessions)} session{'s' if len(sessions) != 1 else ''}...",
            file=sys.stderr,
        )
        stats = process_sessions(sessions, use_accumulator=False, include_details=False)
        print_ranges(stats)

    elif cmd == "analyze":
        session = rest[0] if rest else None
        sessions = find_md_sessions(session)
        print(
            f"Scanning {len(sessions)} session{'s' if len(sessions) != 1 else ''}...",
            file=sys.stderr,
        )
        stats = process_sessions(
            sessions, use_accumulator=True, include_details=include_details
        )
        print_analyze(stats)

    elif cmd == "detail":
        if not rest:
            print(
                "Usage: annotation-ids.py detail <AnnotationType> [session]",
                file=sys.stderr,
            )
            sys.exit(1)
        ann_type = rest[0]
        session = rest[1] if len(rest) > 1 else None
        sessions = find_md_sessions(session)
        print(
            f"Scanning {len(sessions)} session{'s' if len(sessions) != 1 else ''}...",
            file=sys.stderr,
        )
        stats = process_sessions(
            sessions, use_accumulator=True, include_details=include_details
        )
        print_detail(stats, ann_type)

    else:
        print(f"Unknown subcommand: {cmd}", file=sys.stderr)
        usage()


if __name__ == "__main__":
    main()
