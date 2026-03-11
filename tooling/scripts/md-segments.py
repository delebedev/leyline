#!/usr/bin/env python3
"""
Match Door segment miner + templatizer.

Extracts bounded interaction segments from md-frames.jsonl recordings
and produces templatized JSON where instance IDs are replaced with
symbolic variables ($var_1, $var_2, ...).

Usage:
    # List segments in a recording
    python3 tooling/scripts/md-segments.py list [session]

    # Extract a segment by category (e.g. PlayLand, CastSpell, Resolve)
    python3 tooling/scripts/md-segments.py extract PlayLand [session]

    # Extract + templatize
    python3 tooling/scripts/md-segments.py template PlayLand [session]

Session defaults to most recent recording with md-frames.jsonl.
"""

import sys
import json
import glob
import os
from collections import OrderedDict


def find_md_jsonl(session=None):
    """Find md-frames.jsonl, with fuzzy session matching."""
    if session:
        path = f"recordings/{session}/md-frames.jsonl"
        if os.path.exists(path):
            return path
        matches = sorted(glob.glob(f"recordings/*{session}*/md-frames.jsonl"))
        if matches:
            return matches[-1]
        print(f"No md-frames.jsonl for session '{session}'", file=sys.stderr)
        sys.exit(1)
    files = sorted(glob.glob("recordings/*/md-frames.jsonl"), reverse=True)
    if not files:
        print("No md-frames.jsonl found. Run: just serve-proxy", file=sys.stderr)
        sys.exit(1)
    return files[0]


def session_name(path):
    return os.path.basename(os.path.dirname(path))


def load_frames(path):
    """Load all frames from md-frames.jsonl."""
    frames = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                frames.append(json.loads(line))
    return frames


# --- Segment Mining ---


def find_segments_by_category(frames, category):
    """
    Find GSM Diff frames that contain a ZoneTransfer annotation
    with the given category (PlayLand, CastSpell, Resolve, etc.).

    Returns list of segments, each a dict with:
      - index: frame index in the recording
      - gsId / prevGsId: state chain
      - frame: the full frame dict
      - prev_frame: the preceding frame (context)
      - annotations: just the annotations list
      - category: the matched category
    """
    segments = []
    for i, frame in enumerate(frames):
        if frame.get("gsmType") != "Diff":
            continue
        anns = frame.get("annotations", [])
        for ann in anns:
            details = ann.get("details", {})
            if details.get("category") == category:
                seg = {
                    "index": i,
                    "gsId": frame.get("gsId"),
                    "prevGsId": frame.get("prevGsId"),
                    "frame": frame,
                    "prev_frame": frames[i - 1] if i > 0 else None,
                    "annotations": anns,
                    "category": category,
                }
                segments.append(seg)
                break  # one segment per frame
    return segments


def list_all_categories(frames):
    """List all ZoneTransfer categories found in the recording."""
    cats = {}
    for i, frame in enumerate(frames):
        anns = frame.get("annotations", [])
        for ann in anns:
            details = ann.get("details", {})
            cat = details.get("category")
            if cat:
                cats.setdefault(cat, []).append(i)
    return cats


# --- Templatizing ---


def collect_instance_ids(frame):
    """
    Collect all integer values that look like instance IDs from a frame.
    Sources: objectIds in zones, instanceId in objects, affectedIds/affectorId
    in annotations, details containing ID values.
    """
    ids = set()

    # Zone objectIds
    for zone in frame.get("zones", []):
        for oid in zone.get("objectIds", []):
            ids.add(oid)

    # Object instanceIds
    for obj in frame.get("objects", []):
        iid = obj.get("instanceId")
        if iid is not None:
            ids.add(iid)

    # Annotation IDs
    for ann in frame.get("annotations", []):
        aff = ann.get("affectorId")
        if aff is not None and aff > 10:  # skip seat IDs (1, 2)
            ids.add(aff)
        for aid in ann.get("affectedIds", []):
            if aid > 10:
                ids.add(aid)
        # Detail values that look like IDs
        details = ann.get("details", {})
        for key in ("orig_id", "new_id"):
            val = details.get(key)
            if val is not None:
                ids.add(val)

    # Persistent annotations
    for ann in frame.get("persistentAnnotations", []):
        aff = ann.get("affectorId")
        if aff is not None and aff > 10:
            ids.add(aff)
        for aid in ann.get("affectedIds", []):
            if aid > 10:
                ids.add(aid)

    return ids


