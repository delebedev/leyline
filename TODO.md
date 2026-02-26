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

## Pre-validate test decks somehow

Cards that resolved to grpId=0 (no client card data):
- Quirion Ranger
- Timberwatch Elf
- Avenging Hunter (also brings Initiative/Undercity dungeon — too complex)
- Fyndhorn Elves
- Wellwisher
