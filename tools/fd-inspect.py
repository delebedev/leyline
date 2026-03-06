#!/usr/bin/env python3
"""FD frame inspection tool. Used by just fd-* commands."""
import sys
import json
import glob
import os
from collections import Counter

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

def _match_cmd(req, cmd_type_str):
    """Check if a request matches a cmdType by number or name substring."""
    if cmd_type_str.isdigit():
        return req.get("cmdType") == int(cmd_type_str)
    return cmd_type_str.lower() in (req.get("cmdTypeName") or "").lower()

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
            # wrap with session metadata
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
    handler = "src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt"
    if not os.path.exists(handler):
        return set()
    handled = set()
    in_dispatch = False
    with open(handler) as f:
        for line in f:
            if "when (cmdType)" in line:
                in_dispatch = True
            elif in_dispatch:
                # match "  123 -> " or "  123, 456 -> "
                m = re.match(r"\s+([\d,\s]+)\s*->", line)
                if m:
                    for num in re.findall(r"\d+", m.group(1)):
                        handled.add(int(num))
                elif line.strip().startswith("else"):
                    break
    return handled

def _collect_observed_cmdtypes():
    """Collect all C2S CmdTypes seen across all proxy recordings."""
    observed = Counter()  # cmdType -> total count across sessions
    for path in find_all_jsonl():
        for d in load_frames(path):
            if d.get("dir") == "C2S" and d.get("cmdType") is not None:
                observed[d["cmdType"]] += 1
    return observed

def _cmdtype_names():
    """Load CmdType name map from FdEnvelope.kt."""
    import re
    envelope = "src/main/kotlin/leyline/protocol/FdEnvelope.kt"
    names = {}
    if not os.path.exists(envelope):
        return names
    with open(envelope) as f:
        for line in f:
            m = re.match(r'\s+(\d+)\s+to\s+"([^"]+)"', line)
            if m:
                names[int(m.group(1))] = m.group(2)
    return names

def cmd_coverage():
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
    elif cmd == "response-all":
        if len(sys.argv) < 3:
            print("Usage: fd-inspect.py response-all <cmdType|name>", file=sys.stderr)
            sys.exit(1)
        cmd_response_all(sys.argv[2])
    elif cmd == "coverage":
        cmd_coverage()
    else:
        print(f"Unknown command: {cmd}")
        sys.exit(1)
