#!/usr/bin/env python3
"""wire — Front Door frame inspection tool."""
import argparse
import json
import glob
import os
import sys
import textwrap
from collections import Counter


# ---------------------------------------------------------------------------
# Session / frame loading
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def cmd_tail(n, session=None):
    f = find_jsonl(session)
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

def cmd_search(term, session=None):
    f = find_jsonl(session)
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

def _find_request_for(frames, frame):
    """For an S2C frame, find the matching C2S request by transactionId."""
    tid = frame.get("transactionId")
    if not tid:
        return None
    for d in frames:
        if d.get("dir") == "C2S" and d.get("transactionId") == tid:
            return d
    return None

def cmd_show(seq_num, session=None):
    f = find_jsonl(session)
    frames = load_frames(f)
    for d in frames:
        if d.get("seq") == seq_num:
            cmd_label = d.get("cmdTypeName") or ""
            if d.get("dir") == "S2C" and not cmd_label:
                req = _find_request_for(frames, d)
                if req:
                    req_cmd = req.get("cmdTypeName") or ""
                    cmd_label = f"response to {req_cmd}" if req_cmd else "response"
            print(f"seq={d['seq']} {d['dir']} {cmd_label} envelope={d.get('envelopeType','')}")
            print(f"transactionId={d.get('transactionId','')}")
            p = d.get("jsonPayload")
            if p:
                try:
                    print(json.dumps(json.loads(p), indent=2))
                except Exception:
                    print(p)
            else:
                print("(no payload)")
            return
    print(f"seq {seq_num} not found")

def _type_hint(val):
    """Return type name and size hint for a JSON value."""
    if val is None:
        return "null", ""
    if isinstance(val, bool):
        return "bool", ""
    if isinstance(val, (int, float)):
        return "number", ""
    if isinstance(val, str):
        return "string", f"({len(val)} chars)"
    if isinstance(val, list):
        n = len(val)
        if n > 0 and isinstance(val[0], dict):
            keys = list(val[0].keys())
            keys_str = ", ".join(keys[:6])
            if len(keys) > 6:
                keys_str += ", ..."
            return "array", f"[{n} items] of object {{{keys_str}}}"
        return "array", f"[{n} items]"
    if isinstance(val, dict):
        keys = list(val.keys())
        keys_str = ", ".join(keys[:8])
        if len(keys) > 8:
            keys_str += ", ..."
        return "object", f"{{{len(keys)} keys: {keys_str}}}"
    return type(val).__name__, ""

def _print_keys_recursive(obj, prefix="", depth=0, max_depth=2):
    """Print keys with types and size hints, recursing into dicts."""
    if not isinstance(obj, dict):
        return
    for key, val in obj.items():
        full_key = f"{prefix}.{key}" if prefix else key
        type_name, hint = _type_hint(val)
        print(f"  {full_key:<40s} {type_name:<8s} {hint}")
        if depth < max_depth and isinstance(val, dict):
            _print_keys_recursive(val, full_key, depth + 1, max_depth)
        elif depth < max_depth and isinstance(val, list) and val and isinstance(val[0], dict):
            _print_keys_recursive(val[0], full_key + "[0]", depth + 1, max_depth)

def cmd_keys(seq_num, session=None):
    f = find_jsonl(session)
    frames = load_frames(f)
    for d in frames:
        if d.get("seq") == seq_num:
            cmd_label = d.get("cmdTypeName") or ""
            if d.get("dir") == "S2C" and not cmd_label:
                req = _find_request_for(frames, d)
                if req:
                    req_cmd = req.get("cmdTypeName") or ""
                    cmd_label = f"response to {req_cmd}" if req_cmd else "response"
            print(f"seq={d['seq']} {d['dir']} {cmd_label}")
            p = d.get("jsonPayload")
            if not p:
                print("(no payload)")
                return
            try:
                payload = json.loads(p)
            except Exception:
                print("(payload is not valid JSON)")
                return
            if not isinstance(payload, dict):
                type_name, hint = _type_hint(payload)
                print(f"  (root)  {type_name}  {hint}")
                return
            print(f"  {'key':<40s} {'type':<8s} size/structure")
            print("  " + "-" * 70)
            _print_keys_recursive(payload)
            return
    print(f"seq {seq_num} not found")

def cmd_raw(seq_num, session=None):
    """Print only the JSON payload for a seq — pipe-friendly for jq."""
    f = find_jsonl(session)
    for d in load_frames(f):
        if d.get("seq") == seq_num:
            p = d.get("jsonPayload")
            if p:
                print(p)
            return
    print(f"seq {seq_num} not found", file=sys.stderr)
    sys.exit(1)

