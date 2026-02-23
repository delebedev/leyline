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

## Pre-validate test decks somehow

Cards that resolved to grpId=0 (no client card data):
- Quirion Ranger
- Timberwatch Elf
- Avenging Hunter (also brings Initiative/Undercity dungeon — too complex)
- Fyndhorn Elves
- Wellwisher