def collect_zone_ids(frames):
    """Collect all zoneIds from zone definitions across all frames."""
    zone_ids = set()
    for frame in frames:
        for zone in frame.get("zones", []):
            zid = zone.get("zoneId")
            if zid is not None:
                zone_ids.add(zid)
    return zone_ids


def templatize_frame(frame, zone_ids=None):
    """
    Replace instance IDs with symbolic $var_N variables.

    Returns (templatized_frame, id_map) where id_map is {original_id: "$var_N"}.

    Skips: seat IDs (1, 2), zone IDs (structural constants from zone defs),
    annotation sequence IDs, grpIds, gsId/prevGsId.

    ID assignment order: ObjectIdChanged orig_id first (source), then new_id,
    then remaining IDs in order of first appearance. This gives stable variable
    names across recordings of the same mechanic.
    """
    if zone_ids is None:
        zone_ids = set()

    # Build ordered ID list: ObjectIdChanged gives us the canonical ordering
    ordered_ids = []
    seen = set()

    def add_id(val):
        if val not in seen and val > 2 and val not in zone_ids:
            ordered_ids.append(val)
            seen.add(val)

    # Pass 1: ObjectIdChanged annotations define the primary mapping
    for ann in frame.get("annotations", []):
        if "ObjectIdChanged" in ann.get("types", []):
            details = ann.get("details", {})
            add_id(details.get("orig_id", 0))
            add_id(details.get("new_id", 0))

    # Pass 2: remaining IDs from annotations (affectorId, affectedIds)
    for ann in frame.get("annotations", []):
        aff = ann.get("affectorId")
        if aff is not None:
            add_id(aff)
        for aid in ann.get("affectedIds", []):
            add_id(aid)
        details = ann.get("details", {})
        for key in ("orig_id", "new_id"):
            add_id(details.get(key, 0))

    # Pass 3: objects and zones
    for obj in frame.get("objects", []):
        add_id(obj.get("instanceId", 0))
    for zone in frame.get("zones", []):
        for oid in zone.get("objectIds", []):
            add_id(oid)

    # Pass 4: persistent annotations
    for ann in frame.get("persistentAnnotations", []):
        aff = ann.get("affectorId")
        if aff is not None:
            add_id(aff)
        for aid in ann.get("affectedIds", []):
            add_id(aid)

    # Build map
    id_map = {}
    for i, original in enumerate(ordered_ids, 1):
        id_map[original] = f"$var_{i}"

    # Deep-replace, skipping structural keys
    result = _replace_ids_deep(frame, id_map)
    return result, id_map


# Keys whose integer values should NEVER be templatized
_SKIP_KEYS = frozenset(
    {
        "gsId",
        "prevGsId",
        "gameStateId",
        "prevGameStateId",  # state chain
        "zoneId",
        "zone_src",
        "zone_dest",
        "source_zone",  # zone refs
        "grpId",
        "abilityGrpId",  # card identity
        "id",
        "msgId",  # sequence numbers
        "owner",
        "controller",
        "seatId",  # seat refs
        "actionType",
        "uniqueAbilityCount",  # enum/count
        "phase",
        "step",  # phase refs
        "colors",
        "counter_type",
        "count",  # mechanic values
        "topCount",
        "bottomCount",  # scry counts
    }
)