def cmd_summary(session=None):
    f = find_jsonl(session)
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

def cmd_pairs(session=None):
    f = find_jsonl(session)
    print(f"Session: {session_name(f)}")
    pairs = _build_pairs(load_frames(f))
    print(f"Matched {len(pairs)} request/response pairs:")
    print(f"{'req':>5s} {'rsp':>5s} {'cmd':<35s} {'rsp_size':>10s}")
    print("-" * 60)
    for req, rsp in pairs:
        cmd = req.get("cmdTypeName") or req.get("envelopeType", "")
        rp = rsp.get("jsonPayload") or ""
        print(f"{req['seq']:5d} {rsp['seq']:5d} {cmd:<35s} {len(rp):>9d}B")

def _match_cmd(req, cmd_type_str):
    """Check if a request matches a cmdType by number or name substring."""
    if cmd_type_str.isdigit():
        return req.get("cmdType") == int(cmd_type_str)
    return cmd_type_str.lower() in (req.get("cmdTypeName") or "").lower()

def cmd_response(cmd_type_str, session=None):
    """Print the S2C response payload for a given cmdType (by number or name)."""
    f = find_jsonl(session)
    frames = load_frames(f)
    pairs = _build_pairs(frames)

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

    best = matches[0]
    for req, rsp in matches:
        p = rsp.get("jsonPayload") or ""
        if p and len(p) > 4:
            best = (req, rsp)
            break

    req, rsp = best
    cmd = req.get("cmdTypeName") or f"CmdType({req.get('cmdType','')})"
    p = rsp.get("jsonPayload")
    print(f"{session_name(f)}  {cmd}  req={req['seq']} rsp={rsp['seq']}", file=sys.stderr)
    if p:
        try:
            print(json.dumps(json.loads(p), indent=2))
        except Exception:
            print(p)
    else:
        print("{}")

def cmd_response_all(cmd_type_str):
    """Print S2C response payloads for a cmdType across ALL sessions (JSONL to stdout)."""
    found = 0
    for path in find_all_jsonl():
        frames = load_frames(path)
        for req, rsp in _build_pairs(frames):
            if not _match_cmd(req, cmd_type_str):
                continue
            p = rsp.get("jsonPayload") or ""
            if len(p) <= 4:
                continue
            try:
                payload = json.loads(p)
            except Exception:
                continue
            record = {
                "_session": session_name(path),
                "_seq": rsp.get("seq"),
                "_cmd": req.get("cmdTypeName") or f"CmdType({req.get('cmdType','')})",
                "payload": payload,
            }
            print(json.dumps(record, separators=(",", ":")))
            found += 1
            break  # one per session
    print(f"{found} sessions", file=sys.stderr)

def _parse_handled_cmdtypes():
    """Extract CmdType numbers handled in FrontDoorHandler.kt dispatch."""
    import re
    handler = "frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt"
    if not os.path.exists(handler):
        return set()
    handled = set()
    in_dispatch = False
    with open(handler) as f:
        for line in f:
            if "when (cmdType)" in line:
                in_dispatch = True
            elif in_dispatch:
                m = re.match(r"\s+([\d,\s]+)\s*->", line)
                if m:
                    for num in re.findall(r"\d+", m.group(1)):
                        handled.add(int(num))
                elif line.strip().startswith("else"):
                    break
    return handled

def _collect_observed_cmdtypes():
    """Collect all C2S CmdTypes seen across all proxy recordings."""
    observed = Counter()
    for path in find_all_jsonl():
        for d in load_frames(path):
            if d.get("dir") == "C2S" and d.get("cmdType") is not None:
                observed[d["cmdType"]] += 1
    return observed

def _cmdtype_names():
    """Load CmdType name map from FdEnvelope.kt."""
    import re
    envelope = "frontdoor/src/main/kotlin/leyline/frontdoor/wire/FdEnvelope.kt"
    names = {}
    if not os.path.exists(envelope):
        return names
    with open(envelope) as f:
        for line in f:
            m = re.match(r'\s+(\d+)\s+to\s+"([^"]+)"', line)
            if m:
                names[int(m.group(1))] = m.group(2)
    return names

