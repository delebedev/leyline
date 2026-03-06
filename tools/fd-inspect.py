#!/usr/bin/env python3
"""FD frame inspection tool. Used by just fd-* commands."""
import sys
import json
import glob
import os
from collections import Counter, defaultdict

def find_jsonl(session=None):
    if session:
        path = f"recordings/{session}/capture/fd-frames.jsonl"
        if os.path.exists(path):
            return path
        # fuzzy match
        matches = sorted(glob.glob(f"recordings/*{session}*/capture/fd-frames.jsonl"))
        if matches:
            return matches[-1]
        print(f"No fd-frames.jsonl for session '{session}'", file=sys.stderr)
        sys.exit(1)
    files = sorted(glob.glob("recordings/*/capture/fd-frames.jsonl"), reverse=True)
    if not files:
        print("No fd-frames.jsonl found. Run: just serve-proxy", file=sys.stderr)
        sys.exit(1)
    return files[0]

def find_all_jsonl():
    return sorted(glob.glob("recordings/*/capture/fd-frames.jsonl"))

def session_name(path):
    return os.path.basename(os.path.dirname(os.path.dirname(path)))

def load_frames(path):
    with open(path) as f:
        return [json.loads(l) for l in f]

def cmd_tail(n):
    f = find_jsonl()
    frames = load_frames(f)
    print(f"Session: {session_name(f)} ({len(frames)} frames)")
    print(f"{'seq':>5s} {'dir':3s} {'type':8s} {'cmd':<35s} {'size':>8s}  preview")
    print("-" * 110)
    for d in frames[-n:]:
        seq = d.get("seq", 0)
        dr = d.get("dir", "?")
        et = d.get("envelopeType", "")[:8]
        cmd = d.get("cmdTypeName") or ""
        p = d.get("jsonPayload") or ""
        plen = f"{len(p)}B"
        preview = p[:60].replace("\n", " ") if p else ""
        print(f"{seq:5d} {dr:3s} {et:8s} {cmd:<35s} {plen:>8s}  {preview}")

def cmd_search(term):
    f = find_jsonl()
    print(f"Session: {session_name(f)}")
    term_lower = term.lower()
    for d in load_frames(f):
        p = (d.get("jsonPayload") or "").lower()
        cmd_name = (d.get("cmdTypeName") or "").lower()
        if term_lower in p or term_lower in cmd_name:
            seq = d.get("seq", 0)
            dr = d.get("dir", "?")
            cn = d.get("cmdTypeName") or d.get("envelopeType", "")
            payload = d.get("jsonPayload") or ""
            idx = payload.lower().find(term_lower)
            if idx >= 0:
                start = max(0, idx - 30)
                end = min(len(payload), idx + len(term_lower) + 50)
                preview = ("..." if start > 0 else "") + payload[start:end] + ("..." if end < len(payload) else "")
            else:
                preview = payload[:80]
            print(f"{seq:5d} {dr:3s} {cn:<35s} {preview}")

def cmd_show(seq_num):
    f = find_jsonl()
    for d in load_frames(f):
        if d.get("seq") == seq_num:
            print(f"seq={d['seq']} dir={d['dir']} cmd={d.get('cmdTypeName','')} envelope={d.get('envelopeType','')}")
            print(f"transactionId={d.get('transactionId','')}")
            p = d.get("jsonPayload")
            if p:
                try:
                    print(json.dumps(json.loads(p), indent=2))
                except:
                    print(p)
            else:
                print("(no payload)")
            return
    print(f"seq {seq_num} not found")

def cmd_raw(seq_num):
    """Print only the JSON payload for a seq — pipe-friendly for jq."""
    f = find_jsonl()
    for d in load_frames(f):
        if d.get("seq") == seq_num:
            p = d.get("jsonPayload")
            if p:
                print(p)
            return
    print(f"seq {seq_num} not found", file=sys.stderr)
    sys.exit(1)

