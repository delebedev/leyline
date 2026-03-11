# Tooling Vision: tape, wire, arena, scry

Design spec for reorganizing leyline's developer tooling into self-contained, discoverable tool families with gh-style ergonomics.

## Problem

48 just recipes, 18 arena subcommands, 13 Kotlin modules, 3 Python analyzers, 6 playbooks — all powerful, none discoverable. Agents waste 30s+ per session grepping docs to find the right command. Recording/protocol tools are the most powerful and least findable. `bin/` is a junk drawer. `tooling/debug/` mixes live server infrastructure with offline CLI tools.

## Vision

Four self-contained tools, each owning its source, binaries, tests, and docs:

| Tool | Purpose | Character |
|---|---|---|
| **`tape`** | Recording analysis, proto inspection, conformance | Leyline dev tool. Knows about Forge, recordings, wire format. |
| **`wire`** | Front Door frame inspection | Leyline dev tool. FD protocol layer. |
| **`arena`** | MTGA UI automation | **Standalone.** Black-box game client automation. No leyline knowledge. |
| **`scry`** | Game state from Player.log | **Standalone.** Log parser + accumulator. No leyline knowledge. |

arena and scry treat the game as a black box — logs, OCR, clicks. They work against real servers too. An **enrichment hook** optionally layers in richer data (debug API today, forge oracle tomorrow).

tape and wire are leyline-specific — they know about recordings, protos, the conformance pipeline, Forge.

A fifth thing — the **agent harness** (record-game, autoplay batch system) — is an experiment that consumes arena+scry. It lives in `mtga-internals`, not in leyline.

## Directory Structure

```
tools/
  arena/
    arena.py              # entry point (current bin/arena.py)
    screens.py            # screen state machine (current arena_screens.py)
    record.py             # screen recording (current arena-record)
    annotate.py           # screenshot annotation (current arena-annotate)
    native/               # compiled Swift binaries
      click
      ocr
      window-bounds
    swift/                # Swift source
      click.swift
      ocr.swift
      detect.swift
      window-bounds.swift
    models/               # ML models (gitignored)
      card_detector.mlmodel
    tests/
      test_arena.py
      test_scry.py        # or in scry/tests/
    docs/
      cli.md              # ← docs/arena-cli.md
      nav.md              # ← docs/arena-nav.md
      zone-aware-ocr.md   # ← docs/autoplay/zone-aware-ocr.md

  scry/
    cli.py                # entry point
    lib/                  # accumulator, tracker, annotations, etc.
      accumulator.py
      tracker.py
      annotations.py
      cards.py
      errors.py
      models.py
      parser.py
      server.py
      tail.py
    tests/
    docs/

  tape/
    tape.py               # entry point — Python CLI
    segments.py            # ← tooling/scripts/md-segments.py
    annotation_ids.py      # ← tooling/scripts/annotation-ids.py
    docs/
      recording-cli.md     # ← docs/recording-cli.md
      conformance.md       # ← docs/conformance-pipeline.md
      levers.md            # ← docs/conformance-levers.md

  wire/
    wire.py               # entry point — ← tooling/scripts/fd-inspect.py
    docs/

bin/                      # thin wrappers only (4 one-liners)
  arena
  scry
  tape
  wire
```

Each tool is self-contained: source, tests, docs all co-located. When tape grows conformance features, the docs grow with it.

## tape — the big one

### Design Principles

- **gh-style `noun verb`** — `tape session list`, `tape proto decode`, `tape annotation variance`
- **Smart defaults** — no session arg = latest. No flags for the common case.
- **Human-readable by default, `--json` for scripting** — every command.
- **`--help` with examples** — not flag soup. Each noun group shows what it does.
- **Python reads jsonl directly** — no JVM for basic inspection. Kotlin shim only for proto binary decoding, conformance engine runs, annotation variance (needs protobuf classes).

### Command Map

Replaces 43 just recipes with one discoverable CLI.

#### Sessions (replaces `rec-*` recipes)