def _replace_ids_deep(obj, id_map, key=None):
    """Recursively replace integer values that match id_map keys.
    Skips values under structural keys (zone IDs, grpIds, etc.)."""
    if isinstance(obj, dict):
        return {k: _replace_ids_deep(v, id_map, key=k) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [_replace_ids_deep(item, id_map, key=key) for item in obj]
    elif isinstance(obj, int) and obj in id_map and key not in _SKIP_KEYS:
        return id_map[obj]
    return obj


# --- Segment summary ---


def segment_summary(seg):
    """One-line summary of a segment."""
    frame = seg["frame"]
    anns = seg["annotations"]
    ann_types = []
    for ann in anns:
        ann_types.extend(ann.get("types", []))

    # Find card names from objects
    card_names = []
    for obj in frame.get("objects", []):
        subtypes = obj.get("subtypes", [])
        name = obj.get("name", "")
        grp = obj.get("grpId", 0)
        card_types = obj.get("cardTypes", [])
        label = name or ", ".join(subtypes) or ", ".join(card_types) or f"grp:{grp}"
        card_names.append(label)

    return (
        f"  idx={seg['index']:3d}  gsId={seg['gsId']:<4}  "
        f"anns=[{', '.join(ann_types)}]  "
        f"cards=[{', '.join(card_names)}]"
    )


# --- Main ---


def cmd_list(args):
    """List all segment categories in a recording."""
    session = args[0] if args else None
    path = find_md_jsonl(session)
    frames = load_frames(path)
    cats = list_all_categories(frames)
    print(f"Session: {session_name(path)}")
    print(f"Frames: {len(frames)}")
    print()
    for cat, indices in sorted(cats.items()):
        print(f"  {cat}: {len(indices)} segments at indices {indices}")


def cmd_extract(args):
    """Extract segments by category."""
    if not args:
        print("Usage: md-segments.py extract <category> [session]", file=sys.stderr)
        sys.exit(1)
    category = args[0]
    session = args[1] if len(args) > 1 else None
    path = find_md_jsonl(session)
    frames = load_frames(path)
    segments = find_segments_by_category(frames, category)
    print(f"Session: {session_name(path)}")
    print(f"Found {len(segments)} {category} segment(s):")
    print()
    for seg in segments:
        print(segment_summary(seg))
    if segments:
        # Print first segment's annotations in detail
        print()
        print(f"--- First {category} segment (index {segments[0]['index']}) ---")
        print(json.dumps(segments[0]["annotations"], indent=2))


def cmd_template(args):
    """Extract + templatize a segment."""
    if not args:
        print(
            "Usage: md-segments.py template <category> [session] [--index N]",
            file=sys.stderr,
        )
        sys.exit(1)
    category = args[0]
    session = None
    target_index = 0  # which occurrence to templatize

    i = 1
    while i < len(args):
        if args[i] == "--index" and i + 1 < len(args):
            target_index = int(args[i + 1])
            i += 2
        else:
            session = args[i]
            i += 1

    path = find_md_jsonl(session)
    frames = load_frames(path)
    segments = find_segments_by_category(frames, category)

    if not segments:
        print(f"No {category} segments found", file=sys.stderr)
        sys.exit(1)

    if target_index >= len(segments):
        print(
            f"Only {len(segments)} segments, index {target_index} out of range",
            file=sys.stderr,
        )
        sys.exit(1)

    seg = segments[target_index]
    frame = seg["frame"]

    # Collect zone IDs from the full recording to exclude from templatization
    zone_ids = collect_zone_ids(frames)

    # Extract just the conformance-relevant fields
    conformance_frame = {
        "gsId": frame.get("gsId"),
        "prevGsId": frame.get("prevGsId"),
        "gsmType": frame.get("gsmType"),
        "zones": frame.get("zones", []),
        "objects": frame.get("objects", []),
        "annotations": frame.get("annotations", []),
        "persistentAnnotations": frame.get("persistentAnnotations", []),
    }

    templatized, id_map = templatize_frame(conformance_frame, zone_ids)

    print(f"# Session: {session_name(path)}")
    print(f"# Category: {category}, index: {target_index}, frame: {seg['index']}")
    print(f"# ID mapping:")
    for orig, var in sorted(id_map.items(), key=lambda x: x[1]):
        # Find card info for this ID
        card_info = ""
        for obj in frame.get("objects", []):
            if obj.get("instanceId") == orig:
                grp = obj.get("grpId", 0)
                subtypes = obj.get("subtypes", [])
                card_info = f" (grp:{grp}, {', '.join(subtypes)})"
        print(f"#   {var} = {orig}{card_info}")
    print()
    print(json.dumps(templatized, indent=2))


def cmd_puzzle(args):
    """Generate a .pzl puzzle file from a segment's card/zone layout."""
    if not args:
        print(
            "Usage: md-segments.py puzzle <category> [session] [--index N]",
            file=sys.stderr,
        )
        sys.exit(1)
    category = args[0]
    session = None
    target_index = 0

    i = 1
    while i < len(args):
        if args[i] == "--index" and i + 1 < len(args):
            target_index = int(args[i + 1])
            i += 2
        else:
            session = args[i]
            i += 1

    path = find_md_jsonl(session)
    frames = load_frames(path)
    segments = find_segments_by_category(frames, category)

    if not segments:
        print(f"No {category} segments found", file=sys.stderr)
        sys.exit(1)

    seg = segments[target_index]
    frame = seg["frame"]

    # Reconstruct the BEFORE state from the diff:
    # Objects in Limbo with orig_id are the cards that were in hand before the move.
    # Objects on Battlefield with new_id are where they ended up.
    # Hand zone's objectIds are what remains after the move.

    # Find the card that moved (from ObjectIdChanged annotation)
    moved_card = None
    for ann in frame.get("annotations", []):
        if "ObjectIdChanged" in ann.get("types", []):
            orig = ann["details"]["orig_id"]
            new = ann["details"]["new_id"]
            # Find the object with instanceId=new to get card info
            for obj in frame.get("objects", []):
                if obj.get("instanceId") == new:
                    moved_card = {
                        "orig_id": orig,
                        "new_id": new,
                        "grpId": obj.get("grpId"),
                        "cardTypes": obj.get("cardTypes", []),
                        "subtypes": obj.get("subtypes", []),
                        "superTypes": obj.get("superTypes", []),
                    }
            break

    if not moved_card:
        print("Could not identify moved card from ObjectIdChanged", file=sys.stderr)
        sys.exit(1)

    # Card name from subtypes (for basic lands) or need grpId lookup
    card_name = _card_name_from_obj(moved_card)

    # Remaining hand: the objectIds still in Hand zone after the move
    hand_remaining = []
    for zone in frame.get("zones", []):
        if zone.get("type") == "Hand" and zone.get("owner") == 1:
            # These IDs are post-move; we need to figure out what cards they are.
            # In the recording they're just IDs — we can't resolve names without
            # a Full GSM. For puzzle gen, use placeholder cards.
            hand_remaining = zone.get("objectIds", [])

    # For a PlayLand puzzle, the before-state is:
    # - The moved card was in hand (add it back)
    # - hand_remaining cards were also in hand
    # For simplicity, fill remaining hand slots with Islands
    hand_count = len(hand_remaining) + 1  # +1 for the card we're about to play

    # Check if there's a Full GSM earlier that has card details for remaining hand
    hand_cards = _resolve_hand_cards(frames, seg["index"], hand_remaining)

    lines = []
    lines.append("[metadata]")
    lines.append(f"Name:Conformance {category}")
    lines.append("Goal:Win")
    lines.append("Turns:1")
    lines.append("Difficulty:Tutorial")
    lines.append(
        f"Description:Auto-generated from recording {session_name(path)} frame {seg['index']}."
    )
    lines.append("")
    lines.append("[state]")
    lines.append("ActivePlayer=Human")
    lines.append("ActivePhase=Main1")
    lines.append("HumanLife=20")
    lines.append("AILife=20")
    lines.append("")

    # Build hand: the moved card + remaining cards
    hand_list = [card_name] + hand_cards
    lines.append(f"humanhand={';'.join(hand_list)}")
    lines.append("humanlibrary=Island;Island;Island;Island;Island")
    lines.append("aibattlefield=Plains")
    lines.append("ailibrary=Plains;Plains;Plains;Plains;Plains")

    print("\n".join(lines))


def _card_name_from_obj(obj):
    """Best-effort card name from object metadata."""
    subtypes = obj.get("subtypes", [])
    card_types = obj.get("cardTypes", [])
    super_types = obj.get("superTypes", [])
    if "Land" in card_types and subtypes:
        return subtypes[0]  # "Island", "Plains", etc.
    return (
        subtypes[0]
        if subtypes
        else card_types[0]
        if card_types
        else f"Unknown_{obj.get('grpId', 0)}"
    )


def _resolve_hand_cards(frames, seg_index, hand_ids):
    """
    Try to resolve card names for hand IDs by scanning earlier Full GSMs.
    Falls back to 'Island' for unresolvable IDs.
    """
    # Look backwards for a Full GSM that has these objects
    card_names = {}
    for i in range(seg_index - 1, -1, -1):
        frame = frames[i]
        if frame.get("gsmType") != "Full":
            continue
        for obj in frame.get("objects", []):
            iid = obj.get("instanceId")
            if iid in hand_ids:
                card_names[iid] = _card_name_from_obj(obj)
        if len(card_names) == len(hand_ids):
            break

    return [card_names.get(iid, "Island") for iid in hand_ids]


# --- Binding + Diff ---


def bind_ids(template_anns, engine_anns):
    """
    Bind engine instance IDs to template $var_N variables by structural matching.

    Matches annotations by type + position, then maps engine IDs to template vars
    using affectedIds and detail values (orig_id, new_id).

    Returns {engine_id: "$var_N"} binding map.
    """
    bindings = {}  # engine_id -> $var_N

    for t_ann, e_ann in zip(template_anns, engine_anns):
        t_types = t_ann.get("types", [])
        e_types = e_ann.get("types", [])

        # Types must match (ignoring proto suffixes)
        t_clean = [t.replace("_af5a", "").replace("_a0f6", "") for t in t_types]
        e_clean = [e.replace("_af5a", "").replace("_a0f6", "") for e in e_types]
        if t_clean != e_clean:
            continue

        # Bind affectedIds
        t_affected = t_ann.get("affectedIds", [])
        e_affected = e_ann.get("affectedIds", [])
        for t_id, e_id in zip(t_affected, e_affected):
            if isinstance(t_id, str) and t_id.startswith("$var_"):
                bindings[e_id] = t_id

        # Bind affectorId
        t_affector = t_ann.get("affectorId")
        e_affector = e_ann.get("affectorId")
        if (
            isinstance(t_affector, str)
            and t_affector.startswith("$var_")
            and e_affector
        ):
            bindings[e_affector] = t_affector

        # Bind detail values (orig_id, new_id)
        t_details = t_ann.get("details", {})
        e_details = e_ann.get("details", {})
        for key in ("orig_id", "new_id"):
            t_val = t_details.get(key)
            e_val = e_details.get(key)
            if isinstance(t_val, str) and t_val.startswith("$var_") and e_val:
                bindings[e_val] = t_val

    return bindings


def hydrate_template(template, bindings):
    """
    Replace $var_N placeholders in template with bound engine IDs.
    bindings is {engine_id: "$var_N"}, we need the reverse: {"$var_N": engine_id}.
    """
    reverse = {v: k for k, v in bindings.items()}
    return _hydrate_deep(template, reverse)


def _hydrate_deep(obj, reverse_bindings):
    if isinstance(obj, dict):
        return {k: _hydrate_deep(v, reverse_bindings) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [_hydrate_deep(item, reverse_bindings) for item in obj]
    elif isinstance(obj, str) and obj.startswith("$var_"):
        return reverse_bindings.get(obj, obj)  # leave unbound vars as-is
    return obj


def diff_annotations(recording_anns, engine_anns, bindings):
    """
    Compare recording annotations (hydrated template) against engine annotations.
    Reports structural differences.

    Returns list of diff entries: {type, field, recording, engine}.
    """
    diffs = []
    max_len = max(len(recording_anns), len(engine_anns))

    for i in range(max_len):
        if i >= len(recording_anns):
            diffs.append(
                {
                    "index": i,
                    "type": "extra_engine",
                    "engine": engine_anns[i].get("types", []),
                }
            )
            continue
        if i >= len(engine_anns):
            diffs.append(
                {
                    "index": i,
                    "type": "extra_recording",
                    "recording": recording_anns[i].get("types", []),
                }
            )
            continue

        r_ann = recording_anns[i]
        e_ann = engine_anns[i]

        # Compare types (strip proto suffixes)
        r_types = [
            t.replace("_af5a", "").replace("_a0f6", "") for t in r_ann.get("types", [])
        ]
        e_types = [
            t.replace("_af5a", "").replace("_a0f6", "") for t in e_ann.get("types", [])
        ]
        if r_types != e_types:
            diffs.append(
                {
                    "index": i,
                    "type": "type_mismatch",
                    "recording": r_types,
                    "engine": e_types,
                }
            )
            continue

        # Compare affectedIds
        r_affected = r_ann.get("affectedIds", [])
        e_affected = e_ann.get("affectedIds", [])
        if r_affected != e_affected:
            diffs.append(
                {
                    "index": i,
                    "type": "affectedIds_mismatch",
                    "annotation": r_types,
                    "recording": r_affected,
                    "engine": e_affected,
                }
            )

        # Compare details (key by key)
        r_details = r_ann.get("details", {})
        e_details = e_ann.get("details", {})
        all_keys = set(r_details.keys()) | set(e_details.keys())
        for key in sorted(all_keys):
            r_val = r_details.get(key)
            e_val = e_details.get(key)
            if r_val != e_val:
                diffs.append(
                    {
                        "index": i,
                        "type": "detail_mismatch",
                        "annotation": r_types,
                        "key": key,
                        "recording": r_val,
                        "engine": e_val,
                    }
                )

    return diffs


def cmd_diff(args):
    """
    Diff a templatized recording segment against engine output.

    Usage: md-segments.py diff <template.json> <engine-frame.json>

    Performs ID binding, hydration, and structural comparison.
    """
    if len(args) < 2:
        print(
            "Usage: md-segments.py diff <template.json> <engine-frame.json>",
            file=sys.stderr,
        )
        sys.exit(1)

    template_path = args[0]
    engine_path = args[1]

    # Load template (strip comment lines)
    with open(template_path) as f:
        lines = [l for l in f.readlines() if not l.startswith("#")]
        template = json.loads("".join(lines))

    # Load engine frame
    with open(engine_path) as f:
        engine = json.loads(f.read())

    # Step 1: Bind IDs
    t_anns = template.get("annotations", [])
    e_anns = engine.get("annotations", [])
    bindings = bind_ids(t_anns, e_anns)

    print("=== ID Bindings ===")
    for engine_id, var in sorted(bindings.items(), key=lambda x: x[1]):
        print(f"  {var} = {engine_id}")
    print()

    # Step 2: Hydrate template
    hydrated = hydrate_template(template, bindings)

    # Step 3: Diff annotations
    h_anns = hydrated.get("annotations", [])
    diffs = diff_annotations(h_anns, e_anns, bindings)

    if not diffs:
        print("=== PASS: annotations match ===")
        print(f"  {len(h_anns)} annotations compared, 0 differences")
    else:
        print(f"=== FAIL: {len(diffs)} difference(s) ===")
        for d in diffs:
            print(f"  [{d['index']}] {d['type']}: ", end="")
            if d["type"] == "type_mismatch":
                print(f"recording={d['recording']} engine={d['engine']}")
            elif d["type"] == "detail_mismatch":
                print(
                    f"{d['annotation']} key={d['key']} recording={d['recording']} engine={d['engine']}"
                )
            elif d["type"] == "affectedIds_mismatch":
                print(
                    f"{d['annotation']} recording={d['recording']} engine={d['engine']}"
                )
            else:
                print(json.dumps(d))

    # Step 4: Diff persistent annotations
    h_persist = hydrated.get("persistentAnnotations", [])
    e_persist = engine.get("persistentAnnotations", [])
    p_diffs = diff_annotations(h_persist, e_persist, bindings)

    if p_diffs:
        print(f"\n=== Persistent annotation diffs: {len(p_diffs)} ===")
        for d in p_diffs:
            print(f"  [{d['index']}] {d['type']}: ", end="")
            if d["type"] == "type_mismatch":
                print(f"recording={d['recording']} engine={d['engine']}")
            elif d["type"] == "detail_mismatch":
                print(
                    f"{d['annotation']} key={d['key']} recording={d['recording']} engine={d['engine']}"
                )
            else:
                print(json.dumps(d))


COMMANDS = {
    "list": cmd_list,
    "extract": cmd_extract,
    "template": cmd_template,
    "puzzle": cmd_puzzle,
    "diff": cmd_diff,
}


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        print("Commands:", ", ".join(COMMANDS.keys()))
        sys.exit(1)
    COMMANDS[sys.argv[1]](sys.argv[2:])


if __name__ == "__main__":
    main()