def cmd_summary():
    f = find_jsonl()
    frames = load_frames(f)
    print(f"Session: {session_name(f)}")
    c2s, s2c = Counter(), Counter()
    for d in frames:
        cmd = d.get("cmdTypeName") or d.get("envelopeType") or "?"
        if d.get("dir") == "C2S":
            c2s[cmd] += 1
        else:
            s2c[cmd] += 1
    total = sum(c2s.values()) + sum(s2c.values())
    print(f"Total: {total} frames ({sum(c2s.values())} C2S, {sum(s2c.values())} S2C)\n")
    print("C2S (client -> server):")
    for cmd, n in c2s.most_common():
        print(f"  {n:4d}  {cmd}")
    print("\nS2C (server -> client):")
    for cmd, n in s2c.most_common():
        print(f"  {n:4d}  {cmd}")

def _build_pairs(frames):
    """Match C2S requests to S2C responses by transactionId."""
    reqs = {}
    pairs = []
    for d in frames:
        tid = d.get("transactionId")
        if not tid:
            continue
        if d["dir"] == "C2S":
            reqs[tid] = d
        elif tid in reqs:
            req = reqs.pop(tid)
            pairs.append((req, d))
    return pairs

def cmd_pairs():
    f = find_jsonl()
    print(f"Session: {session_name(f)}")
    pairs = _build_pairs(load_frames(f))
    print(f"Matched {len(pairs)} request/response pairs:")
    print(f"{'req':>5s} {'rsp':>5s} {'cmd':<35s} {'rsp_size':>10s}")
    print("-" * 60)
    for req, rsp in pairs:
        cmd = req.get("cmdTypeName") or req.get("envelopeType", "")
        rp = rsp.get("jsonPayload") or ""
        print(f"{req['seq']:5d} {rsp['seq']:5d} {cmd:<35s} {len(rp):>9d}B")

def _extract(obj, path):
    """Extract a dotted path from a nested dict. Returns None on miss."""
    for key in path.split("."):
        if isinstance(obj, dict):
            obj = obj.get(key)
        else:
            return None
    return obj

def _get_response_payload(frames, cmd_type):
    """Get first non-trivial S2C payload for a cmdType (int) from paired frames."""
    is_num = str(cmd_type).isdigit()
    ct = int(cmd_type) if is_num else None
    for req, rsp in _build_pairs(frames):
        if is_num:
            if req.get("cmdType") != ct:
                continue
        else:
            if cmd_type.lower() not in (req.get("cmdTypeName") or "").lower():
                continue
        p = rsp.get("jsonPayload") or ""
        if len(p) > 4:
            return json.loads(p)
    return None

def _extract_events(frames):
    """Get the Events array from a 624 response."""
    payload = _get_response_payload(frames, 624)
    return payload.get("Events", []) if payload else []

def cmd_field(field_path, group_by=None):
    """Show distinct values of a field across all events in all proxy sessions."""
    all_files = find_all_jsonl()
    # bucket: group_value -> field_value -> [event names]
    buckets = defaultdict(lambda: defaultdict(list))
    seen_events = set()

    for path in all_files:
        frames = load_frames(path)
        for evt in _extract_events(frames):
            name = evt.get("InternalEventName", "?")
            # deduplicate across sessions (same event, same value)
            val = _extract(evt, field_path)
            val_str = json.dumps(val, sort_keys=True) if isinstance(val, (dict, list)) else str(val)
            grp = "all"
            if group_by:
                g = _extract(evt, group_by)
                grp = json.dumps(g, sort_keys=True) if isinstance(g, (dict, list)) else str(g)
            key = (name, val_str, grp)
            if key not in seen_events:
                seen_events.add(key)
                buckets[grp][val_str].append(name)

    n_sessions = sum(1 for p in all_files if _extract_events(load_frames(p)))
    print(f"Field: {field_path}  ({n_sessions} sessions, {len(seen_events)} observations)", file=sys.stderr)
    if group_by:
        print(f"Grouped by: {group_by}", file=sys.stderr)
    print(file=sys.stderr)

    for grp in sorted(buckets):
        if group_by:
            print(f"── {group_by} = {grp} ──")
        vals = buckets[grp]
        for val in sorted(vals, key=lambda v: -len(vals[v])):
            names = sorted(set(vals[val]))
            preview = ", ".join(names[:6])
            if len(names) > 6:
                preview += f", ... (+{len(names)-6})"
            print(f"  {val:<50s}  [{len(names)}] {preview}")
        print()

