---
summary: "Two-layer priority model (engine + session), diagnostic steps for stuck states, and what correct priority flow looks like."
read_when:
  - "debugging a stuck game or priority hang"
  - "diagnosing auto-pass not firing or firing incorrectly"
  - "modifying WebPlayerController or AutoPassEngine"
---
# Priority & Auto-Pass Debugging Playbook

How the two-layer priority system works, how to diagnose stuck states, and what "correct" looks like.

## Two-Layer Model

Priority decisions happen at two layers. Both must agree for the game to flow correctly.

### Layer 1: Engine-Side (`WebPlayerController.chooseSpellAbilityToPlay`)

Runs on the Forge engine thread. Returns `null` to pass priority (skips the bridge round-trip entirely) or a `SpellAbility` to play. Decision order (all skipped when `fullControl=true`):

1. **EndTurnFlag** — `autoPassUntilEndOfTurn` set by client End Turn action → pass. Cleared on turn boundary.
2. **SmartPhaseSkip** — own turn only, stack empty, no castable/playable/activatable non-mana action → pass.
3. **PhaseNotStopped** — own turn only, `PhaseStopProfile.isEnabled(player.id, phase)` is false → pass. NOT checked on opponent's turn — that's session-layer's job.
4. **Normal** — blocks on `awaitAction()` (`CompletableFuture`), waits for session thread to submit an action.

### Layer 2: Session-Side (`AutoPassEngine.autoPassAndAdvance`)

Runs on the Netty/session thread. Loops up to 50 iterations:

```
1. Game over?              → sendGameOver, return
2. drainPlayback           → sent AI diffs? re-evaluate
3. combatHandler           → STOP | SEND_STATE | CONTINUE
4. targetingHandler        → SENT_TO_CLIENT | AUTO_RESOLVED | NONE
5. checkHumanActions       → Grant | Skip
6. advanceOrWait           → submit pass or wait
```

**Critical:** Steps 3–4 run BEFORE step 5. Any `SEND_STATE` from the combat handler bypasses the action check. This is the main source of "stuck with nothing to do" bugs.

## What "Correct" Looks Like

### Human's Own Turn
- **Main1/Main2**: stop if castable spells, playable lands, or activatable abilities exist. Auto-pass if only Pass/mana actions.
- **Combat**: `DeclareAttackersReq` sent if legal attackers exist. Skip combat if no creatures.
- **Other phases** (Upkeep, Draw, End): auto-pass unless the player has instants/abilities to cast.
- **Phase stops**: `PhaseStopProfile` (HUMAN_DEFAULTS: Main1, Attackers, Blockers, Main2) gates engine-side. Client `SetSettingsReq` with `Team` scope overrides.

### Opponent's (AI) Turn
- **Engine-side**: NO phase stop check. `WebPlayerController` skips `PhaseNotStopped` during opponent turn — lets the engine run AI logic freely.
- **Session-side `checkHumanActions`**: returns `Skip(OnlyPassActions)` immediately for `isAiTurn` (before action check).
- **Session-side `advanceOrWait`**: checks `autoPassState.hasOpponentStop(phase)` — only stops if the client explicitly toggled this phase via `SetSettingsReq` with `Opponents` scope. Defaults empty.
- **Combat handler**: `SEND_STATE` for AI attacking (shows attackers), `STOP` for `DeclareBlockersReq` (human must assign blockers). `COMBAT_DAMAGE`/`COMBAT_END` should only stop if human has meaningful actions (not just Pass).

### AI Turn Flow (No Client Interaction)
If no opponent stops set and no combat with the human:
```
Engine: SmartPhaseSkip through all phases
Session: Skip(OnlyPassActions) on each, advanceOrWait submits pass
→ AI turn completes without client seeing "My Turn"
```

### AI Turn with Combat
```
Engine: stops at COMBAT_DECLARE_ATTACKERS (AI_DEFAULTS)
Session: combatHandler detects AI attacking, sends SEND_STATE (shows attackers)
         combatHandler sends DeclareBlockersReq → STOP (waits for client)
Client:  submits DeclareBlockersResp
Session: continues through damage/end
```

## Reference: Stop Stores & Combat Signals

See KDoc on `PhaseStopProfile`, `ClientAutoPassState`, and `CombatHandler.Signal` for the
phase-stop split rationale and combat signal contract. Short version: PhaseStopProfile is
engine-side own-turn gating, opponentStops is session-side opponent-turn gating, and
CombatHandler.Signal.SEND_STATE bypasses checkHumanActions (known bug pattern).

## Diagnosing Stuck States

### Quick Checks
```bash
# Current state
curl -s localhost:8090/api/state | jq .

# Last N priority decisions (both layers)
curl -s localhost:8090/api/priority-log | jq '.data[-10:]'

# Last N priority events (with detail)
curl -s localhost:8090/api/priority-events | jq '.data[-5:]'

# Latest messages (in/out)
curl -s localhost:8090/api/messages | jq '.data[-5:]'
```

