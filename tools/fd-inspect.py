#!/usr/bin/env python3
"""FD frame inspection tool. Used by just fd-* commands."""
import sys
import json
from collections import Counter

def find_jsonl():
    import glob
    files = sorted(glob.glob("recordings/*/capture/fd-frames.jsonl"), reverse=True)
    if not files:
        print("No fd-frames.jsonl found. Run: just serve-proxy", file=sys.stderr)
        sys.exit(1)
    return files[0]

def session_name(path):
    import os
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

def cmd_pairs():
    f = find_jsonl()
    print(f"Session: {session_name(f)}")
    reqs = {}
    pairs = []
    for d in load_frames(f):
        tid = d.get("transactionId")
        if not tid:
            continue
        if d["dir"] == "C2S":
            reqs[tid] = d
        elif tid in reqs:
            req = reqs.pop(tid)
            rp = d.get("jsonPayload") or ""
            pairs.append((req, d, len(rp)))
    print(f"Matched {len(pairs)} request/response pairs:")
    print(f"{'req':>5s} {'rsp':>5s} {'cmd':<35s} {'rsp_size':>10s}")
    print("-" * 60)
    for req, rsp, sz in pairs:
        cmd = req.get("cmdTypeName") or req.get("envelopeType", "")
        print(f"{req['seq']:5d} {rsp['seq']:5d} {cmd:<35s} {sz:>9d}B")

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
    else:
        print(f"Unknown command: {cmd}")
        sys.exit(1)
