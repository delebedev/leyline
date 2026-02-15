# Arena Conformance Gaps — Progressive Enhancement Plan

## Context

We extracted 6 golden subsequences from real Arena server recordings (`full-game*.json`)
and wrote 7 conformance tests comparing our `BundleBuilder` output shapes against them.

**Status: COMPLETE.** All 8 Arena conformance tests pass genuinely (no `expectedExceptions`).

## Final Test Status

| Test | Status | What it validates |
|------|--------|-------------------|
| `arenaPlayLandShape` | genuine pass | Shape vs Arena golden (Diff+ActionsAvailableReq with timers) |
| `arenaPhaseTransitionShape` | genuine pass | 5-message pattern (3 diffs + PromptReq + ActionsAvailableReq) |
| `arenaCastCreatureStructure` | genuine pass | Golden structure: 2x aiActionDiff (cast+echo, resolve+echo) |
| `aiActionDiffMatchesArenaSubPattern` | genuine pass | Our aiActionDiff sends SendHiFi diff+echo |
| `arenaDeclareAttackersShape` | genuine pass | BundleBuilder output vs golden (types + promptId=6) |
| `arenaEdictalShape` | genuine pass | edictalPass() type matches Arena EdictalMessage |
| `arenaEdictalContext` | genuine pass | Arena pattern: SendAndRecord → Edict → SendHiFi |
| ~~arenaGameStartHandShape~~ | removed | No matching Arena pattern (Arena uses phase transitions) |

## Enhancement Plan (highest → lowest priority)

### 1. Add TimerStateMessage to game state bundles

**Priority:** Highest — timers control the turn clock UI. Missing timers means client
may show no clock or behave unexpectedly during timed play.

**Gap:** Arena includes `timers` in every `GameStateMessage` fieldPresence. Our
`buildFromGame` / `buildDiffFromGame` never populates the timers field.

**Fix:** In `StateMapper.buildFromGame` and `buildDiffFromGame`, add timer info
(2 timers per player, matching the `TimerStateMessage` proto structure).

**Test unlocked:** `arenaPlayLandShape` — remove `expectedExceptions`.

**Files:**
- `StateMapper.kt` — add timer population in `buildFromGame`, `buildDiffFromGame`
- `ArenaGoldenConformanceTest.kt` — remove `expectedExceptions` from `arenaPlayLandShape`

---

### 2. Upgrade declare-attackers and edictal tests to real comparisons

**Priority:** High — these tests currently only validate golden file structure. They should
compare our `BundleBuilder.declareAttackersBundle()` and `edictalPass()` output against
the Arena golden shapes.

**Gap:** Tests call `loadGolden()` and assert on the golden data. They never call
`BundleBuilder` or `fingerprint()`.

**Fix:**
- `arenaDeclareAttackersShape`: start game, advance to combat, call
  `BundleBuilder.declareAttackersBundle()`, compare shape against golden.
- `arenaEdictalMessageExists` / `edictalPassMessageType`: merge into one test that
  builds an edictal + surrounding state messages and compares against golden.

**Test unlocked:** Turns 3 documentation-only tests into real conformance gates.

**Files:**
- `ArenaGoldenConformanceTest.kt` — rewrite `arenaDeclareAttackersShape`,
  `arenaEdictalMessageExists`, `edictalPassMessageType`
- May need `ConformanceTestBase` helper to advance game to combat phase

---

### 3. Add PromptReq to phase transitions

**Priority:** Medium-high — Arena sends `PromptReq(promptId=37)` before
`ActionsAvailableReq` on every phase transition. Client may use this to display
the "priority" prompt overlay. Without it, the client might not show the stop/pass UI.

**Gap:** Our `phaseTransitionDiff` produces 2 messages (2 diffs). Arena sends 5:
3 diffs + PromptReq + ActionsAvailableReq. This task adds PromptReq only.

**Fix:** Add `PromptReq` message to `BundleBuilder.phaseTransitionDiff()` between
the second diff and the actions window. PromptReq has `promptId=37` and references
the current game state.

**Test unlocked:** Partial fix for `arenaPhaseTransitionShape` (still fails on
triple-diff, but PromptReq gap is closed).

**Files:**
- `BundleBuilder.kt` — add PromptReq to `phaseTransitionDiff()` return
- May need to update `StructuralFingerprint.fromGRE` if PromptReq has unhandled fields
- `ArenaGoldenConformanceTest.kt` — update expected message count

---

### 4. Triple-diff pattern for phase transitions

**Priority:** Medium — Arena sends 3 diffs per phase: `SendHiFi` (annotated),
`SendHiFi` (echo/marker), `SendAndRecord` (annotated). We send 2 diffs:
`SendHiFi` (annotated) + `SendHiFi` (marker with actions). The third diff
(`SendAndRecord`) is what the client uses to commit state to its history.

**Gap:** Missing the `SendAndRecord` diff in phase transitions. This may cause
the client's game history / undo to be incomplete.

**Fix:** Add third diff to `BundleBuilder.phaseTransitionDiff()` with
`updateType=SendAndRecord` and `PhaseOrStepModified` annotation.

**Test unlocked:** `arenaPhaseTransitionShape` — remove `expectedExceptions` (combined
with task 3).

**Files:**
- `BundleBuilder.kt` — extend `phaseTransitionDiff()` to produce 5 messages
- `phase-transition.json` golden — regenerate (self-generated golden changes too)
- `ArenaGoldenConformanceTest.kt` — remove `expectedExceptions`

---

### 5. Resolve echo diffs for cast spell

**Priority:** Medium-low — Arena sends 4 messages after a spell cast:
CastSpell diff → echo diff → Resolve diff → echo diff. We send 2 (state diff +
ActionsAvailableReq). The echo diffs and resolution annotation are missing.

**Gap:** Our `postAction` doesn't know if the action was a cast vs land play.
Arena differentiates: land play gets 1 diff, cast gets 4 (with stack
placement → resolution). This requires action-aware bundling.

**Fix:** Either:
- (a) Add a `postCast` variant to `BundleBuilder` that produces the 4-message
  cast sequence, or
- (b) Make `postAction` inspect game state to detect stack resolution and emit
  extra diffs.

**Test unlocked:** `arenaCastCreatureShape` — remove `expectedExceptions`.

**Files:**
- `BundleBuilder.kt` — new `postCast()` or extend `postAction()`
- `MatchSession.kt` — call the right bundle method based on action type
- `ArenaGoldenConformanceTest.kt` — remove `expectedExceptions`

---

### 6. Game-start bundle structure

**Priority:** Lowest — the game-start golden (`full-game-3.json [7-9]`) captures
the hand-deal + mulligan sequence, which is handled by `MatchHandler` template
senders, not `BundleBuilder.gameStart()`. The two serve different phases of the
connection flow.

**Gap:** `BundleBuilder.gameStart()` produces a post-keep bundle (Beginning/Upkeep
transition → Main1 state). The Arena golden captures the pre-keep hand-deal
messages. These are fundamentally different protocol moments.

**Fix:** Either:
- (a) Extract the correct Arena golden for post-keep (find the right indices), or
- (b) Test `MatchHandler`'s template senders against the hand-deal golden instead.

**Test unlocked:** `arenaGameStartHandShape` — fix golden or test target.

**Files:**
- `ArenaGoldenConformanceTest.kt` — fix golden indices or test approach
- Possibly `Templates.kt` if testing template senders
