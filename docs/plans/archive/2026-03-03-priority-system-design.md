# Priority System Design — Aligned Abstractions + Observability

> Date: 2026-03-03
> Status: **Executed** (PR #16)
> Ref: `priority-system-analysis.md`, `autopass-and-priority-findings.md`, `autopass-implementation-plan.md`

---

## Objective

Build an aligned priority system with correct abstractions that's testable against real Arena behavior. Three workstreams, executed in order:

1. **Observability** (this sprint) — enhance recording decoder to capture all priority-relevant fields from real Arena traffic, build analysis tooling
2. **Correct abstractions** (next) — model priority decisions as typed state machine with clear seams
3. **Full client fidelity** (later) — wire all 10 gaps from `priority-system-analysis.md`

---

## 1. Why Observability First

We're designing against documentation, not observed behavior. The recording decoder strips critical fields:

| Field | Proto Location | Currently Captured | Impact |
|-------|---------------|-------------------|--------|
| `autoPassPriority` | `PerformActionResp.autoPassPriority` | **No** | Can't see full control mode |
| `shouldStop` | `Action.shouldStop` (field 12) | **No** | Can't see Arena's stop intelligence |
| `highlight` | `Action.highlight` (field 27) | **No** | Can't see action urgency hints |
| `stops` | `SettingsMessage.stops` | **No** | Can't see client stop config |
| `transientStops` | `SettingsMessage.transientStops` (field 15) | **No** | Can't see one-shot stops |
| `yields` | `SettingsMessage.yields` | **No** | Can't see auto-yield config |
| `autoPassOption` | `SettingsMessage.autoPassOption` (field 4) | **No** | Can't see auto-pass mode |
| `stackAutoPassOption` | `SettingsMessage.stackAutoPassOption` (field 19) | **No** | Can't see stack resolution mode |
| `smartStopsSetting` | `SettingsMessage.smartStopsSetting` (field 11) | **No** | Can't see smart stops toggle |
| `setYield` | `PerformActionResp.setYield` (field 3) | **No** | Can't see inline yield changes |
| `EdictalMessage` contents | `GREToClientMessage.edictalMessage` | Counted only | Can't see what Arena force-passes |

### Evidence from recordings

**Recording `2026-03-01_11-33-28`** (non-combat, 10 turns):
- 383 GSMs → 17 AARs (4.4% priority grant rate)
- All 17 AARs at Main1 only. No combat, no opponent-turn stops.
- 1 explicit Pass out of 16 PerformActionResp
- 0 EdictalMessages
- 11 SetSettingsReq — contents unknown (decoder strips them)

**Recording `2026-03-01_00-18-46`** (combat-heavy, 10+ turns):
- 1283 GSMs → 97 AARs (7.6% grant rate)
- Phase distribution: Main1 (62), BeginCombat (10), DeclareBlock (8), DeclareAttack (7), Main2 (6), End (4)
- 48 explicit Passes out of 96 PerformActionResp (50%)
- 1 EdictalMessage

**Key insight:** Arena sends priority to the client at ~5-8% of state changes. The remaining 92-95% are auto-resolved without client involvement. Understanding *when and why* Arena grants priority requires seeing `shouldStop`, `autoPassPriority`, and stop configuration.

---

## 2. Correct Abstractions (Phase 2 — Design Only, Not Implemented Yet)

### Current state

Priority decisions are scattered across 3 layers with ad-hoc types:

```
Engine thread:                        Session thread:
  WebPlayerController                   AutoPassEngine
    ├─ autoPassUntilEndOfTurn (bool)     ├─ shouldAutoPass(actions) → bool
    ├─ smartPhaseSkip (inline logic)     ├─ edictalPass (force-pass)
    ├─ PhaseStopProfile.isEnabled()      └─ CombatHandler/TargetingHandler
    └─ GameActionBridge.awaitAction()
```

No single type represents "why this priority was granted/skipped." No way to log/test the decision chain.

### Target abstractions

```kotlin
/** Why a priority stop was resolved without client input. */
sealed class AutoPassReason {
    data object EndTurnFlag : AutoPassReason()
    data object SmartPhaseSkip : AutoPassReason()  // own turn, no playable actions
    data class PhaseNotStopped(val phase: PhaseType) : AutoPassReason()
    data object OnlyPassActions : AutoPassReason()  // session-side shouldAutoPass
    data object ClientAutoPass : AutoPassReason()   // autoPassPriority=Yes from client
    data object FullControlOverride : AutoPassReason() // never auto-pass
    data object AutoPassCancel : AutoPassReason()   // opponent acted, cancel auto-pass
}

/** Result of evaluating whether to grant priority to the client. */
sealed class PriorityDecision {
    data class Grant(val phase: PhaseType, val actions: List<Action>) : PriorityDecision()
    data class Skip(val reason: AutoPassReason) : PriorityDecision()
}

/** Client's current auto-pass configuration, parsed from SetSettingsReq. */
data class ClientAutoPassState(
    val autoPassOption: AutoPassOption,          // None/Turn/UnlessAction/EndStep/ResolveMyStackEffects/FullControl/ResolveAll
    val stackAutoPassOption: AutoPassOption,     // separate stack resolution mode
    val autoPassPriority: AutoPassPriority,      // from last PerformActionResp (None/No/Yes)
    val stops: StopConfiguration,                // phase stops per scope
    val transientStops: Set<PhaseType>,          // one-shot stops (consumed on use)
    val yields: Map<Int, YieldConfig>,           // abilityGrpId/cardTitleId → auto-yield
    val smartStops: Boolean,                     // smart stops enabled
)

/** Phase stops organized by scope. */
data class StopConfiguration(
    val ownTurn: Set<PhaseType>,       // Team scope
    val opponentTurn: Set<PhaseType>,  // Opponents scope
)
```

### Decision flow (target)

```
chooseSpellAbilityToPlay():
  1. autoPassUntilEndOfTurn? → Skip(EndTurnFlag)
  2. clientAutoPassState.autoPassPriority == FullControl? → force Grant
  3. smartPhaseSkip eligible? → Skip(SmartPhaseSkip)
  4. Phase in stops (own/opp turn aware)? → no → Skip(PhaseNotStopped)
  5. autoPassCancel flag set? → clear flag, force Grant
  6. → Grant (block on bridge)

AutoPassEngine:
  7. shouldAutoPass(actions)? → Skip(OnlyPassActions) + edictal
  8. clientAutoPassState.autoPassOption == ResolveAll + stack resolving? → Skip(ClientAutoPass) + edictal
```

Each decision logged as `PriorityDecision`. Testable in isolation (pure function from game state + config → decision).

### What this enables

- **Unit testing:** Given game state X and config Y, assert decision is Grant/Skip(reason)
- **Conformance testing:** Replay recording, compare our decisions against Arena's (did we grant when Arena didn't? vice versa?)
- **Debug panel:** `/api/priority-log` shows decision chain for every priority stop
- **Future GAP fixes:** Each gap maps to a specific `AutoPassReason` or `ClientAutoPassState` field