def cmd_events(filter_str=None):
    """List all events from the latest 624 response as a table."""
    f = find_jsonl()
    events = _extract_events(load_frames(f))
    if not events:
        print("No events found", file=sys.stderr)
        sys.exit(1)

    if filter_str:
        fl = filter_str.lower()
        events = [e for e in events if fl in e.get("InternalEventName", "").lower()
                  or fl in (e.get("FormatType") or "").lower()
                  or fl in str(e.get("EventTags", [])).lower()
                  or fl in (_extract(e, "EventUXInfo.EventBladeBehavior") or "").lower()]

    print(f"Session: {session_name(f)}  ({len(events)} events)", file=sys.stderr)
    print(f"{'Name':<45s} {'Format':<14s} {'Blade':<8s} {'State':<12s} {'Prio':>4s} {'Flags'}")
    print("-" * 120)
    for e in sorted(events, key=lambda x: x.get("InternalEventName", "")):
        name = e.get("InternalEventName", "?")
        fmt = e.get("FormatType", "?")
        blade = _extract(e, "EventUXInfo.EventBladeBehavior") or "—"
        state = e.get("EventState") or "—"
        prio = _extract(e, "EventUXInfo.DisplayPriority")
        prio_s = str(prio) if prio is not None else "—"
        flags = ",".join(e.get("Flags", []))
        print(f"  {name:<43s} {fmt:<14s} {blade:<8s} {state:<12s} {prio_s:>4s} {flags}")

def cmd_diff(session1, session2, cmd_type="624"):
    """Diff events between two sessions."""
    f1 = find_jsonl(session1)
    f2 = find_jsonl(session2)
    s1, s2 = session_name(f1), session_name(f2)

    if cmd_type == "624":
        evts1 = {e["InternalEventName"]: e for e in _extract_events(load_frames(f1))}
        evts2 = {e["InternalEventName"]: e for e in _extract_events(load_frames(f2))}
        names1, names2 = set(evts1), set(evts2)

        added = names2 - names1
        removed = names1 - names2
        common = names1 & names2

        print(f"Events diff: {s1} → {s2}")
        print(f"  {s1}: {len(names1)} events")
        print(f"  {s2}: {len(names2)} events")
        print()

        if added:
            print(f"Added ({len(added)}):")
            for n in sorted(added):
                e = evts2[n]
                print(f"  + {n}  ({e.get('FormatType','?')}, blade={_extract(e,'EventUXInfo.EventBladeBehavior') or '—'})")
            print()
        if removed:
            print(f"Removed ({len(removed)}):")
            for n in sorted(removed):
                e = evts1[n]
                print(f"  - {n}  ({e.get('FormatType','?')}, blade={_extract(e,'EventUXInfo.EventBladeBehavior') or '—'})")
            print()

        # check for field changes in common events
        changed = []
        check_fields = ["EventState", "FormatType", "Flags", "EventTags",
                        "EventUXInfo.EventBladeBehavior", "EventUXInfo.DisplayPriority",
                        "EventUXInfo.DeckSelectFormat", "WinCondition",
                        "StartTime", "LockedTime", "ClosedTime"]
        for n in sorted(common):
            diffs = []
            for fp in check_fields:
                v1 = _extract(evts1[n], fp)
                v2 = _extract(evts2[n], fp)
                if v1 != v2:
                    diffs.append((fp, v1, v2))
            if diffs:
                changed.append((n, diffs))
        if changed:
            print(f"Changed ({len(changed)}):")
            for n, diffs in changed:
                print(f"  ~ {n}")
                for fp, v1, v2 in diffs:
                    print(f"      {fp}: {v1} → {v2}")
            print()
        if not added and not removed and not changed:
            print("No differences.")
    else:
        # Generic JSON diff for other cmdTypes
        p1 = _get_response_payload(load_frames(f1), cmd_type)
        p2 = _get_response_payload(load_frames(f2), cmd_type)
        if not p1:
            print(f"CmdType {cmd_type} not found in {s1}", file=sys.stderr)
            sys.exit(1)
        if not p2:
            print(f"CmdType {cmd_type} not found in {s2}", file=sys.stderr)
            sys.exit(1)
        j1 = json.dumps(p1, indent=2, sort_keys=True)
        j2 = json.dumps(p2, indent=2, sort_keys=True)
        if j1 == j2:
            print(f"CmdType {cmd_type}: identical between {s1} and {s2}")
        else:
            import difflib
            for line in difflib.unified_diff(j1.splitlines(), j2.splitlines(),
                                             fromfile=s1, tofile=s2, lineterm=""):
                print(line)

