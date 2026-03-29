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

`bun test` — fast, no build step. Tests live next to source (`*.test.ts`).

Test the accumulator thoroughly — it's the subtlest code (proto3 merge semantics, id chain tracking). Parser tests use synthetic log lines, not real Player.log data.

## When to use the accumulator vs raw GSMs

- **Raw GSMs** (`gsm.raw`): when you need exactly what the client saw in one state update. Annotations, zone deltas, objects that changed.
- **Accumulated state** (`acc.current`): when you need "what's on the board right now." Full object map, merged zones, life totals.
- **Most commands need raw.** `gsm show`, `gsm list --view annotations`, `trace` all work with raw GSM data. Only `board` and `game cards` need the accumulator.

## Storage contract

`~/.scry/games/*.log` are **immutable after write**. Raw log slices, never modified. Enrichment goes in `.meta.json` sidecars. The catalog is a derived index — if it gets corrupted, it can be rebuilt from the log files.

## Card resolution

Arena's SQLite DB is the only external dependency. It lives with the game client installation. Never copy or bundle it. Resolve at save time, cache in `.meta.json`, so queries work even if Arena is uninstalled later.

## Performance notes

File parsing is synchronous (Bun is fast enough). For a 200K-line Player.log with 6 games, full parse + game detection takes ~50ms. The accumulator adds ~100ms for replay. This is fine for CLI use.

`game search` reads every saved log file sequentially. Will need optimization (index or parallel reads) if the catalog grows past ~50 games.