---

## 3. Full Client Fidelity (Phase 3 — Design Only, Not Implemented Yet)

Wire all 10 gaps from `priority-system-analysis.md`. Each gap maps to a specific abstraction:

| Gap | Abstraction | Priority |
|-----|------------|----------|
| GAP 1: autoPassPriority ignored | `ClientAutoPassState.autoPassPriority` | P0 (correctness) |
| GAP 2: AutoPassOption not tracked | `ClientAutoPassState.autoPassOption` | P1 |
| GAP 3: shouldStop always true | `ActionMapper` + `ShouldStopEvaluator` | P2 (UX) |
| GAP 4: Transient stops | `ClientAutoPassState.transientStops` | P2 |
| GAP 5: Yields | `ClientAutoPassState.yields` | P2 |
| GAP 6: decision_player divergence | `StateMapper.decisionPlayer` | P3 |
| GAP 7: next_phase/next_step | `TurnInfo` builder | P3 (cosmetic) |
| GAP 8: TimerStateMessage | `TimerManager` (new) | P1 |
| GAP 9: Opponent-turn stops | `StopConfiguration.opponentTurn` | P1 |
| GAP 10: autoPassCancel no-op | `AutoPassReason.AutoPassCancel` | P0 (correctness) |

### Testing strategy

| Layer | Test Type | What |
|-------|-----------|------|
| `PriorityDecision` | Unit | Pure function: game state + config → decision. Fast, no engine. |
| `ClientAutoPassState` | Unit | Parse SetSettingsReq → state. Parse PerformActionResp → update. |
| `PhaseStopProfile` | Unit | Already tested. Add scope-aware tests. |
| `ShouldStopEvaluator` | Unit | Given actions + game state → which get shouldStop=true |
| `AutoPassEngine` integration | MatchFlowHarness | Full loop: play land → pass → verify skip/grant pattern |
| Recording conformance | New test class | Replay real Arena recording, compare priority grant/skip decisions |

---

## 4. Recording Decoder Enhancement (Phase 1 — Implement Now)

