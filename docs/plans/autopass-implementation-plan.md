# Implementation Plan: Auto-Pass, Priority & Phase Stops

> Status: **Phases 1-3 complete.** Phase 4 deferred.
> Branch: `feat/auto-passing-state-transitions`
> Ref: `autopass-and-priority-findings.md`, `phase-stop-profile.md`, `learnings.md`

## What's implemented

- **PhaseStopProfile wired** — engine skips non-stop phases (UPKEEP/DRAW by default)
- **lastSentTurnInfo tracking** — phase annotations compare against what client saw, not diff snapshot
- **Combat/blocking works** — awaitPriority before DeclareBlockersReq, no involuntary attackers, no duplicate blocker reqs
- **gsId chain monotonic** — playback drain + counter sync before bundle building; SELF-REF guard in StateMapper
- **Discard-to-hand-size** — auto-resolves via TargetingHandler non-targeting prompt path
- **Client settings → PhaseStopProfile** — SetSettingsReq Team-scope stops update the live profile
- **StopTypeMapping** — bidirectional Arena StopType ↔ Forge PhaseType, scope-filtered parseStops

Tests: 207/207 pass (unit + conformance + integration)

---

## Phase 1 — Wire PhaseStopProfile + Fix Annotation Regression ✅

### Problem

`GameBridge.start()` creates `WebPlayerController` with no `phaseStopProfile`.
Every phase grants priority. Client stalls at CombatDamage/CombatEnd/etc.

Wiring the profile is 2 lines but causes `AiFirstTurnShapeTest.phaseTransitionsHaveAnnotations`
to regress — the first GSM on the human's turn loses its `PhaseOrStepModified` annotation.

### Root cause

`drainPlayback()` snapshots the current engine state (already at MAIN1) after
sending AI action diffs. `postAction()` compares against that snapshot — same
phase, no annotation injected. The client never saw MAIN1 before, but the
internal snapshot says it did.

### Fix: Separate "last-sent phase" from "diff-computation snapshot"

Add `lastSentTurnInfo` to `DiffSnapshotter`. Updated only when GSMs are
actually sent to the client. `postAction()` uses this for phase-change
detection instead of `prevSnapshot.turnInfo`.

### Tasks

| # | Type | File | Description |
|---|------|------|-------------|
| 1a-test | Unit | `PhaseStopProfileTest.kt` | Profile defaults, isEnabled, setEnabled, round-trip |
| 1b-test | Unit | `LastSentTurnInfoTest.kt` | lastSentTurnInfo tracking, phase change detection, independence from prevSnapshot |
| 1a-impl | Impl | `DiffSnapshotter.kt` | Add `lastSentTurnInfo`, `updateLastSentTurnInfo()`, `isPhaseChangedFrom()`, `getLastSentTurnInfo()` |
| 1b-impl | Impl | `BridgeContracts.kt` | Add `lastSentTurnInfo` methods to `StateSnapshot` interface |
| 1c-impl | Impl | `GameBridge.kt` | Expose new methods; wire `PhaseStopProfile.createDefaults()` in `start()` |
| 1d-impl | Impl | `BundleBuilder.postAction()` | Use `bridge.isPhaseChangedFromLastSent()` instead of prevSnapshot comparison |
| 1e-impl | Impl | `MatchSession.sendBundledGRE()` | Extract turnInfo from sent GSMs → `bridge.diff.updateLastSentTurnInfo()` |
| 1f-impl | Impl | `AutoPassEngine.drainPlayback()` | Update lastSentTurnInfo from playback diffs' turnInfo |
| 1-verify | Test | `AiFirstTurnShapeTest` | Must still pass with profile wired |
| 1g-test | Integration | `PhaseStopIntegrationTest.kt` | Game with profile: play land, pass, verify engine skips non-stop phases, annotations survive |

### Key invariant

`lastSentTurnInfo` reflects what the client has seen. `prevSnapshot` reflects
what diff computation needs. They diverge when the engine skips phases internally
(PhaseStopProfile auto-pass) or when drainPlayback snapshots ahead of sending.

---

## Phase 2 — Fix Blocker Declaration ✅

### Problem

BUGS.md: "declaring blockers targeting doesn't work." Attacker path was fixed
(defenderPlayerId resolved explicitly). Blocker path not verified end-to-end.

Likely issue: `CombatHandler.checkCombatPhase()` at DECLARE_BLOCKERS on human
turn (when AI attacked) sends `SEND_STATE` instead of `DeclareBlockersReq`.
Human-as-defender needs the blocker prompt.

### Tasks