```
tape session list                        # ← just rec-list
tape session show [SESSION]              # ← just rec-summary (default: latest)
tape session actions [SESSION] [--card X] [--actor Y]
                                         # ← just rec-actions, rec-actions-filtered
tape session turns [SESSION]             # ← just rec-turninfo
tape session cards [SESSION]             # ← just cards-in-session
tape session find <keyword>              # ← just rec-find
tape session compare <A> <B>             # ← just rec-compare
tape session mechanics                   # ← just rec-mechanics
tape session latest                      # ← just rec-latest
tape session analyze [SESSION] [--force] # ← just rec-analyze
tape session violations [SESSION]        # ← just rec-violations
```

#### Proto (replaces `proto-*` recipes)

```
tape proto decode <file|dir>             # ← just proto-decode, proto-inspect, proto-decode-recording
tape proto trace <id> [SESSION]          # ← just proto-trace
tape proto accumulate [SESSION]          # ← just proto-accumulate
tape proto priority [SESSION]            # ← just proto-priority
tape proto diff-prep                     # ← just proto-diff-prep
tape proto diff                          # ← just proto-diff
tape proto compare [ARGS]                # ← just proto-compare
tape proto extract [--name X]            # ← just proto-extract
```

#### Annotations (replaces `ann-id-*` + `proto-annotation-*` + `rec-annotation-*`)

```
tape annotation variance                 # ← just proto-annotation-variance
tape annotation contract <TYPE> [SESSION] [EFFECT_ID]
                                         # ← just rec-annotation-contract
tape annotation ranges [SESSION]         # ← just ann-id-ranges
tape annotation analyze [SESSION]        # ← just ann-id-analyze
tape annotation detail <TYPE> [SESSION]  # ← just ann-id-detail
```

#### Segments (NEW — conformance pipeline)

```
tape segment list [SESSION]              # list extractable interaction segments
tape segment extract <SESSION> <RANGE>   # mine bounded interaction from recording
tape segment template <FILE>             # templatize: replace instanceIds with $slots
```

#### Conformance (NEW — closed-loop comparison)

```
tape conform run <TEMPLATE> <ENGINE_OUTPUT>  # bind + hydrate + diff
tape conform index                           # overall conformance score
```

#### FD decode (replaces `decode-golden`)

```
tape fd decode [DIR]                     # ← just decode-golden
```

### Implementation: Python + Kotlin Backend

tape.py handles:
- All jsonl reading (session list, show, actions, turns, cards, find, compare, latest)
- Segment extraction and templatization (md-segments.py logic)
- Annotation ID analysis (annotation-ids.py logic)
- Human-friendly formatting, `--json` output

tape.py shells out to `java -cp` for:
- `tape proto decode/trace/accumulate/priority` — needs protobuf Java classes
- `tape annotation variance/contract` — needs Kotlin analysis code
- `tape conform run` — needs engine + binding logic
- `tape session analyze/violations/mechanics` — needs SessionAnalyzer

The Kotlin code stays in `tooling/` as a Gradle module. tape calls it; users never see `java -cp` directly.

## wire

Promoted from `tooling/scripts/fd-inspect.py`. Already a clean Python CLI — just needs a proper entry point and `--help`.

```
wire tail [N]                            # ← just fd-tail (default: 20)
wire search <TERM>                       # ← just fd-search
wire show <SEQ>                          # ← just fd-show
wire show <SEQ> --json                   # ← just fd-raw
wire summary                             # ← just fd-summary
wire pairs                               # ← just fd-pairs
wire response <CMD>                      # ← just fd-response
wire response <CMD> --all                # ← just fd-response-all
wire coverage                            # ← just fd-coverage
wire keys <SEQ>                          # ← just fd-keys
wire since <SEQ>                         # ← just fd-since
wire cards <SEQ>                         # ← just fd-cards
wire flow                                # ← just fd-flow
```

Pure Python. No Kotlin dependency. Reads `fd-frames.jsonl` directly.

## arena — enrichment hook

