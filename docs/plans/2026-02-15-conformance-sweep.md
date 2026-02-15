# Conformance Sweep Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extract golden slices for every untested pattern in the 3 full-game recordings, write conformance tests, fix protocol gaps found.

**Architecture:** Use `just proto-compare --analyze` to identify pattern indices → `jq` to extract slices → shape-only conformance tests against Arena goldens → fix gaps iteratively.

**Tech Stack:** Kotlin, TestNG, jq, existing fingerprint/golden tooling

**Baseline:** 94 tests pass. 7 conformance tests cover: play-land, phase-transition, cast-creature (AI+player), declare-attackers, edictal-pass, aiActionDiff sub-pattern. 5 patterns have NO golden file or test.

---

### Task 1: Extract golden slices for all untested patterns

**Source recordings and index ranges** (from `just proto-compare --analyze`):

| Pattern | Recording | Indices | Notes |
|---------|-----------|---------|-------|
| DRAW step | full-game-3 | [49-50] | 2 msgs, simplest instance |
| NEW_TURN | full-game-3 | [47-48] | 2 msgs |
| COMBAT (simple, no blockers) | full-game-3 | [132-142] | 11 msgs, first combat |
| COMBAT (with damage) | full-game-3 | [193-206] | 14 msgs, later combat with kills |
| CAST_SPELL (player) | full-game-3 | [245-247] | 3 msgs |
| TARGETED_SPELL | full-game-2 | [71-88] | 18 msgs, full targeting flow |
| PLAY_LAND (AI) | full-game-3 | [29-30] | 2 msgs (already have player; need AI) |
| GAME_END | full-game-3 | [249-252] | 4 msgs |

**Steps:**

```bash
cd forge-nexus/src/test/resources/golden

# Draw step
jq '.[49:51]' full-game-3.json > arena-draw-step.json

# New turn
jq '.[47:49]' full-game-3.json > arena-new-turn.json

# Combat (simple)
jq '.[132:143]' full-game-3.json > arena-combat-simple.json

# Combat (with damage/kills)
jq '.[193:207]' full-game-3.json > arena-combat-damage.json

# Cast spell (player perspective)
jq '.[245:248]' full-game-3.json > arena-cast-spell-player.json

# Targeted spell (full flow)
jq '.[71:89]' full-game-2.json > arena-targeted-spell.json

# Play land (AI perspective)
jq '.[29:31]' full-game-3.json > arena-play-land-ai.json

# Game end
jq '.[249:253]' full-game-3.json > arena-game-end.json
```

Verify each file: `cat arena-*.json | python3 -m json.tool > /dev/null` (valid JSON check).

Commit: `test(nexus): extract 8 new Arena golden slices from recordings`

---

### Task 2: Inspect golden slices — document expected shapes

Before writing tests, read each golden file and document:
- Message count and types
- Annotation types and categories
- updateType per message
- Field presence (zones, objects, timers, actions, annotations)
- Any new message types we haven't handled (SelectNreq, SubmitTargetsResp, etc.)

**Create:** `forge-nexus/docs/plans/2026-02-15-golden-shapes.md` with a table per pattern.

This is research only — no code changes.

Commit: `docs(nexus): document golden slice shapes for conformance sweep`

---

### Task 3: Draw step conformance test

**Golden:** `arena-draw-step.json` (2 msgs)

Expected shape (from runbook):
```
[0] GS Diff, SendHiFi, [ObjectIdChanged, PhaseOrStepModified, ZoneTransfer], category=Draw
[1] GS Diff, SendHiFi, empty marker
```

This matches our `aiActionDiff` pattern (diff + echo). The draw is an "AI action" from the opponent's perspective.

**Test:** Create `DrawStepConformanceTest.kt`. Use `assertShapeConformance("arena-draw-step", captured)`.

To build our equivalent: after advanceToMain1, pass priority through to the draw step, then call `BundleBuilder.aiActionDiff()` to capture the draw diff.