def cmd_coverage(session=None):
    """Show FD CmdType coverage: handled vs observed in recordings."""
    handled = _parse_handled_cmdtypes()
    observed = _collect_observed_cmdtypes()
    names = _cmdtype_names()

    all_types = sorted(handled | set(observed.keys()))
    n_sessions = len(find_all_jsonl())

    print(f"FD CmdType coverage ({len(handled)} handled, {len(observed)} observed, {n_sessions} sessions)")
    print()
    print(f"{'Cmd':>5s}  {'Name':<40s} {'Handled':>7s}  {'Seen':>5s}")
    print("-" * 65)

    missing = []
    for ct in all_types:
        name = names.get(ct, f"Unknown({ct})")
        h = "  yes" if ct in handled else "   —"
        count = observed.get(ct, 0)
        seen = str(count) if count else "—"
        marker = ""
        if ct in observed and ct not in handled:
            marker = "  ← UNHANDLED"
            missing.append((ct, name, count))
        print(f"{ct:5d}  {name:<40s} {h:>7s}  {seen:>5s}{marker}")

    if missing:
        print()
        print(f"Unhandled but observed ({len(missing)}):")
        for ct, name, count in sorted(missing, key=lambda x: -x[2]):
            print(f"  {ct:5d}  {name:<40s}  seen {count}x")

def cmd_since(seq_num, session=None):
    """Print all frames with seq > seq_num."""
    f = find_jsonl(session)
    frames = load_frames(f)
    newer = [d for d in frames if d.get("seq", 0) > seq_num]
    print(f"Session: {session_name(f)} — {len(newer)} frames after seq {seq_num}")
    print(f"{'seq':>5s} {'dir':3s} {'type':8s} {'cmd':<35s} {'size':>8s}  preview")
    print("-" * 110)
    for d in newer:
        seq = d.get("seq", 0)
        dr = d.get("dir", "?")
        et = d.get("envelopeType", "")[:8]
        cmd = d.get("cmdTypeName") or ""
        p = d.get("jsonPayload") or ""
        plen = f"{len(p)}B"
        preview = p[:60].replace("\n", " ") if p else ""
        print(f"{seq:5d} {dr:3s} {et:8s} {cmd:<35s} {plen:>8s}  {preview}")

def _find_arena_db():
    """Locate the Arena card database."""
    import glob as _glob
    home = os.path.expanduser("~")
    pattern = os.path.join(home, "Library", "Application Support",
                           "com.wizards.mtga", "Downloads", "Raw",
                           "Raw_CardDatabase_*.mtga")
    matches = _glob.glob(pattern)
    if matches:
        return matches[0]
    return None

def cmd_cards(seq_num, session=None):
    """Resolve grpId arrays in a frame to card names via Arena SQLite DB."""
    import sqlite3

    db_path = _find_arena_db()
    if not db_path:
        print("Arena card DB not found. Launch Arena at least once to download it.", file=sys.stderr)
        sys.exit(1)

    f = find_jsonl(session)
    frames = load_frames(f)
    target = None
    for d in frames:
        if d.get("seq") == seq_num:
            target = d
            break
    if not target:
        print(f"seq {seq_num} not found")
        return

    p = target.get("jsonPayload")
    if not p:
        print("(no payload)")
        return

    try:
        payload = json.loads(p)
    except Exception:
        print("(payload is not valid JSON)")
        return

    def collect_grpid_arrays(obj, path=""):
        results = []
        if isinstance(obj, dict):
            for k, v in obj.items():
                results.extend(collect_grpid_arrays(v, f"{path}.{k}" if path else k))
        elif isinstance(obj, list) and len(obj) >= 5:
            if all(isinstance(x, int) and x > 10000 for x in obj):
                results.append((path, obj))
            else:
                for i, v in enumerate(obj):
                    if isinstance(v, (dict, list)):
                        results.extend(collect_grpid_arrays(v, f"{path}[{i}]"))
        return results

    grpid_arrays = collect_grpid_arrays(payload)
    if not grpid_arrays:
        print(f"seq={seq_num}: no grpId arrays found (need int arrays of 5+ items > 10000)")
        return

    conn = sqlite3.connect(db_path)
    try:
        for field_path, grp_ids in grpid_arrays:
            unique_ids = list(set(grp_ids))
            placeholders = ",".join("?" * len(unique_ids))
            rows = conn.execute(
                f"SELECT c.GrpId, l.Loc FROM Cards c JOIN Localizations_enUS l ON c.TitleId = l.LocId "
                f"WHERE l.Formatted = 1 AND c.GrpId IN ({placeholders})",
                unique_ids
            ).fetchall()
            name_map = {grp: name for grp, name in rows}

            count_by_name = Counter()
            unknown = 0
            for grp in grp_ids:
                name = name_map.get(grp)
                if name:
                    count_by_name[name] += 1
                else:
                    unknown += 1

            print(f"\n{field_path}: {len(grp_ids)} cards ({len(count_by_name)} unique named, {unknown} unknown)")
            for name, cnt in count_by_name.most_common():
                bar = f"x{cnt}" if cnt > 1 else "  "
                print(f"  {bar:>3s}  {name}")
            if unknown:
                print(f"  ---  {unknown} unknown grpId(s)")
    finally:
        conn.close()