arena and scry are standalone black-box tools. They work without leyline running. But when richer data is available, an enrichment hook layers it in.

### Current State

`arena state`, `arena board` already call the debug API on `:8090`. This is hardcoded enrichment — it either works or errors.

### Target: Provider Interface

```python
# In arena.py — enrichment is optional
class GameStateProvider:
    """Source of enriched game state beyond OCR/logs."""
    def board(self) -> dict | None: ...
    def hand(self) -> list[dict] | None: ...
    def best_play(self) -> dict | None: ...

class DebugApiProvider(GameStateProvider):
    """Current: leyline debug API on :8090"""

class ForgeOracleProvider(GameStateProvider):
    """Future: direct forge AI query"""

class LogOnlyProvider(GameStateProvider):
    """Fallback: everything returns None, arena uses OCR/logs only"""
```

arena auto-detects: if `:8090` responds, use `DebugApiProvider`. Otherwise fall back to `LogOnlyProvider`. No `--enrich` flag needed — it just works or degrades gracefully.

`arena board` with enrichment: exact hand contents, instanceIds, life totals, phase, graveyard. Without: OCR text + zone estimation.

`arena play` with enrichment: looks up card by name in debug API, gets instanceId + coordinates. Without: OCR scan for card name, estimate position from arc geometry.

## tooling/ Gradle Module — What Stays

After tape and wire absorb the CLI-facing code, `tooling/` contains:

### Server-side (runtime, runs inside leyline process)
- `debug/DebugServer.kt` — :8090 HTTP server (866 LOC)
- `debug/GameStateCollector.kt` — live game state snapshots
- `debug/DebugCollector.kt` — message collector
- `debug/FdDebugCollector.kt` — FD message collector
- `debug/SessionRecorder.kt` — capture sessions to disk

### tape's Kotlin backend (offline, called via `java -cp`)
- `debug/RecordingCli.kt` — session list/show/actions/compare
- `debug/Trace.kt` — ID tracing across payloads
- `debug/Inspect.kt` — proto binary inspection
- `debug/DecodeCapture.kt` — MD payload decoding
- `debug/DecodeFdCapture.kt` — FD raw frame decoding
- `debug/AnnotationVariance.kt` — cross-session variance
- `recording/` — RecordingDecoder, SemanticTimeline, PriorityTimeline, etc.
- `analysis/` — SessionAnalyzer, AnnotationContract, MechanicClassifier
- `conformance/` — CompareMain, GameFlowAnalyzer, RecordingParser

### Unrelated infrastructure
- `cli/SeedDb.kt` — DB initialization

The `debug/` package split (server-side vs offline CLI) should eventually become two packages, but that's a refactor, not a blocker.

## Legacy Script Disposition

| Script | Destination |
|---|---|
| `bin/arena.py` | → `tools/arena/arena.py` |
| `bin/arena_screens.py` | → `tools/arena/screens.py` |
| `bin/arena-record` | → `tools/arena/record.py` |
| `bin/arena-annotate` | → `tools/arena/annotate.py` |
| `bin/scry` + `bin/scry_lib/` | → `tools/scry/` |
| `bin/record-game` | → `mtga-internals` (agent harness) |
| `bin/client-auto.sh` | → trash (dead, replaced by `arena launch`) |
| `bin/test_arena_scry.py` | → `tools/arena/tests/` |
| `tooling/scripts/fd-inspect.py` | → `tools/wire/wire.py` |
| `tooling/scripts/md-segments.py` | → `tools/tape/segments.py` |
| `tooling/scripts/annotation-ids.py` | → `tools/tape/annotation_ids.py` |

## just Recipes

Recipes stay as thin wrappers for muscle memory, but are no longer the primary interface. They delegate to `tape`/`wire`:

```just
# recording.just — thin wrappers around tape
rec-list: tape session list
rec-summary session: tape session show "{{session}}"
# ...etc
```

Eventually the just wrappers can be deprecated as everyone learns `tape`/`wire`. No rush.