### Stuck: "My Turn" But Nothing To Do

**Symptom:** Client shows "My Turn", only action is Pass.

**Diagnosis:**
1. Check `/api/priority-events` — look for `SEND_STATE` with `"combat damage"` or similar detail.
2. If source is combat handler → the `SEND_STATE` signal bypassed `checkHumanActions`.
3. Check if the human actually has meaningful actions (`Cast`, `Activate`, `Play`) or only `Pass`/`FloatMana`/`ActivateMana`.

**Fix:** Combat handler should return `CONTINUE` for informational phases (damage, end) unless the human has interactive decisions. Or add a safety net in `autoPassAndAdvance` that checks actions before honoring `SEND_STATE`.

### Stuck: Game Frozen, No Messages

**Symptom:** No outbound messages, client shows last state indefinitely.

**Diagnosis:**
1. Check `/api/state` — `activePlayer` empty or stale turn number.
2. Check priority log — last decision may be engine-side `Skip` that ran off without the session catching up.
3. Engine thread may be blocked on a prompt (`InteractivePromptBridge`) or action (`GameActionBridge`) that the session never submitted.

**Common causes:**
- `advanceOrWait` with `pending == null && !isAiTurn` — session waiting for engine, engine waiting for session → deadlock.
- Unhandled prompt type in `TargetingHandler` — engine blocks on `InteractivePromptBridge`, session doesn't see a prompt.
- Combat handler `STOP` but client response handler not wired for that message type.

### Stuck: Opponent Turn Never Ends

**Symptom:** AI phases fly through priority log but client never advances.

**Diagnosis:**
1. Check if `advanceOrWait` is hitting `awaitPriorityWithTimeout` and timing out.
2. Look for `AI_TURN_TIMEOUT` in priority events.
3. Engine thread may be stuck in AI decision-making (slow AI, infinite loop in AI strategy).

### Skipped: Human Turn Flies By

**Symptom:** Human's Main1/Main2 skipped, never got priority.

**Diagnosis:**
1. Check priority log for `Skip(PhaseNotStopped(...))` on human's own turn.
2. `PhaseStopProfile` missing the phase for human player — check if `SetSettingsReq` with `Team` scope cleared it, or if defaults are wrong.
3. Check for `Skip(SmartPhaseSkip)` — `PlayableActionQuery` thinks nothing is playable. Possible if hand is empty and no activated abilities.

### Skipped: Opponent Stop Not Working

**Symptom:** Clicked opponent-turn stop in UI, but game doesn't stop during AI turn.

**Diagnosis:**
1. Check `/api/messages` for inbound `SetSettingsReq` — verify `appliesTo=Opponents` and `status=Set`.
2. Check if `MatchSession.applyStopsToProfile` parsed the `Opponents` scope.
3. Check `autoPassState.opponentStops` — this is what `advanceOrWait` actually checks.
4. `advanceOrWait` only checks opponent stops when `pending != null` — if the engine hasn't reached a priority point yet, the check runs on the next loop iteration after `awaitPriority()` returns.

## Key Files

| File | Role |
|---|---|
| `bridge/WebPlayerController.kt` | Engine-side priority decisions |
| `server/AutoPassEngine.kt` | Session-side auto-pass loop |
| `server/CombatHandler.kt` | Combat phase detection and prompts |
| `server/TargetingHandler.kt` | Interactive prompt detection |
| `bridge/PhaseStopProfile.kt` | Per-player phase stop sets |
| `bridge/ClientAutoPassState.kt` | Client auto-pass settings + opponent stops |
| `server/MatchSession.kt` | Settings merge, action handling, state sending |
| `game/BundleBuilder.kt` | Proto message construction (actions, edictal, timer) |
| `game/mapper/ShouldStopEvaluator.kt` | Which action types are "meaningful" (stop-worthy) |

## Priority Decision Types

See `PriorityDecision` and `AutoPassReason` sealed classes for the full set.
Format in `/api/priority-log`: `Grant(phase, actionCount)` or `Skip(reason)`.

## Settings Flow

Client sends `SetSettingsReq` → `MatchSession.onSettings()`:

1. **Merge** incoming delta into accumulated `clientSettings` (keyed by `(stopType, appliesTo)`).
2. **Apply to profile**: `Team` scope → human player entry. `Opponents` scope → AI player entry.
3. **Mirror opponent stops** to `ClientAutoPassState.opponentStops` (separate from profile).
4. **Update auto-pass options** (`autoPassOption`, `stackAutoPassOption`).
5. **Echo** merged settings back via `SettingsResp`.

Client sends deltas only (one toggle per message). Server must accumulate, not replace.