def cmd_response(cmd_type_str):
    """Print the S2C response payload for a given cmdType (by number or name)."""
    f = find_jsonl()
    frames = load_frames(f)
    pairs = _build_pairs(frames)

    # match by number or name (case-insensitive substring)
    is_num = cmd_type_str.isdigit()
    matches = []
    for req, rsp in pairs:
        if is_num:
            if req.get("cmdType") == int(cmd_type_str):
                matches.append((req, rsp))
        else:
            name = (req.get("cmdTypeName") or "").lower()
            if cmd_type_str.lower() in name:
                matches.append((req, rsp))

    if not matches:
        print(f"No response found for '{cmd_type_str}' in {session_name(f)}", file=sys.stderr)
        sys.exit(1)

    # If multiple matches, use the first one with a non-empty payload;
    # fall back to the very first match.
    best = matches[0]
    for req, rsp in matches:
        p = rsp.get("jsonPayload") or ""
        if p and len(p) > 4:
            best = (req, rsp)
            break

    req, rsp = best
    cmd = req.get("cmdTypeName") or f"CmdType({req.get('cmdType','')})"
    p = rsp.get("jsonPayload")
    # Header to stderr so stdout is clean for piping
    print(f"{session_name(f)}  {cmd}  req={req['seq']} rsp={rsp['seq']}", file=sys.stderr)
    if p:
        try:
            print(json.dumps(json.loads(p), indent=2))
        except Exception:
            print(p)
    else:
        print("{}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: fd-inspect.py <tail|search|show|summary|pairs> [args]")
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "tail":
        cmd_tail(int(sys.argv[2]) if len(sys.argv) > 2 else 20)
    elif cmd == "search":
        cmd_search(sys.argv[2] if len(sys.argv) > 2 else "")
    elif cmd == "show":
        cmd_show(int(sys.argv[2]) if len(sys.argv) > 2 else 0)
    elif cmd == "summary":
        cmd_summary()
    elif cmd == "raw":
        cmd_raw(int(sys.argv[2]) if len(sys.argv) > 2 else 0)
    elif cmd == "pairs":
        cmd_pairs()
    elif cmd == "response":
        if len(sys.argv) < 3:
            print("Usage: fd-inspect.py response <cmdType|name>", file=sys.stderr)
            sys.exit(1)
        cmd_response(sys.argv[2])
    elif cmd == "field":
        if len(sys.argv) < 3:
            print("Usage: fd-inspect.py field <dotted.path> [--by <group.path>]", file=sys.stderr)
            sys.exit(1)
        group_by = None
        if "--by" in sys.argv:
            idx = sys.argv.index("--by")
            group_by = sys.argv[idx + 1] if idx + 1 < len(sys.argv) else None
        cmd_field(sys.argv[2], group_by)
    elif cmd == "events":
        cmd_events(sys.argv[2] if len(sys.argv) > 2 else None)
    elif cmd == "diff":
        if len(sys.argv) < 4:
            print("Usage: fd-inspect.py diff <session1> <session2> [cmdType]", file=sys.stderr)
            sys.exit(1)
        ct = sys.argv[4] if len(sys.argv) > 4 else "624"
        cmd_diff(sys.argv[2], sys.argv[3], ct)
    else:
        print(f"Unknown command: {cmd}")
        sys.exit(1)
