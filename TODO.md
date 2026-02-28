# TODO

## Slow integration tests

Top 5 tests eating most of the ~475s integration budget:

| Test | Time | Why slow |
|------|------|----------|
| `GameEndTest.lethalDamageProducesMatchCompleted` | 63s | Plays full game to lethal — multi-turn AI loop |
| `BlockerDeclarationTest.tradeProducesCreatureDeaths` | 42s | Multi-turn setup to get creatures + combat resolution |
| `BlockerDeclarationTest.humanBlocksAiAttacker` | 41s | Same: needs board state with attackers/blockers |
| `BlockerDeclarationTest.humanDeclinesBlockingTakesDamage` | 32s | Same blocker setup overhead |
| `DeclareBlockersDedupeTest.noDuplicateBlockersReq` | 22s | Reaches declare-blockers step |

Root cause is shared: reaching combat with creatures on both sides requires multi-turn setup through the full engine loop. Options:
- Card injection (`CardInjectionTest` pattern) to start with creatures already on battlefield
- Scripted AI controller to skip AI think time
- Dedicated "combat-ready" seed/fixture that starts at a board state closer to combat

## Coverage: include integration tests

`just coverage` currently runs unit+conformance only. Integration tests should be included for fuller coverage but they dump ~7min of noisy engine stderr (bridge timeouts, stack traces). `just test-integration` has the same noise problem. Fix: suppress engine log noise in test runs (test-specific logback config with WARN→ERROR for bridge/timeout loggers), then add integration back to coverage.

## Split justfile into delegated groups

The root justfile delegates `fmt`/`fmt-check` to per-module justfiles — extend this pattern to more target groups. The nexus justfile is 360+ LOC with serve/proto/recording/cert targets all in one file.

Candidates:
- `proto.just` — proto-inspect, proto-decode, proto-diff, proto-compare, proto-trace, proto-extract, proto-accumulate
- `recording.just` — rec-list, rec-summary, rec-actions, rec-compare, rec-analyze, rec-violations, rec-mechanics, rec-latest
- `certs.just` — gen-certs (standalone, rarely used)
- Keep serve/test/build/dev in the main justfile (daily workflow)

Use `import` to keep `just <target>` working from the nexus dir.

## Client error watcher is too narrow

`/api/client-errors` only catches patterns the watcher knows about. Client-side crashes (`NullReferenceException`, `ArgumentOutOfRangeException`, Unity stack traces) are invisible — have to manually grep `Player.log`. See [#179](https://github.com/delebedev/forge/issues/179).

## ~~Remote client connection~~ ✓

Done: `just remote-setup` / `just remote-hosts`. Server already binds 0.0.0.0, certs now accept `extra_san` for Tailscale IP.

## Dynamic server mode switching

Allow proxy/stub/serve mode to change per-match without restarting. E.g. AI matches use Forge engine, but unranked queue routes to real servers for recording. Enables capturing both game types in one server session.

## ~~Debug API: per-seat zone breakdown~~ ✓

Done: `/api/id-map` now includes `ownerSeatId`, `status` (active/limbo/stale), `forgeZone`, `protoZone`. Supports `?seat=`, `?active=`, `?zone=`, `?name=` filters.

## Pre-validate test decks somehow

Cards that resolved to grpId=0 (no client card data):
- Quirion Ranger
- Timberwatch Elf
- Avenging Hunter (also brings Initiative/Undercity dungeon — too complex)
- Fyndhorn Elves
- Wellwisher
