# Puzzle-Driven Development

How we fix gameplay mechanics in leyline. Puzzle is the acceptance criteria, MatchHarness is the fast loop, Arena playtest is the proof.

## The Loop

```
1. Identify mechanic gap (catalog, issue, or recording)
2. Write puzzle — minimal cards, one win path, forced mechanic
3. Run in MatchHarness — classify failure
4. Fix code — iterate in <5s cycles (build 0.5s + test 4s)
5. Arena playtest — visual proof, screenshot gallery for PR
6. Commit with conformance assertions
```

## Writing the Puzzle

See `docs/puzzle-design.md` for full patterns. Key rules:

- **One win path.** If testing kicker, the unkicked version must NOT kill. If testing blocking, the only way to survive is blocking.
- **Minimum cards.** No lands if you don't need mana. 1 library card per player (draw step). Every extra card is noise.
- **Force determinism.** Don't rely on AI heuristics. Use "must attack" creatures (Juggernaut), or set life totals so the AI's only rational play is what you need.
- **AI blockers close off combat wins.** Testing a non-combat mechanic? Give the AI a big blocker so attacking can't win.

## Failure Classification

The puzzle result tells you where to look:

| Result | Meaning | Where to look |
|--------|---------|---------------|
| CRASH (NPE, exception) | Card missing from DB, unhandled type | PuzzleCardRegistrar, CardData |
| TIMEOUT (no gameOver) | Engine blocked, prompt unanswered | Bridge, InteractivePromptBridge, WebPlayerController |
| LOSE (turn limit) | Mechanic didn't fire, AI didn't attack | ActionMapper, MatchSession action handler |
| LOSE (player died) | Mechanic fired wrong (wrong damage, wrong target) | AnnotationBuilder, StateMapper, WebPlayerController |
| WIN but wrong state | Side effects missing | GameEventCollector, TransferCategory |
| WIN | Mechanic works | Ship it |

## MatchHarness Fast Loop

```kotlin
val h = MatchFlowHarness(seed = 42L, validating = false)
h.connectAndKeepPuzzleText(pzl)

// Cast, target, activate — use harness helpers
h.castSpellByName("Lightning Bolt").shouldBeTrue()
h.selectTargets(listOf(2))  // opponent = seatId 2
h.activateAbility("Liliana of the Veil", abilityIndex = 1)  // -2

// Pass to resolve
repeat(10) {
    if (h.isGameOver()) return@repeat
    h.passPriority()
}

h.isGameOver().shouldBeTrue()
ai.life shouldBe 0
```

Key harness methods:
- `castSpellByName(name)` — cast from hand
- `activateAbility(name, index)` — activate Nth ability on battlefield card
- `selectTargets(instanceIds)` — two-phase targeting (SelectTargetsResp + SubmitTargetsReq)
- `selectTargetsIterative(ids)` + `submitTargets()` — phase-by-phase control
- `declareAttackers(ids)` / `declareAllAttackers()` — combat
- `passPriority()` — advance game state

## Diagnostic Pattern

When a puzzle fails, add println diagnostics:

```kotlin
println("Phase: ${h.phase()}, Turn: ${h.turn()}, GameOver: ${h.isGameOver()}")
println("Human BF: ${human.getZone(ZoneType.Battlefield).cards.map { it.name }}")
println("AI BF: ${ai.getZone(ZoneType.Battlefield).cards.map { it.name }}")
println("AI GY: ${ai.getZone(ZoneType.Graveyard).cards.map { it.name }}")
println("AI life: ${ai.life}")
```

Read from test XML: `find matchdoor/build -name "*.xml" -path "*/TEST-*ClassName*" -exec cat {} \;`

Remove diagnostics before committing.

## Arena Playtest

After MatchHarness passes, validate in Arena:

```bash
# Start puzzle server
pkill -f LeylineMainKt; sleep 2
just build
nohup just serve-puzzle puzzles/<name>.pzl > /tmp/leyline.log 2>&1 &

# Launch Arena, navigate to Bot Match
pkill -f MTGA; sleep 3
just arena launch
# ... navigate to Find Match → Bot Match → select deck → Play

# Capture screenshots at key steps
just arena capture --out /tmp/step1.png
# ... play through ...

# Publish gallery
~/.claude/skills/publish/publish.sh --title "Feature Name" /tmp/step*.png
```

Add gallery URL to PR comment as evidence.

### Hot-swap puzzles (no restart)

Once Arena is connected and in-game, swap to a different puzzle without restarting the server or Arena:

```bash
# By file path (from puzzles/ dir)
curl -X POST http://localhost:8090/api/inject-puzzle?file=legend-rule

# By inline .pzl content
curl -X POST http://localhost:8090/api/inject-puzzle -d @puzzles/legend-rule.pzl
```

The client resets to the new board state immediately — no reconnect needed. Use this to iterate on puzzle design or re-run a scenario after a code fix (`just build` first, then inject).

## Conformance Assertions

Beyond gameplay (WIN/LOSE), assert on message shape:

```kotlin
// Capture messages during the action
val snap = h.messageSnapshot()
h.castSpellByName("Burst Lightning").shouldBeTrue()
val messages = h.messagesSince(snap)

// Verify CastingTimeOptionsReq shape (kicker example)
val ctoReq = messages.first { it.hasCastingTimeOptionsReq() }.castingTimeOptionsReq
ctoReq.castingTimeOptionReqList.any {
    it.castingTimeOptionType == CastingTimeOptionType.Kicker
}.shouldBeTrue()
```

## What This Catches

Real examples from this workflow:

| Puzzle | What it caught | Root cause |
|--------|---------------|------------|
| `planeswalker-sacrifice.pzl` | Liliana +1 fired instead of -2 | `abilityIndex` hardcoded to 0 |
| Same puzzle, deeper | Only 1 abilityId slot for 3 abilities | `deriveAbilityIds` used `spellAbilities.size - 1` |
| Same puzzle, deepest | Temp cards had empty spellAbilities | `registerPuzzleCards` used by-name path instead of live card |
| `kicker.pzl` | Kicker never prompted | `playChosenSpellAbility` skipped `chooseOptionalAdditionalCosts` |
| Same puzzle, deeper | Bridge deadlock on prompt | Engine thread blocks inside `playChosenSpellAbility`, auto-pass can't run |
| `declare-blockers.pzl` | AI didn't attack (false pass) | Grizzly Bears won't attack into Centaur Courser — used Juggernaut instead |
| `activated-ability.pzl` | Player targeting click doesn't register | `SelectTargetsReq` for player targets needs specific portrait coords |

## When NOT to Use Puzzles

- **Protocol-only conformance** (SubmitTargetsReq handling) — no gameplay change, use MatchHarness message assertions
- **Visual/animation bugs** — need Arena playtest, not puzzle
- **Multi-turn interactions** — puzzles are best for single-turn mechanics. Multi-turn needs constructed game tests.

## File Locations

```
puzzles/                           # Puzzle .pzl files (acceptance targets)
docs/puzzle-design.md              # Puzzle authoring guide
matchdoor/src/test/resources/puzzles/  # Test-only puzzles (classpath)
matchdoor/src/test/kotlin/leyline/conformance/  # Integration tests
```
