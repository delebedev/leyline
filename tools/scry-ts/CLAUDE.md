# scry-ts

**Before working on this tool, run `just scry-ts --help` to see current commands.** The CLI evolves fast — the help output is the source of truth for what exists.

## Design philosophy

**gh-style CLI.** Noun-verb pattern: `scry <noun> <verb> [args] [--flags]`. LLMs and humans parse it the same way. Every noun has `--help`. Sensible defaults — most commands work with zero flags (last game, current Player.log).

**The accumulator is the core.** Everything — board state, trace, actions, annotations — is a view on accumulated game state. One parser, one accumulator, many lenses.

**Enrich enums, keep trace handles.** Zone types, phases, card types → readable names. instanceIds, grpIds, annotationIds → stay numeric (they're what you grep for, what annotations reference).

**`--json` is the escape hatch.** Human view is enriched and opinionated. `--json` is always raw/lossless. Pipe to jq when you need something the CLI doesn't surface.

**Views, not commands.** When the same data can be presented multiple ways, use `--view` on the existing noun rather than a new command. `gsm list --view turns` not `scry turns`.

## Adding commands

Commands live in `src/commands/<noun>.ts`. Each exports an async function `<noun>Command(args: string[])`. Add to the `commands` map in `cli.ts`.

Follow the pattern:
- First arg is verb (or `--help`)
- Parse flags manually (no framework dependency)
- Use `loadEvents()` or `resolveGame()` for data access
- Use `getResolver()` for card names
- Use `stripPrefix()` / `zoneName()` / `fmtGrp()` from `format.ts`

## Evolving the parser

`parser.ts` extracts events from Player.log lines. To add a new event type:
1. Add to the `LogEvent` union type
2. Add regex + extraction in `parseLog()` generator
3. Existing consumers ignore unknown types (union discrimination)

The parser must never import from commands or the accumulator — it's the foundation everything else builds on.

## Testing

`bun run check` — typecheck + tests in one command. Run after any change.

Tests live next to source (`*.test.ts`). Golden tests in `src/golden.test.ts` replay a reference Brawl game against committed snapshots — catches accumulator/parser regressions. Update golden files after intentional changes: `bun run testdata/update-golden.ts`.

Test the accumulator thoroughly — it's the subtlest code (proto3 merge semantics, id chain tracking). Parser tests use synthetic log lines, not real Player.log data.

## When to use the accumulator vs raw GSMs

- **Raw GSMs** (`gsm.raw`): when you need exactly what the client saw in one state update. Annotations, zone deltas, objects that changed.
- **Accumulated state** (`acc.current`): when you need "what's on the board right now." Full object map, merged zones, life totals.
- **Most commands need raw.** `gsm show`, `gsm list --view annotations`, `trace` all work with raw GSM data. Only `board`, `inspect`, and `game cards` need the accumulator.

## Storage contract

`~/.scry/games/*.log` are **immutable after write**. Enrichment goes in `.meta.json` sidecars. Catalog is a derived index — rebuildable from log files. See README for full layout.

## Analysis defaults

Prefer saved games with `source=real` first. Include `unknown` (pre-provenance saves). Do **not** default to `leyline` / `puzzle` unless the task is explicitly about local-server behavior.
- saved-game queries default to `real,unknown`
- `--source leyline|puzzle|any` is explicit opt-in

## Common pitfalls

**Player.log rotates on Arena launch.** Current → Player-prev.log, new empty file. Use `scry save --all` to capture both before restarting Arena.

**Bare-echo GSMs.** Arena sends content-less GSMs for animation pacing. They show as 0 annotations / 0 objects in `gsm list`. Filter with `--has` to skip them.

**Turn numbers are per-player.** Turn 1 = player 1's first turn, turn 2 = player 2's first turn. `scry game` shows rounds (turns ÷ 2).

**Ability grpIds ≠ card grpIds.** Triggered/activated abilities have their own grpIds not in the Cards table. `board` and `gsm show` resolve them via `objectSourceGrpId` → source card name. `resolveAbility()` in `cards.ts` queries the Abilities table directly.

**Annotations are split across two arrays.** `gsm.raw.annotations` has transient annotations (per-GSM, ephemeral). `gsm.raw.persistentAnnotations` has persistent ones (accumulate across GSMs, deleted via `diffDeletedPersistentAnnotationIds`). Types like `DamagedThisTurn`, `EnteredZoneThisTurn`, `TargetSpec`, `TemporaryPermanent` are persistent. Any code that profiles or scans "all annotations" must check both arrays — `GsmSummary.annotationTypes` already merges them, but `gsm.raw` does not.

**Source filters for the real corpus are `real,unknown` — not just `real`.** Many older saved games have `source=unknown` (pre-provenance saves). Using `--source real` alone misses them. The default `parseSavedSourceFilter([])` returns `["real", "unknown"]`, which is correct. When building `--diff` or manual source sets, always include `unknown` alongside `real`.

**`--diff` source args are comma-separated strings, not separate flags.** `--diff real,unknown leyline` — the first arg is the left source set, second is right. Each is split on `,` internally.

**`gsm.raw.type` vs `gsm.raw.update`.** `type` is the GSM content type (`GameStateType_Full`/`GameStateType_Diff`). `update` is the delivery method (`GameStateUpdate_Send`/`SendHiFi`/`SendAndRecord`). Don't confuse them — they look similar but answer different questions.

## Performance notes

File parsing is synchronous (Bun is fast enough). For a 200K-line Player.log with 6 games, full parse + game detection takes ~50ms. The accumulator adds ~100ms for replay. This is fine for CLI use.

`game search` reads every saved log file sequentially. Will need optimization (index or parallel reads) if the catalog grows past ~50 games.
