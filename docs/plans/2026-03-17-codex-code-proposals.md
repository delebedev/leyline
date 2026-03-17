# Codex Code Proposals

**Date:** 2026-03-17

Short list from an independent repo review. Not a full design doc. Goal: capture a few changes that look small-to-medium but high leverage. Another agent can validate / refine before implementation.

---

## 1. Remove seat-1 bridge footguns

### Problem

`GameBridge` now has proper per-seat accessors:

- `actionBridge(seatId)`
- `promptBridge(seatId)`
- `mulliganBridge(seatId)`

But much of Match Door/session code still reaches for the old seat-1 aliases:

- `bridge.actionBridge`
- `bridge.promptBridge`

That is fine in 1-seat flows, but dangerous once PvP and seat-2 routing exist.

### Why it matters

This creates the exact kind of bug that is hard to see in single-seat tests:

- read wrong pending action
- submit response to wrong prompt bridge
- route `GroupResp` off seat-1 prompt state
- drain reveal state from wrong seat

Likely broad bug-class reduction for small code churn.

### Proposed direction

- Deprecate or remove seat-1 aliases entirely.
- If removal is too noisy, rename them to `seat1ActionBridge` / `seat1PromptBridge` so accidental use is obvious.
- Add a small seat-scoped helper, e.g. `bridge.forSeat(seatId)`, returning:
  - action bridge
  - prompt bridge
  - mulligan bridge
  - maybe seat player lookup / reveal drain

### Validation

- grep/build should leave no gameplay/session use of the old aliases
- add at least one real seat-2 MD transport test, not only bridge-level PvP coverage

---

## 2. Tighten snapshot semantics in `DiffSnapshotter`

### Problem

`DiffSnapshotter` still carries two subtly different concepts:

- `previousState` = diff baseline
- `lastSentTurnInfo` = what client actually saw

The code comments are good, but the model still relies on developers remembering which one to read at each callsite.

### Why it matters

This is the kind of state model that works until a new mechanic or phase edge uses the wrong source and silently creates:

- skipped phase annotations
- wrong `prevGameStateId` expectations
- “client never saw this transition” bugs

### Proposed direction

Do not redesign the whole diff system yet. Just make the distinction harder to misuse.

Options:

1. Rename methods/fields more aggressively
   - `previousState` → `diffBaselineState`
   - `lastSentTurnInfo` → `clientSeenTurnInfo`

2. Wrap both into one named snapshot holder
   - lightweight struct, not a major rewrite

3. Push BundleBuilder callsites toward explicit helper methods
   - `currentDiffBaseline()`
   - `currentClientSeenTurnInfo()`

### Validation

- phase-transition tests
- AI-turn / skipped-phase tests
- no behavior change intended in first pass

---

## 3. Centralize match/session teardown

### Problem

Lifecycle cleanup is split across:

- `channelInactive()`
- `exceptionCaught()`
- `sendGameOver()`
- concede TODOs
- `MatchRegistry` bookkeeping

Today, disconnect cleanup and post-game cleanup do not obviously converge on the same path.

### Why it matters

Lifecycle bugs compound:

- stale sessions
- leaked engine threads
- reconnect weirdness
- confusing debug state

This is more architecture hygiene than feature work, but it touches a real open pain point.

### Proposed direction

Introduce one registry/match-level teardown path with inputs like:

- `matchId`
- `seatId`
- reason (`disconnect`, `exception`, `gameOver`, `concede`)

That path should own:

- session unregister
- handler unregister
- match close/remove when appropriate
- recorder shutdown

### Validation

- disconnect / reconnect test
- concede / lethal / exception should all end in consistent registry state

---

## 4. Add prompt classification layer

### Problem

Prompt routing still leans on heuristics like:

- `candidateRefs.isNotEmpty()` => targeting
- special-case surveil/scry
- modal prompt string/type checks

This is brittle, especially with upstream drift and non-spell choices like legend-rule-style SBA decisions.

### Why it matters

Prompt routing mistakes produce some of the worst user-facing failures:

- infinite re-prompts
- wrong wire message type
- prompt metadata mismatch

### Proposed direction

Before sending protocol messages, classify prompts into a small internal enum:

- `SpellTargeting`
- `SelectN`
- `Grouping`
- `ModalChoice`
- `AutoResolvable`

Keep it thin. Goal is not a new subsystem; goal is to move routing logic out of scattered ad hoc checks.

### Validation

- target flow tests
- `#123` style non-targeting choose-cards flow
- optional-cost + modal ETB regression checks

---

## 5. Add a few transport-seam tests, not many more unit tests

### Problem

Unit coverage is already strong. Remaining risk is concentrated in transport/lifecycle seams.

### Why it matters

More builder/unit coverage will not catch:

- seat-2 bridge misuse
- disconnect cleanup gaps
- game-over delivery after stack resolution

### Proposed direction

Prefer a tiny set of high-value integration/conformance tests:

1. stack-resolution lethal sends game-over bundle
2. disconnect tears down match/session cleanly
3. seat-2 cast/target/prompt flow over real MD path

### Validation

If these three are stable, a lot of current architectural anxiety drops.

---

## 6. Clarify local `test` vs `gate` workflow

### Problem

Repo docs say “scope tests to changed modules”, but `just test` currently runs everything, and `test-gate` is only a partial gate.

### Why it matters

This is small process friction, but it affects every dev loop.

### Proposed direction

- keep scoped module tests as the normal path
- reserve `test-gate` for pre-commit / handoff
- likely include:
  - formatting check
  - detekt
  - module test tasks
- possibly keep `build` as a separate explicit repo-health command if Forge freshness should stay distinct

### Validation

- another agent should verify desired semantics against `CLAUDE.md`, `leyline-tests.md`, and current `just` usage before changing commands

---

## Suggested order

1. remove or rename seat-1 bridge aliases
2. centralize teardown
3. add 3 transport-seam tests
4. tighten snapshot naming/model
5. add prompt classification layer
6. clean up test/gate ergonomics