Commit: `test(nexus): add draw step conformance test`

---

### Task 4: New turn conformance test

**Golden:** `arena-new-turn.json` (2 msgs)

Expected shape:
```
[0] GS Diff, SendHiFi, [NewTurnStarted, TappedUntappedPermanent x N], category varies
[1] GS Diff, SendHiFi, empty marker
```

Same `aiActionDiff` pattern. NewTurnStarted + TappedUntappedPermanent (untap step).

**Test:** Create `NewTurnConformanceTest.kt`. Advance past turn 1 to capture the turn boundary.

Commit: `test(nexus): add new turn conformance test`

---

### Task 5: Combat conformance test (simple, no blockers)

**Golden:** `arena-combat-simple.json` (~11 msgs)

This is the most complex pattern. From runbook:
```
DeclareAttackersReq
GS Diff states (attacker state, phase transitions)
SubmitAttackersResp
Damage step diffs
```

**Test:** Create `CombatConformanceTest.kt`. This test documents the expected shape — may use `expectedExceptions` initially if our combat flow doesn't match yet.

Key: extract the golden, fingerprint it, document the structure. The test verifies our understanding of the pattern even before we can reproduce it.

Commit: `test(nexus): add combat conformance test (shape documentation)`

---

### Task 6: Game end conformance test

**Golden:** `arena-game-end.json` (4 msgs)

Expected shape:
```
[0-2] GS Diff, SendAndRecord, empty (actions only)
[3] IntermissionReq
```

Our `MatchSession.sendGameOver()` already produces this pattern. Test verifies it matches Arena.

**Test:** Create `GameEndConformanceTest.kt`.

Commit: `test(nexus): add game end conformance test`

---

### Task 7: Cast spell (player perspective) conformance test

**Golden:** `arena-cast-spell-player.json` (3 msgs)

Different from AI perspective — player sees:
```
[0] GS Diff, SendAndRecord, CastSpell annotations
[1] GS Diff, SendAndRecord, same annotations (seat 2 view)
[2] ActionsAvailableReq
```

Our `postAction` should produce this. Compare shape.

**Test:** Add to existing `CastCreatureConformanceTest.kt` or create `CastSpellPlayerConformanceTest.kt`.

Commit: `test(nexus): add player-perspective cast spell conformance test`

---

### Task 8: Targeted spell conformance test (documentation)

**Golden:** `arena-targeted-spell.json` (~18 msgs)

This is the most complex untested flow. From runbook:
```
GS Diff, Send, [ObjectIdChanged, PlayerSelectingTargets, ZoneTransfer]
SelectTargetsReq
GS Diff, Send, empty
SelectTargetsReq (re-prompt)
GS Diff, SendHiFi, [AbilityInstanceCreated, ManaPaid, PlayerSubmittedTargets, ...]
GS Diff, SendHiFi, empty
... resolution varies
```

New message types: `SelectTargetsReq`, new annotations: `PlayerSelectingTargets`, `PlayerSubmittedTargets`.

**Test:** Create `TargetedSpellConformanceTest.kt` — likely `expectedExceptions` since we haven't implemented the full targeting prompt flow. But extracting and documenting the golden is valuable.

Commit: `test(nexus): add targeted spell conformance test (shape documentation)`

---

### Task 9: Fix gaps discovered by conformance tests

After Tasks 3-8, some tests will pass (draw, new turn, game end — these use patterns we've already built). Others will fail with specific divergences.

For each failing test:
1. Read the divergence report
2. Identify what's missing (annotations, fields, message types)
3. Fix in BundleBuilder/StateMapper/AnnotationBuilder
4. Re-run until test passes
5. Commit per fix

This task is iterative — exact scope depends on what Tasks 3-8 discover.

---

### Task 10: Format + final verification

Run: `just fmt && just test`

Commit if fmt changed anything.