## Doc Migration

Docs travel with their tools:

| Current location | New location |
|---|---|
| `docs/arena-cli.md` | `tools/arena/docs/cli.md` |
| `docs/arena-nav.md` | `tools/arena/docs/nav.md` |
| `docs/recording-cli.md` | `tools/tape/docs/cli.md` |
| `docs/conformance-pipeline.md` | `tools/tape/docs/conformance.md` |
| `docs/conformance-levers.md` | `tools/tape/docs/levers.md` |
| `docs/autoplay/zone-aware-ocr.md` | `tools/arena/docs/zone-aware-ocr.md` |
| `docs/autoplay/forge-mode-notes.md` | `tools/arena/docs/forge-mode-notes.md` |
| `docs/autoplay/` (rest) | → `mtga-internals` (agent harness docs) |
| `docs/retro/autoplay/` | → `mtga-internals` (batch results) |
| `docs/debug-api.md` | stays in `docs/` (server-side reference) |

Top-level `docs/` keeps: architecture, rosetta, catalog, wire-format, playbooks, plans, decisions, field-notes — project-level reference that isn't tool-specific.

## Discoverability

After the reorg, an agent (or human) finds tools through:

1. **`tape --help`** / **`wire --help`** — noun groups with one-line descriptions and examples
2. **`tools/*/docs/`** — deep reference travels with each tool
3. **`docs/index.md`** — refreshed to include tool entry points
4. **CLAUDE.md quick reference** — updated with `tape`, `wire` alongside existing `arena`, `scry`, `just card`

No RAG needed. 4 entry points, self-documenting, docs co-located.

## Implementation Order

1. **`wire`** — smallest, already 90% done (rename fd-inspect.py, add `--help`, create `tools/wire/`)
2. **`tape` skeleton** — entry point + session subcommands (pure Python, reads jsonl)
3. **`tape proto`** — wire up Kotlin shims
4. **`tape annotation`** — absorb annotation-ids.py
5. **`tape segment` + `tape conform`** — new conformance commands
6. **`tools/arena/` reorg** — move files, fix imports, update wrappers
7. **`tools/scry/` reorg** — move files, fix imports
8. **Doc migration** — move docs to tool dirs, refresh index.md
9. **Legacy cleanup** — trash dead scripts, move agent harness to mtga-internals
10. **just recipe thin wrappers** — point existing recipes at new tools

Steps 1-3 are the high-value core. Steps 6-10 are mechanical cleanup.

## Import Path Mechanics

Thin bin wrappers set `sys.path` and delegate:

```python
#!/usr/bin/env python3
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'tools', 'tape'))
from tape import main
main()
```

arena's native binaries resolve relative to `__file__`:

```python
NATIVE_DIR = os.path.join(os.path.dirname(__file__), 'native')
OCR_BIN = os.path.join(NATIVE_DIR, 'ocr')
```

No pip, no pyproject.toml, no virtualenvs. Direct script execution, relative paths.

## Out of Scope

- **Card lookup recipes** (`just card`, `card-grp`, `ability`, `card-script`) — stay as just recipes. They're Kotlin one-liners that query the Arena SQLite DB. Stable, low-friction, no discoverability problem. Not worth wrapping.
- **`.claude/skills/`** — agent skills reference tools by name. After the reorg, skills that reference `bin/arena` or just recipes need path updates. This is mechanical — do it per-skill as part of step 8 (doc migration), not as a separate effort.
- **`just/test.just`**, **`just/certs.just`**, **`just/java.just`** — build/test/infra recipes. Not user-facing tooling. Stay as-is.

## Non-Goals

- No new Gradle modules. tape calls existing `tooling/` classes via `java -cp`.
- No Python package management (pip, pyproject.toml). Scripts run directly.
- No breaking existing just recipes. They become wrappers, not deleted.
- No refactoring Kotlin `debug/` package split (server vs CLI). Do later.
- No RAG or embedding system. 4 CLI entry points + co-located docs is enough.
