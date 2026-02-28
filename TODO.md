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

## Restore JaCoCo coverage plugin

JaCoCo plugin removed from pom.xml — `prepare-agent` at `initialize` phase + `${argLine}` in surefire causes spotless 2.43.0 "No value present" crash on JDK 17 CI runners. Justfile recipes (`just coverage`, `just coverage-summary`) are still there but won't produce reports until the plugin is re-added. Options:
- Bump spotless to a version that doesn't choke on unresolved `${argLine}`
- Move JaCoCo to a Maven profile (`-Pcoverage`) so it's opt-in and doesn't pollute default lifecycle
- Switch CI to JDK 21+ where the conflict may not reproduce

## Coverage: include integration tests

`just coverage` currently runs unit+conformance only. Integration tests should be included for fuller coverage. ~~Bridge timeout log noise~~ fixed (see `docs/logging.md`). Remaining: wire integration group into the coverage recipe.

## Client error watcher is too narrow

`/api/client-errors` only catches patterns the watcher knows about. Client-side crashes (`NullReferenceException`, `ArgumentOutOfRangeException`, Unity stack traces) are invisible — have to manually grep `Player.log`. See [#179](https://github.com/delebedev/forge/issues/179).

## ~~Remote client connection~~ ✓

Done: `just remote-setup` / `just remote-hosts`. Server already binds 0.0.0.0, certs now accept `extra_san` for Tailscale IP.

## Dynamic server mode switching

Allow proxy/stub/serve mode to change per-match without restarting. E.g. AI matches use Forge engine, but unranked queue routes to real servers for recording. Enables capturing both game types in one server session.

## ~~Debug API: per-seat zone breakdown~~ ✓

Done: `/api/id-map` now includes `ownerSeatId`, `status` (active/limbo/stale), `forgeZone`, `protoZone`. Supports `?seat=`, `?active=`, `?zone=`, `?name=` filters.

## [tests] Reconsider "conformance" as a group name

"Conformance" originally meant wire-shape checks against Arena protocol patterns, but the group now includes board-based tests (zone transitions, events, annotations, state mapping) that don't check wire conformance at all — they're just "needs forge-game but not the game loop thread." The name is becoming a misnomer for what's really a speed tier. Options:
- Rename to something like `engine` or `component` (breaking: touches every test class annotation + justfile + CI)
- Keep the name, redefine in docs as "tests that need forge-game but not the game loop" (cheap, slightly confusing)
- Split into `conformance` (actual wire-shape checks) + new group for board-based engine tests (more groups = more complexity)

Not urgent — current setup works. Revisit if the naming causes real confusion.

## Pre-validate test decks somehow

Cards that resolved to grpId=0 (no client card data):
- Quirion Ranger
- Timberwatch Elf
- Avenging Hunter (also brings Initiative/Undercity dungeon — too complex)
- Fyndhorn Elves
- Wellwisher