| # | Type | File | Description |
|---|------|------|-------------|
| 2a-test | Integration | `BlockerDeclarationTest.kt` | AI attacks, human blocks: verify DeclareBlockersReq sent, damage resolves with block applied |
| 2b-test | Integration | `BlockerDeclarationTest.kt` | AI attacks, human passes blocking: damage goes to player life |
| 2c-test | Integration | `BlockerDeclarationTest.kt` | 1/1 vs 1/1 trade: both creatures die |
| 2-impl | Impl | `CombatHandler.checkCombatPhase()` | Add branch: DECLARE_BLOCKERS + human is defender + combat has attackers → send DeclareBlockersReq + STOP |
| 2-impl | Impl | `MatchFlowHarness.kt` | Add `declareBlockers(assignments)` helper with blocker→attacker mapping |
| 2-verify | Test | `CombatFlowTest` + `BlockerDeclarationTest` | All green |

### Combat flow expected

```
AI turn: AI declares attackers → engine advances
Human turn at COMBAT_DECLARE_BLOCKERS:
  CombatHandler detects: human is defender, combat has attackers
  → sends DeclareBlockersReq with eligible blockers
  → STOP (wait for client response)
Client: DeclareBlockersResp with assignments
  → CombatHandler.onDeclareBlockers() maps and submits
  → engine resolves combat damage
```

---

## Phase 3 — Apply Client Settings (Stops) ✅

### Problem

`onSettings()` saves `SettingsMessage` but doesn't update `PhaseStopProfile`.
Client's phase ladder is decorative.

### Tasks

| # | Type | File | Description |
|---|------|------|-------------|
| 3a-test | Unit | `StopTypeMappingTest.kt` | StopType ↔ PhaseType bidirectional, all 11 types, SettingScope mapping |
| 3b-impl | Impl | `StopTypeMapping.kt` (new) | Pure mapping: `StopType → PhaseType`, `SettingScope → player selection`. ~40 LOC |
| 3c-test | Integration | `ClientSettingsTest.kt` | Toggle upkeep stop → server stops at upkeep. Clear all → auto-passes everything |
| 3d-impl | Impl | `GameBridge.kt` | Expose `PhaseStopProfile` reference |
| 3e-impl | Impl | `MatchSession.onSettings()` | Parse stops, map via StopTypeMapping, update profile |
| 3-verify | Test | Full gate | test-gate + test-integration |

### StopType → PhaseType mapping

| StopType | PhaseType |
|----------|-----------|
| UpkeepStep | UPKEEP |
| DrawStep | DRAW |
| PrecombatMainPhase | MAIN1 |
| BeginCombatStep | COMBAT_BEGIN |
| DeclareAttackersStep | COMBAT_DECLARE_ATTACKERS |
| DeclareBlockersStep | COMBAT_DECLARE_BLOCKERS |
| CombatDamageStep | COMBAT_DAMAGE |
| EndCombatStep | COMBAT_END |
| PostcombatMainPhase | MAIN2 |
| EndStep | END_OF_TURN |
| FirstStrikeDamageStep | COMBAT_FIRST_STRIKE_DAMAGE |

### SettingScope handling (v1)

- `Team` → applies to human player's stops (own turn)
- `Opponents` → applies to human player's stops during AI turn (store separately or fold into AI defaults)
- `AnyPlayer` → both

For v1: only `Team` scope matters — controls when engine stops on human's own
turn. Opponent-turn stops are handled by AI_DEFAULTS in the profile. Full scope
support deferred.

---

## Deferred (Phase 4+)

- Turn-boundary stop in autoPassAndAdvance
- Activate action handling (`ActionType.Activate` → `PlayerAction.ActivateAbility`)
- `next_phase` / `next_step` in TurnInfo
- Client-driven auto-pass (send priority at every stop, let client respond)
- SmartStops, yields, AutoPassPriority handling
- Transient stops (field 15)
- Opponent-turn SettingScope

---

## Execution Order

```
1a-test   PhaseStopProfileTest (integration)              ✅
1b-test   LastSentTurnInfoTest (unit)                     ✅
1a-impl   DiffSnapshotter: lastSentTurnInfo               ✅
1b-impl   BridgeContracts: interface additions             ✅
1c-impl   GameBridge: expose + wire PhaseStopProfile       ✅
1d-impl   BundleBuilder.postAction: use lastSentTurnInfo   ✅
1e-impl   MatchSession.sendBundledGRE: update tracker      ✅
1f-impl   AutoPassEngine.drainPlayback: update tracker     ✅
1-verify  test-gate (214/214)                              ✅

2a-test   BlockerDeclarationTest (integration)             ✅
2-impl    CombatHandler + MatchFlowHarness                 ✅
2-extra   gsId race fix, thin-diff fix, ScriptedPC fix     ✅
2-verify  test-integration (173/173)                       ✅

3a-test   StopTypeMappingTest (integration)                ✅
3b-impl   StopTypeMapping.kt                               ✅
3c-test   ClientSettingsTest (integration)                  ✅
3d-impl   GameBridge expose profile                        ✅
3e-impl   MatchSession.onSettings wiring                   ✅
3-verify  test-full (207/207)                              ✅
```