### Fields to add

#### ActionSummary — add priority-relevant fields

```kotlin
data class ActionSummary(
    val type: String,
    val instanceId: Int = 0,
    val grpId: Int = 0,
    val seatId: Int? = null,
    // NEW:
    val shouldStop: Boolean? = null,     // Action.shouldStop (field 12)
    val highlight: String? = null,       // Action.highlight (field 27) → enum name
    val abilityGrpId: Int? = null,       // Action.abilityGrpId (for yield matching)
)
```

#### ClientActionSummary — add autoPassPriority + yield fields

```kotlin
data class ClientActionSummary(
    val actions: List<ActionSummary>,
    // NEW:
    val autoPassPriority: String? = null,  // PerformActionResp.autoPassPriority enum name
    val setYield: String? = null,          // PerformActionResp.setYield enum name
    val yieldScope: String? = null,        // PerformActionResp.appliesTo enum name
    val yieldKey: String? = null,          // PerformActionResp.mapTo enum name
)
```

#### New: SettingsSummary — decode SetSettingsReq contents

```kotlin
data class SettingsSummary(
    val stops: List<StopSummary> = emptyList(),
    val transientStops: List<StopSummary> = emptyList(),
    val yields: List<YieldSummary> = emptyList(),
    val autoPassOption: String? = null,
    val stackAutoPassOption: String? = null,
    val smartStops: String? = null,
    val clearAllStops: Boolean = false,
    val clearAllYields: Boolean = false,
)

data class StopSummary(
    val stopType: String,
    val scope: String,
    val status: String,
)

data class YieldSummary(
    val abilityGrpId: Int = 0,
    val cardTitleId: Int = 0,
    val scope: String,
    val status: String,
)
```

#### DecodedMessage — add settings field

```kotlin
data class DecodedMessage(
    // ... existing fields ...
    val clientSettings: SettingsSummary? = null,  // NEW: decoded SetSettingsReq
)
```

#### EdictalMessage — decode contents

```kotlin
data class EdictalSummary(
    val actions: List<ActionSummary> = emptyList(),
    val autoPassPriority: String? = null,
)
```

### Analysis CLI enhancement

Add `just decode-priority <recording-dir>` command that:
1. Decodes recording with enhanced fields
2. Prints priority timeline:
   ```
   T1 Main1  AAR [Cast×3, Play×4, Pass] → Play (autoPass=None)
   T1 Main1  AAR [Cast×3, ActivateMana×2, Pass] → Cast (autoPass=None)
   ...skip 15 GSMs (auto-passed)...
   T2 Main1  AAR [Cast×2, Play×2, Pass] → Play (autoPass=None)
   ```
3. Prints settings changelog:
   ```
   Settings[0]: stops=[Main1:Team:Set, BeginCombat:Both:Set, ...] autoPass=ResolveMyStackEffects
   Settings[5]: stops=[+Upkeep:Team:Set] (delta from previous)
   ```
4. Prints shouldStop distribution:
   ```
   shouldStop=true:  Cast(45), Play(23), Activate(8)
   shouldStop=false: ActivateMana(31), FloatMana(17), Pass(97)
   ```

### Existing tooling alignment

- `SemanticTimeline` already produces a timeline view — extend it with priority columns
- `RecordingInspector` has `inspect()` — add priority summary to its output
- `just decode` CLI already calls `RecordingDecoderMain` — add `--priority` flag
- Debug panel `/api/recording/:session` — add priority fields to response

---

## 5. File Map

| Change | File | What |
|--------|------|------|
| Enhance | `recording/RecordingDecoder.kt` | Add shouldStop/highlight/abilityGrpId to ActionSummary, autoPassPriority to ClientActionSummary, SettingsSummary, EdictalSummary |
| Enhance | `recording/SemanticTimeline.kt` | Add priority columns to timeline |
| Enhance | `recording/RecordingDecoderMain.kt` | Add `--priority` flag |
| New | `recording/PriorityTimeline.kt` | Priority-focused recording analysis |
| Enhance | `recording/RecordingInspector.kt` | Add priority summary |
| Test | `recording/RecordingDecoderTest.kt` | Verify new fields decode correctly from real recordings |

---

## 6. Non-Goals (Explicit)

- **No engine changes in Phase 1.** Decoder-only. No bridge modifications.
- **No new priority logic.** Abstractions are designed but not implemented.
- **No shouldStop intelligence.** Just capture what Arena does; don't replicate it yet.
- **No test framework changes.** MatchFlowHarness stays as-is.
