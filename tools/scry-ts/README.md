# scry

MTGA Player.log parser and game state tool. Reads Arena's Player.log, accumulates game state, and provides query commands for game analysis.

Works with any MTGA Player.log — yours, a contributor's, a bug report.

## Setup

Requires [Bun](https://bun.sh) (1.3+).

```bash
# From the repo root
just scry-ts --help

# Or directly
cd tools/scry-ts && bun run cli.ts --help
```

Card name resolution requires a local MTGA installation (reads the Arena SQLite card database from `~/Library/Application Support/com.wizards.mtga/Downloads/Raw/`). Everything else works without it.

## Commands

### `scry board`

Accumulated board state at end of game (or any point via `--gsid`).

```bash
scry board                    # last game, final state
scry board --gsid 50          # time travel to gsId 50
scry board --game 3           # different game
scry board --json             # raw accumulated state
```

### `scry game`

Game-level summaries, card manifests, notes.

```bash
scry game                     # last game detail + annotation histogram
scry game list                # all games in current Player.log
scry game list --saved        # all games in catalog (~/.scry/games/)
scry game list --saved --source puzzle   # only saved puzzle runs
scry game 3                   # specific game
scry game cards               # card manifest (cached in .meta.json)
scry game cards 2026-03-29    # specific saved game
scry game search "Giada"      # search card names, notes, tags, raw logs
scry game search "Giada" --source any    # include leyline + puzzle runs too
scry game notes               # view anchored notes
```

### `scry gsm`

Query game state messages with filtering and multiple views.

```bash
scry gsm list                           # all GSMs in last game
scry gsm list --has DamageDealt         # filter by annotation type
scry gsm list --has DamageDealt --has ZoneTransfer  # multiple filters (AND)
scry gsm list --view turns              # phase/step timeline
scry gsm list --view actions            # action timeline (CastSpell, PlayLand, etc.)
scry gsm list --view annotations        # full annotation bodies with details
scry gsm show 292                       # drill into a specific GSM
scry gsm show 292 --json                # raw JSON (lossless)
```

### `scry sequences`

Extract canonical GSM bracketing patterns from saved game logs. Classifies each GSM by role (CAST, ECHO, RESOLVE, etc.), detects multi-GSM interaction instances, and aggregates across games.

```bash
scry sequences                            # all saved games (real+unknown)
scry sequences --game 2026-03-30          # single game
scry sequences --type targeted_spell      # filter interaction type
scry sequences --json                     # machine-readable output
scry sequences --source any               # include leyline/puzzle runs
scry sequences --debug                    # show per-game classification
```

### `scry trace`

Follow a card's full lifecycle through a game — zone transfers, annotations, instance ID changes.

```bash
scry trace "Giada"                      # by card name
scry trace 365                          # by instanceId
scry trace "Hydra" --game 2026-03-29    # specific saved game
```

### `scry lobby`

Lobby (Front Door) request/response pairs.

```bash
scry lobby list                         # all FD pairs
scry lobby show EventAiBotMatch         # by name (last occurrence)
scry lobby show 52                      # by index
scry lobby show abc-123                 # by transaction id prefix
scry lobby search "Deck"               # search payloads
scry lobby show 6 --json               # raw request + response
```

### `scry save`

Save games from Player.log to durable storage (`~/.scry/games/`). Idempotent — safe to run multiple times. Auto-resolves card names at save time.

```bash
scry save                     # save finished games from Player.log
scry save --all               # include Player-prev.log
scry save --dry-run           # preview without saving
```

Saved games also get provenance in `.meta.json`:
- `real` for normal Arena games
- `leyline` for local server games
- `puzzle` for local puzzle runs
- `unknown` for legacy/unclassified saves

Saved-game analysis defaults to `real,unknown`. Use `--source leyline`, `--source puzzle`, or `--source any` only when you're intentionally investigating local-server behavior.

### `scry note`

Add timestamped notes anchored to specific game moments.

```bash
scry note "aura didn't reattach"                    # anchors to last GSM
scry note "combat damage wrong" --gsid 292           # specific moment
scry note "test deck" --game 2026-03-29_16-32        # specific game
```

### `scry events`

Quick overview of what's in a Player.log file.

```bash
scry events                   # default Player.log
scry events /path/to/file     # specific file
```

### `scry usage`

Command invocation heatmap from `~/.scry/usage.log`.

```bash
scry usage
```

## Storage

```
~/.scry/
  games/
    2026-03-29_16-10-08.log         # raw Player.log slice (verbatim, lossless)
    2026-03-29_16-10-08.meta.json   # enrichment: cards, notes, tags, provenance
  catalog.json                      # game index (result, turns, matchId)
  leyline-sessions.jsonl            # optional local-server provenance journal
  usage.log                         # command invocation telemetry
```

Raw log files are never modified after save. Enrichment (cards, notes, provenance) lives in `.meta.json` sidecars. The catalog is a lightweight index for listing without re-parsing.
Leyline writes optional session records to `~/.scry/leyline-sessions.jsonl`; `scry save` joins those onto saved games by `matchId` and records explicit provenance. If no Leyline session matches, the save falls back to `real`/`inferred`.
Saved-game queries default to `real,unknown` sources; pass `--source any` or `--all` to include local Leyline and puzzle runs.

## Architecture

```
cli.ts                  # entry point — noun-verb dispatch, usage logging
src/
  parser.ts             # Player.log → LogEvent stream (GRE, FD, scene, error)
  accumulator.ts        # full/diff GSM merge, ObjectIdChanged chain tracking
  games.ts              # game boundary detection (ConnectResp → game over)
  cards.ts              # grpId → card name via Arena SQLite (bun:sqlite)
  catalog.ts            # durable game storage + catalog index
  meta.ts               # per-game metadata (cards, notes, tags)
  slicer.ts             # line-range extraction for raw log slicing
  resolve.ts            # unified game resolution (live log + catalog)
  format.ts             # shared formatting (zone names, card names, enums)
  log.ts                # shared log loading
  commands/             # one file per command noun
```

### Key design decisions

**Accumulator uses whole-object replacement.** Proto3 semantics: absent fields in a Diff object mean default (false/0/""), not "unchanged". Field-by-field merge would incorrectly preserve stale values.

**ObjectIdChanged chain tracking.** When a card changes zones, Arena assigns a new instanceId. The accumulator maintains forward (old→new) and backward (new→old) maps so `scry trace` can follow a card across its entire lifecycle.

**Raw log slices are lossless.** Saved games are verbatim Player.log lines — re-parseable with future parser improvements. Enrichment is separate.

**Card resolution is cached.** The Arena SQLite DB may be updated or unavailable. Card names are resolved once at save time and cached in `.meta.json`.

## Tests

```bash
cd tools/scry-ts && bun test
```

27 tests across parser, game detection, and accumulator.

## Gotchas

- **Player.log rotates on Arena launch.** Current → Player-prev.log, new empty file. Use `scry save --all` to capture both before restarting Arena.
- **Bare-echo GSMs.** Arena sends content-less GSMs for animation pacing. They show as 0 annotations / 0 objects in `gsm list`. Filter with `--has` to skip them.
- **Turn numbers are per-player.** Turn 1 = player 1's first turn, turn 2 = player 2's first turn. `scry game` shows rounds (turns ÷ 2) for clarity.
- **Ability grpIds ≠ card grpIds.** Triggered/activated abilities have their own grpIds that aren't in the Arena card DB. The card manifest filters these out.
- **`--json` is always lossless.** The human-readable format strips prefixes and enriches enums. `--json` gives the raw data exactly as Player.log recorded it.
