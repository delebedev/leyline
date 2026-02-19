# Recording CLI Reference

Practical CLI for reading/comparing Arena proxy captures and nexus engine dumps.

Use this for day-to-day questions:

- what was played in this game?
- who played card X?
- where do two recordings diverge?

## Prerequisites

```bash
cd forge-nexus
just dev-build   # Kotlin-only, ~3-5s (use `just build` only after proto changes)
```

## Recording Sources

- Engine dump: `/tmp/arena-dump`
- Proxy payload capture: `/tmp/arena-capture/payloads`
- Proxy lossless frames: `/tmp/arena-capture/frames`
- Optional grouped sessions: `/tmp/arena-recordings/<session>`

Decoder accepts:

- raw protobuf payload `.bin`
- Arena-framed `.bin` (6-byte header + payload)

## Session Discovery

List discoverable sessions:

```bash
just rec-list
```

Output columns:

1) session id (base64 path)
2) mode (`engine`/`proxy`/`recording`)
3) file count
4) absolute path

You can pass either session id or path to commands below.

## Summary

Compact game summary:

```bash
just rec-summary /tmp/arena-dump
```

Includes:

- seats (player names)
- message count / action count
- turn range
- top played cards
- actions by actor

## Actions Timeline

First N compact actions (PlayLand/CastSpell/Resolve/etc):

```bash
just rec-actions /tmp/arena-dump 20
```

Columns:

- sequence
- turn
- category
- actor
- card (`grp:<id>` fallback if name missing)
- source dump file

Filtered query (positional args):

```bash
just rec-actions-filtered /tmp/arena-dump 75570 "" 20
```

Args are:

1) session
2) card filter (name substring or grpId)
3) actor filter (optional; seat id or player substring)
4) limit

## Who Played X

Direct card-centric query:

```bash
just rec-who-played /tmp/arena-dump "Cut Down"
```

or by grpId:

```bash
just rec-who-played /tmp/arena-dump 75570
```

Returns turn, actor, action kind, gs/msg identifiers.

## Compare Two Recordings

Compact semantic compare:

```bash
just rec-compare /tmp/arena-dump /tmp/arena-capture/payloads
```

Returns:

- left/right action counts
- first divergence index
- left/right action signatures at divergence

Use this to quickly detect parity drift between real Arena traffic and our engine output.

## Deep-Dive Tools (existing)

When compact view is not enough:

- `just proto-inspect <file.bin>`: decode one payload to text
- `just proto-trace <id> [dir]`: trace an instance/object id across payloads
- `just proto-compare <args>`: structural diff/analyze flow
- `just proto-decode-recording <dir> [output]`: decode whole directory to jsonl

Rule of thumb:

- start with `rec-*`
- drop to `proto-*` for field-level forensic work

## Noise-Free Output

`rec-*` commands run with CLI-specific Logback config (`logback-cli.xml`) so output is data-first (no startup INFO wall).

## Troubleshooting

`No recording sessions found`
- confirm paths exist and contain `.bin` files
- run proxy/engine once to generate data

`Session not found or not parseable`
- pass full path directly (faster than id copy/paste)
- verify files are payloads/frames, not unrelated binaries

`No actions found`
- recording may contain setup-only traffic
- increase limit or remove filters
- use `just rec-summary <session>` to confirm message count > 0