def cmd_flow(session=None):
    """Chronological request→response flow with key payloads expanded."""
    f = find_jsonl(session)
    frames = load_frames(f)
    pairs = _build_pairs(frames)
    print(f"Session: {session_name(f)} ({len(frames)} frames, {len(pairs)} pairs)")
    print()

    paired_tids = set()
    for req, rsp in pairs:
        paired_tids.add(req.get("transactionId"))

    events = []
    for req, rsp in pairs:
        events.append(("pair", req["seq"], req, rsp))
    for d in frames:
        if d["dir"] == "S2C" and d.get("transactionId") not in paired_tids:
            p = d.get("jsonPayload") or ""
            if len(p) > 4:
                events.append(("push", d["seq"], None, d))
    events.sort(key=lambda x: x[1])

    HIGHLIGHT_CMDS = {1700, 1701, 1703, 608, 612}
    HIGHLIGHT_PUSHES = {"MatchCreated", "MatchComplete"}

    for kind, _, req, rsp in events:
        if kind == "push":
            p = rsp.get("jsonPayload") or ""
            try:
                payload = json.loads(p)
            except Exception:
                continue
            push_type = payload.get("Type", "")
            if push_type in HIGHLIGHT_PUSHES or len(p) > 100:
                print(f"  ← PUSH seq={rsp['seq']} Type={push_type or '?'}")
                if push_type == "MatchCreated":
                    mi = payload.get("MatchInfoV3", {})
                    print(f"    matchType={mi.get('MatchType')} event={mi.get('EventId')} seat={mi.get('YourSeat')}")
                    players = mi.get("PlayerInfos", [])
                    for pi in players:
                        print(f"    seat{pi.get('SeatId')}: {pi.get('ScreenName')} avatar={pi.get('CosmeticsSelection',{}).get('Avatar',{}).get('Id','')}")
                    meta = mi.get("ClientMetadata", [])
                    for m in meta:
                        if m.get("Key") in ("BotMatchPlayer", "GraphId", "NodeId") or "GraphId" in m.get("Key", ""):
                            print(f"    meta: {m['Key']}={m['Value']}")
                print()
            continue

        cmd_type = req.get("cmdType")
        cmd_name = req.get("cmdTypeName") or f"CmdType({cmd_type})"
        rsp_payload = rsp.get("jsonPayload") or ""
        req_payload = req.get("jsonPayload") or ""

        if cmd_type not in HIGHLIGHT_CMDS and "Unknown" not in cmd_name:
            rp_len = len(rsp_payload)
            print(f"  {req['seq']:>4d}→{rsp['seq']:<4d} {cmd_name:<35s} {rp_len:>6d}B")
            continue

        print(f"  {req['seq']:>4d}→{rsp['seq']:<4d} ★ {cmd_name}")

        if req_payload:
            try:
                rq = json.loads(req_payload)
                if "GraphId" in rq:
                    nodes = rq.get("NodeIds", [])
                    print(f"    req: GraphId={rq['GraphId']} NodeIds={nodes}")
                elif "EventName" in rq:
                    print(f"    req: EventName={rq['EventName']} MatchId={rq.get('MatchId','')[:8]}")
                else:
                    brief = json.dumps(rq, separators=(",", ":"))
                    if len(brief) > 120:
                        brief = brief[:120] + "..."
                    print(f"    req: {brief}")
            except Exception:
                pass

        if rsp_payload:
            try:
                rp = json.loads(rsp_payload)
                if "NodeStates" in rp:
                    for nid, ns in rp["NodeStates"].items():
                        status = ns.get("Status", "")
                        extras = []
                        if "FamiliarMatchState" in ns:
                            fms = ns["FamiliarMatchState"]
                            if fms:
                                extras.append(f"matches={fms.get('MatchesPlayed','?')}")
                        if "QueueMatchState" in ns:
                            extras.append("queue")
                        extra = f" ({', '.join(extras)})" if extras else ""
                        print(f"    {nid}: {status}{extra}")
                elif "GraphStates" in rp:
                    for gid, gs in rp["GraphStates"].items():
                        nodes = gs.get("NodeStates", {})
                        print(f"    GraphStates.{gid}: {len(nodes)} nodes")
                        for nid, ns in nodes.items():
                            status = ns.get("Status", "")
                            fms = ns.get("FamiliarMatchState", {})
                            extra = f" (matches={fms['MatchesPlayed']})" if fms.get("MatchesPlayed") else ""
                            print(f"      {nid}: {status}{extra}")
                elif "FoundMatch" in rp:
                    print(f"    FoundMatch={rp['FoundMatch']}")
                elif "GraphDefinitions" in rp:
                    defs = rp["GraphDefinitions"]
                    if isinstance(defs, list):
                        names = [g.get("Id", "?") for g in defs]
                    else:
                        names = list(defs.keys())
                    print(f"    {len(names)} graphs: {', '.join(names)}")
                else:
                    brief = json.dumps(rp, separators=(",", ":"))
                    if len(brief) > 150:
                        brief = brief[:150] + "..."
                    print(f"    rsp: {brief}")
            except Exception:
                print(f"    rsp: {len(rsp_payload)}B")
        print()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        prog="wire",
        description="Front Door frame inspection tool.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""\
            examples:
              wire tail              last 20 FD frames
              wire tail 50           last 50
              wire search Deck       search payloads for "Deck"
              wire show 42           full payload for seq 42
              wire summary           cmdType breakdown
              wire pairs             matched request/response pairs
              wire response Deck     response payload for a cmdType
              wire flow              chronological request→response flow
              wire coverage          handled vs observed CmdTypes
              wire keys 42           payload key structure for seq 42
              wire since 100         frames after seq 100
              wire cards 42          resolve grpId arrays to card names
        """),
    )
    # session flag on parser level (inherited by subcommands that use it)
    subs = parser.add_subparsers(dest="command")

    p = subs.add_parser("tail", help="Last N frames (default 20)")
    p.add_argument("n", nargs="?", type=int, default=20)
    p.add_argument("-s", "--session", help="Session name or substring")

    p = subs.add_parser("search", help="Search payloads and cmdTypes")
    p.add_argument("term")
    p.add_argument("-s", "--session", help="Session name or substring")

    p = subs.add_parser("show", help="Full payload for a seq number")
    p.add_argument("seq", type=int)
    p.add_argument("-s", "--session", help="Session name or substring")

    p = subs.add_parser("raw", help="Raw JSON payload for a seq (pipe to jq)")
    p.add_argument("seq", type=int)
    p.add_argument("-s", "--session", help="Session name or substring")

    p = subs.add_parser("summary", help="CmdType breakdown (C2S vs S2C)")
    p.add_argument("-s", "--session", help="Session name or substring")

    p = subs.add_parser("pairs", help="Matched request/response pairs")
    p.add_argument("-s", "--session", help="Session name or substring")

    p = subs.add_parser("response", help="Response payload for a cmdType")
    p.add_argument("cmd", help="CmdType number or name substring")
    p.add_argument("-s", "--session", help="Session name or substring")

    p = subs.add_parser("response-all", help="Response for a cmdType across ALL sessions (JSONL)")
    p.add_argument("cmd", help="CmdType number or name substring")

    p = subs.add_parser("coverage", help="CmdType coverage: handled vs observed")

    p = subs.add_parser("keys", help="Payload key structure for a seq")
    p.add_argument("seq", type=int)
    p.add_argument("-s", "--session", help="Session name or substring")

    p = subs.add_parser("since", help="Frames after a given seq number")
    p.add_argument("seq", type=int)
    p.add_argument("-s", "--session", help="Session name or substring")

    p = subs.add_parser("cards", help="Resolve grpId arrays to card names")
    p.add_argument("seq", type=int)
    p.add_argument("-s", "--session", help="Session name or substring")

    p = subs.add_parser("flow", help="Chronological request→response flow")
    p.add_argument("-s", "--session", help="Session name or substring")

    args = parser.parse_args()
    if args.command is None:
        parser.print_help()
        sys.exit(0)

    session = getattr(args, "session", None)

    if args.command == "tail":
        cmd_tail(args.n, session)
    elif args.command == "search":
        cmd_search(args.term, session)
    elif args.command == "show":
        cmd_show(args.seq, session)
    elif args.command == "raw":
        cmd_raw(args.seq, session)
    elif args.command == "summary":
        cmd_summary(session)
    elif args.command == "pairs":
        cmd_pairs(session)
    elif args.command == "response":
        cmd_response(args.cmd, session)
    elif args.command == "response-all":
        cmd_response_all(args.cmd)
    elif args.command == "coverage":
        cmd_coverage()
    elif args.command == "keys":
        cmd_keys(args.seq, session)
    elif args.command == "since":
        cmd_since(args.seq, session)
    elif args.command == "cards":
        cmd_cards(args.seq, session)
    elif args.command == "flow":
        cmd_flow(session)


if __name__ == "__main__":
    main()
