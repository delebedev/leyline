# Priority System Analysis: Forge Engine / Arena Protocol / Bridge

Cross-reference of how priority, passing, auto-pass, and phase stops work across
the three layers. Focus on behavioral gaps that affect the client connection.

---

## 1. Forge Engine Priority (PhaseHandler)

Source: `forge/forge-game/src/main/java/forge/game/phase/PhaseHandler.java`
Companion: `docs/priority-loop.md` (state machine diagram)

### Core Variables

| Variable | Type | Role |
|---|---|---|
| `pPlayerPriority` | Player | who currently holds priority |
| `pFirstPriority` | Player | who received priority first in this "round" |
| `playerTurn` | Player | active player (whose turn) |
| `givePriorityToPlayer` | boolean | whether current phase/step grants priority |

### mainLoopStep() (line 1042)

Single iteration of the priority state machine, called in a `while (!gameOver)` loop.

1. **If `givePriorityToPlayer`:**
   - Fire `GameEventPlayerPriority`
   - Run SBAs (rule 704.3) -- loops until no new triggers
   - Call `pPlayerPriority.getController().chooseSpellAbilityToPlay()`
   - If action taken: execute it, set `pFirstPriority = pPlayerPriority` (rule 117.3c -- all opponents must pass again)
   - If pass: `chosenSa == null`

2. **After priority resolves:**
   - Compute `nextPlayer = getNextPlayerAfter(priorityPlayer)`
   - If `pFirstPriority == nextPlayer` (all players passed in succession):
     - Stack empty -> end phase (`onPhaseEnd` / `advanceToNextPhase` / `onPhaseBegin`)
     - Stack non-empty -> `resolveStack()` (resolve top item, set `givePriorityToPlayer = true`, reset priority to active player)
   - Else: pass priority to `nextPlayer` (`pPlayerPriority = nextPlayer`)

### chooseSpellAbilityToPlay() Contract

Returns `List<SpellAbility>` or `null`:
- **Non-null list**: engine executes the spell/ability, player retains priority (rule 117.3c)
- **null**: player passes priority, engine rotates to next player

This is the seam where the bridge intercepts. Desktop Forge uses `InputPassPriority` (GUI blocking); the web bridge uses `GameActionBridge.awaitAction()`.

### Phases Without Priority

`onPhaseBegin()` sets `givePriorityToPlayer = false` for:

| Phase | Condition |
|---|---|
| UNTAP | Always (rule 502.4) |
| CLEANUP | Default (rule 514.3); exception if SBAs trigger or stack non-empty |
| COMBAT_DECLARE_ATTACKERS | If no combat (no legal attackers) |
| COMBAT_FIRST_STRIKE_DAMAGE | If no first/double strikers |
| COMBAT_DAMAGE | If no damage to assign |

### autoPassCancel()

Called by the engine at:
- **Cleanup phase** (`PhaseHandler.java:414`): prevents auto-pass from wrapping to next turn
- **Stack add** (`MagicStack.java:330`): when opponent casts, cancel auto-pass so player sees it
- **startGame** (`GameAction.java:2384`): reset at game start

In desktop Forge, this delegates to the GUI layer. In the web bridge, `WebGuiGame.autoPassCancel()` is a **no-op stub** -- the bridge handles auto-pass differently via `GameActionBridge.autoPassUntilEndOfTurn`.

### Stack Resolution

`MagicStack.resolveStack()`:
1. Freeze stack
2. `resetPriority()` -> active player gets priority after
3. Check fizzle (illegal targets)
4. `AbilityUtils.resolve(sa)` -- execute effect
5. `onStackResolved()` -> sets `givePriorityToPlayer = true`

Key: after resolution, priority goes to the **active player**, not the spell's controller.

---

## 2. Arena Client Priority Protocol

Sources: `mtga-internals/docs/phase-transitions-and-autopass.md`, `auto-pass-protocol.md`, `action-submission.md`

### TurnInfo (in every GameStateMessage)

| Field | Purpose |
|---|---|
| `phase` / `step` | Current phase and step |
| `turn_number` | Turn counter |
| `active_player` | Seat of turn owner |
| `priority_player` | Seat of who has priority NOW |
| `decision_player` | Seat of who must decide NOW (usually = priority_player) |
| `next_phase` / `next_step` | Hint for phase ladder UI |

### Priority Flow

```
SERVER                                CLIENT
  |--- GameStateMessage --------------->|  (TurnInfo.priority_player = seat N)
  |--- ActionsAvailableReq ------------>|  (actions[] + inactiveActions[])
  |                                     |-- client checks auto-pass
  |<--- PerformActionResp --------------|  (action + autoPassPriority flag)
```

**Critical:** the server MUST include `ActionType.Pass (5)` in `actions` whenever the player can pass. Without it, `CanPass` returns false, pass button is disabled, and auto-pass cannot fire.

### PerformActionResp Fields

| Field | Type | Purpose |
|---|---|---|
| `actions` | repeated Action | Usually one action |
| `auto_pass_priority` | AutoPassPriority | 0=explicit pass, 1=full control, 2=auto-pass ok |
| `set_yield` | SettingStatus | Inline yield toggle |
| `applies_to` | SettingScope | Yield scope |
| `map_to` | SettingKey | Yield key (ByAbility/ByCardTitle) |

### AutoPassPriority Semantics

| Value | Meaning | Server behavior |
|---|---|---|
| 0 (None) | Explicit pass action | Normal MTG priority pass |
| 1 (No) | Full control enabled | MUST give priority back after every state change |
| 2 (Yes) | Auto-pass ok | MAY skip priority for trivial state changes |

### Auto-Pass System

#### AutoPassOption enum

| Value | Name | Behavior |
|---|---|---|
| 0 | None | No auto-pass |
| 1 | Turn | Pass for rest of turn |
| 2 | UnlessAction | Pass unless player has playable action |
| 3 | EndStep | Pass until end step |
| 6 | ResolveMyStackEffects | **Default** -- auto-pass own stack resolutions |
| 7 | FullControl | Never auto-pass |
| 9 | ResolveAll | Resolve everything |

Client default = `ResolveMyStackEffects (6)`.

#### TryAutoRespond (client-side)

```pseudocode
bool TryAutoRespond():
    if !_pendingAutoPass:        return false
    if !AutoPassEnabled:         return false
    if !request.CanPass:         return false
    request.SubmitPass()
    return true
```

Auto-respond ONLY fires Pass. Never auto-submits non-pass actions through this path.

### shouldStop Field

On each `Action` in `ActionsAvailableReq`, `shouldStop = true` means the client should break auto-pass and show the action to the player. Combined with `highlight` field for visual urgency (Cold/Tepid/Hot/Counterspell).

### Settings System (Stops, Yields)

`SetSettingsReq` carries:
- **stops** (repeated Stop): phase/step stops per SettingScope (Team/Opponents/AnyPlayer)
- **transient_stops**: temporary stops for current interaction only
- **yields** (repeated AutoYield): auto-yield to specific triggers (by abilityGrpId or cardTitleId)
- **auto_pass_option** / **stack_auto_pass_option**: current auto-pass modes

### EdictalMessage (type 54)

Server-forced action. Wraps a `ClientToGREMessage` (as if client submitted it). Used for:
- Timer timeout forced pass
- Forced concession
- Not for auto-pass flow (that is client-side via settings)

### TimerStateMessage (type 56)

Per-player timer state. Types: Decision (rope), Inactivity, ActivePlayer, NonActivePlayer, MatchClock.

### Arena's Player-Facing Priority UX (Deep Dive)

Arena's priority system is a **three-layer design** combining server protocol, client-side auto-pass logic, and "smart priority" heuristics. Understanding all three layers is essential because our bridge must satisfy the protocol layer while the client handles the UX layer autonomously.

#### Layer 1: Server Protocol (GRE)

The server (GRE) always follows MTG rules faithfully:
- Priority is granted per the comprehensive rules (rule 117)
- Every priority grant sends `ActionsAvailableReq` with the full legal action set
- The server never skips a priority grant — it always asks the client

However, the server cooperates with the client's auto-pass via two mechanisms:
1. **`autoPassPriority` on `PerformActionResp`**: client tells server "I'm in auto-pass mode, you may optimize"
2. **`shouldStop` on individual actions**: server hints "this action is worth breaking auto-pass for"

#### Layer 2: Client-Side Auto-Pass

The client has its own priority-passing logic that runs *before* showing anything to the player:

**Keyboard controls map to auto-pass modes:**

| Key | Mode | Behavior |
|-----|------|----------|
| Space | — | Pass priority once (single priority grant) |
| Enter | UnlessAction (2) | Pass until opponent does something OR you have a playable response |
| Shift+Enter | Turn (1) | Hard pass — pass all priority for rest of turn, even through opponent actions |
| Ctrl (hold) | FullControl (7) | Temporary full control — stop at every priority grant while held |
| Ctrl+Shift | FullControl (7) | Permanent full control — stop at every priority grant until toggled off |

**"Resolve All" (Z key or button):** Maps to `ResolveAll (9)` — auto-passes through all stack resolutions without stopping. Combined with `stack_auto_pass_option` to control stack-specific behavior separately from phase-pass behavior.

**Default mode is `ResolveMyStackEffects (6)`:** Auto-pass when your own spells/abilities resolve (you don't need to respond to your own stuff resolving), but stop when opponent's things resolve (you might want to respond).

The client's `TryAutoRespond` is extremely simple — it only ever submits Pass. It never auto-submits a non-pass action. This means auto-pass is purely about *skipping* priority, never about *choosing* actions automatically.

#### Layer 3: Smart Priority (Server-Side Heuristics)

"Smart priority" is Arena's server-side intelligence layer that decides whether a priority grant is worth interrupting the player for. This is implemented via `shouldStop` and `highlight` fields on actions.

**The core principle:** Arena only stops the player if they have a *meaningful* play available. Having cards in hand isn't enough — the cards must be *relevant* to the current game state.

**Known smart priority rules (from patch notes + community documentation):**

| Situation | Behavior |
|-----------|----------|
| Opponent controls Sheoldred | Stop in your upkeep (so you can remove it before draw trigger) |
| You control Teferi, Hero of Dominaria | Stop in your end step (to tap lands for mana before untap trigger) |
| Opponent flashes in a creature during your end step | Re-grant priority (so you can respond to the new threat) |
| You have a beginning-of-combat trigger | Stop in main phase before combat (so you can act before the trigger) |
| Urza's Saga chapter III on stack | Stop to make a Construct or tap for mana before it sacrifices |
| Stack has opponent's spell + you have a counterspell | `shouldStop=true, highlight=Counterspell` — breaks auto-pass with visual emphasis |
| Stack has opponent's spell + you have only creatures/sorceries | `shouldStop=false` — no reason to stop, nothing relevant to cast |

**What smart priority does NOT cover** (requires manual full control):
- Lion's Eye Diamond timing tricks (activate in response to your own spell)
- Casting Vampiric Tutor in your own upkeep
- Any "fancy play" involving unusual priority timing

**Key insight for our bridge:** Smart priority is a SERVER responsibility. The server must analyze the player's available actions against the game state and set `shouldStop` intelligently. Our bridge currently sets `shouldStop=true` on everything — this is safe but produces a worse UX than real Arena (too many stops).

#### How the Three Layers Interact

```
Priority granted (MTG rules) ──→ Server builds ActionsAvailableReq
                                      │
                                      ├─ shouldStop=true on relevant actions?
                                      │     YES → client shows prompt (breaks auto-pass)
                                      │     NO  → client's TryAutoRespond fires Pass
                                      │
                                      ├─ client in FullControl mode?
                                      │     YES → always show prompt regardless of shouldStop
                                      │
                                      ├─ client in Turn/Shift+Enter hard-pass?
                                      │     YES → auto-pass even through shouldStop
                                      │     (but server can override via EdictalMessage if needed)
                                      │
                                      └─ client has phase stop set?
                                            YES → break auto-pass at that phase
                                            NO  → continue auto-passing
```

**Important subtlety:** `shouldStop` is per-action, not per-request. A single `ActionsAvailableReq` can have some actions with `shouldStop=true` and others with `shouldStop=false`. The client breaks auto-pass if ANY action has `shouldStop=true`.

#### Phase Ladder and Stops

The phase ladder UI (toggled with L key) shows phase/step icons. Players click to toggle stops:
- **Regular click:** Toggle persistent stop for that phase (own turn or opponent's turn)
- **Right-click:** Set transient stop (one-shot, applies once then auto-removes)

Stops are sent to server via `SetSettingsReq` with `SettingScope`:
- `Team` = your own turn
- `Opponents` = opponent's turn
- `AnyPlayer` = both

Default stops (not explicitly set, but implicit in client behavior):
- Own turn: First Main, Declare Attackers, Declare Blockers, Second Main
- Opponent turn: Beginning of Combat, Declare Attackers, Declare Blockers

#### Yields (Auto-Accept Triggers)

When a recurring trigger fires repeatedly (e.g., Ajani's Pridemate gaining counters), players can:
- Right-click the trigger on stack → "Always Yield"
- This sends `AutoYield` in `SetSettingsReq` keyed by `abilityGrpId` or `cardTitleId`
- Server then sets `shouldStop=false` for that trigger, and client auto-passes

Yields scope: `ByAbility` (specific ability) or `ByCardTitle` (all abilities of that card).

---

## 3. Current Bridge Implementation

### WebPlayerController.chooseSpellAbilityToPlay()

File: `src/main/kotlin/leyline/bridge/WebPlayerController.kt:938`

The bridge intercept point. Three layers of auto-pass before blocking on the bridge:

1. **EndTurn auto-pass**: if `autoPassUntilEndOfTurn` is set, return null immediately. Cleared on turn boundary.

2. **Smart phase skip** (engine-side): on own turn only, when stack is empty and `PlayableActionQuery.hasPlayableNonManaAction()` returns false, auto-pass. Skips phases where the player literally has nothing to do.

3. **PhaseStopProfile check**: if the current phase is not in the player's enabled stops, auto-pass. This is the bridge-level equivalent of Arena's stop toggles.

If none of those fire, blocks on `GameActionBridge.awaitAction()`.

### GameActionBridge

File: `src/main/kotlin/leyline/bridge/GameActionBridge.kt`

- `awaitAction(PendingActionState)` -- blocks engine thread on `CompletableFuture.get()`, returns `PlayerAction`
- `submitAction(actionId, PlayerAction)` -- called from MatchSession to complete the future
- `PendingActionState`: phase, activePlayerId, priorityPlayerId
- On timeout (30s default): auto-passes (`PlayerAction.PassPriority`)
- `autoPassUntilEndOfTurn`: AtomicBoolean, matches desktop "End Turn" button

### PlayerAction Sealed Class

```kotlin
sealed class PlayerAction {
    data object PassPriority
    data class CastSpell(cardId, abilityId?, targets)
    data class ActivateAbility(cardId, abilityId, targets)
    data class ActivateMana(cardId)
    data class PlayLand(cardId)
    data class DeclareAttackers(attackerIds, defenderPlayerId?, defenderCardId?)
    data class DeclareBlockers(blockAssignments)
    data object EndTurn
}
```

### AutoPassEngine (session-side)

File: `src/main/kotlin/leyline/server/AutoPassEngine.kt`

Runs on the Netty thread after each action response. Loops up to 50 iterations:

1. Drain AI playback diffs
2. Check combat phase -> delegate to CombatHandler
3. Check pending interactive prompts -> delegate to TargetingHandler
4. Check if human has meaningful actions (`BundleBuilder.shouldAutoPass`): returns true if all actions are Pass/FloatMana/ActivateMana
5. If only pass available: submit auto-pass via bridge, send EdictalMessage to client (except during AI turn)

Two-layer auto-pass:
- **Engine-side** (WebPlayerController): `PlayableActionQuery.hasPlayableNonManaAction` -- skips before bridge round-trip, own-turn only
- **Session-side** (AutoPassEngine): `BundleBuilder.shouldAutoPass` -- checks proto action list, covers opponent-turn priority

### PhaseStopProfile

File: `src/main/kotlin/leyline/bridge/PhaseStopProfile.kt`

Per-player set of enabled phases. Checked in WebPlayerController before blocking on bridge.

Defaults:
- **Human own turn**: MAIN1, COMBAT_DECLARE_ATTACKERS, COMBAT_DECLARE_BLOCKERS, MAIN2
- **AI turn (opponent)**: COMBAT_BEGIN, COMBAT_DECLARE_ATTACKERS, COMBAT_DECLARE_BLOCKERS, END_OF_TURN

### StopTypeMapping

File: `src/main/kotlin/leyline/game/mapper/StopTypeMapping.kt`

Maps Arena `StopType` enum to Forge `PhaseType`. Handles `SetSettingsReq` stops with `SettingScope` filtering (Team vs Opponents vs AnyPlayer).

### MatchSession.onSettings()

File: `src/main/kotlin/leyline/server/MatchSession.kt:317`

Receives `SetSettingsReq`, saves client settings, applies Team-scope stops to `PhaseStopProfile`. Opponent-scope stops are logged but deferred (v1 uses AI_DEFAULTS).

### ActionMapper.buildActions()

File: `src/main/kotlin/leyline/game/mapper/ActionMapper.kt`

Builds `ActionsAvailableReq` proto from Forge game state. Sets `shouldStop = true` on all Cast, Play, and Activate actions. Always includes `ActionType.Pass` and `ActionType.FloatMana`.

---

## 4. Mismatches and Alignment Gaps

### GAP 1: autoPassPriority field is ignored

**Arena sends** `PerformActionResp.autoPassPriority` (0/1/2) with every action response. The bridge **does not read it**. `MatchSession.onPerformAction()` only looks at `actionsList[0].actionType`.

**Impact**: Full control mode (value 1) is not respected. When the client sends `autoPassPriority = 1`, the server should always give priority back after every state change. Currently the bridge auto-passes through phases regardless.

**Fix**: Read `autoPassPriority` from `PerformActionResp`. When value 1 (No/FullControl), disable smart phase skip and PhaseStopProfile skipping for that priority grant. When value 2 (Yes), allow aggressive auto-pass.

### GAP 2: AutoPassOption not tracked

**Arena sends** `SetSettingsReq.settings.auto_pass_option` and `stack_auto_pass_option` to communicate the player's auto-pass mode (None/Turn/UnlessAction/EndStep/ResolveMyStackEffects/FullControl/ResolveAll).

**Bridge behavior**: `MatchSession.onSettings()` saves `clientSettings` but only processes `stops`. The `auto_pass_option` and `stack_auto_pass_option` fields are **not read or applied**.

**Impact**: The "Resolve All" button, "Pass Until End Step" toggle, and full control toggle in the client phase ladder have no server-side effect. The bridge always applies its own auto-pass heuristics regardless of what the client requested.

**Fix**: Parse `auto_pass_option` from settings. Map to bridge behavior:
- `Turn (1)` -> `autoPassUntilEndOfTurn = true`
- `FullControl (7)` -> disable all auto-pass (engine-side smart skip and session-side shouldAutoPass)
- `ResolveAll (9)` -> skip priority during stack resolution (don't send ActionsAvailableReq after each resolution)
- `UnlessAction (2)` -> only stop if player has non-pass actions (already close to current behavior)
- `ResolveMyStackEffects (6)` -> auto-pass when own spells resolve (already close to current behavior for the human-as-active case)

### GAP 3: shouldStop is always true

`ActionMapper` sets `shouldStop = true` on every Cast, Play, and Activate action. Arena uses shouldStop selectively:
- `shouldStop = true` + `highlight = Hot/Counterspell` when opponent acts and player has a relevant response
- `shouldStop = false` for actions available during auto-pass (player can act but server doesn't need to break auto-pass)

**Impact**: The client always breaks auto-pass at every priority stop where the player has actions. This is correct behavior for now (conservative), but prevents the "smooth auto-pass" experience where the game flows without interruption when the player has cards but they aren't relevant (e.g., having a creature with an irrelevant activated ability during opponent's main phase).

**Fix**: Implement `shouldStop` logic:
- During opponent's turn: only set `shouldStop = true` if the action is a relevant response (instant, flash, activated ability usable at instant speed)
- Add `highlight` field mapping: `Counterspell` when player has a counter available, `Hot` when relevant instant, `Cold` for marginal actions

### GAP 4: Transient stops not implemented

**Arena sends** `transient_stops` (field 15 on SettingsMessage) for one-time phase stops (e.g., right-clicking a phase on the ladder). These should apply once then be discarded.

**Bridge behavior**: `MatchSession.onSettings()` does not process `transient_stops`.

**Impact**: Client-side "stop here once" clicks have no effect. Low priority for PvAI, but blocks proper PvP phase ladder interaction.

### GAP 5: Yields not implemented

**Arena sends** `yields` (AutoYield list) in SetSettingsReq, plus inline yields in `PerformActionResp` (fields 3-5). Yields auto-pass when a specific trigger/card goes on the stack.

**Bridge behavior**: `WebGuiGame.shouldAutoYield()` always returns false. `setShouldAutoYield()` is a no-op.

**Impact**: "Always yield to this trigger" has no effect. Player must manually pass every time a recurring trigger fires. Annoying but not game-breaking.

### GAP 6: decision_player may diverge from priority_player

**Arena expects** `TurnInfo.decision_player` to differ from `priority_player` during triggered abilities, replacement effects, and simultaneous choices. The current bridge sets `decision_player` equal to the priority player in most cases.

**Current state**: `StateMapper.kt:66` sets `decisionPlayer` based on `handler.priorityPlayer`, and `BundleBuilder.kt:265` sets it to `prioritySeat`. This is correct for normal priority but may not handle non-priority-player decisions (e.g., opponent choosing targets for their own trigger during your priority).

**Impact**: Minor -- mostly affects UI hint for "who is thinking" indicator. Could confuse client state during complex triggered ability resolution.

### GAP 7: next_phase / next_step not set

**Arena expects** `TurnInfo.next_phase` and `TurnInfo.next_step` to be set for the phase ladder "hint glow" (upcoming phase highlight).

**Bridge behavior**: These fields are not set in StateMapper or BundleBuilder TurnInfo construction.

**Impact**: Phase ladder "next phase" glow does not work. Cosmetic only.

### GAP 8: No TimerStateMessage support

**Arena expects** `TimerStateMessage (type 56)` or inline `timers` in GameStateMessage for the rope/timer UI. The bridge does not send these.

**Impact**: No rope timer visible in the client UI. The client still functions (bridge has its own 30s timeout), but the player has no visual countdown. Could confuse players who expect to see the rope.

**Fix**: Send `TimerStateMessage` with `TimerType.Decision`, `duration_sec = 30`, synced to `GameActionBridge.deadlineMs`. Start timer when priority is granted, stop when action is received.

### GAP 9: Opponent-turn stop scope handling is deferred

`MatchSession.onSettings()` processes Team-scope stops but defers Opponents-scope stops (uses hardcoded AI_DEFAULTS). When the client toggles a stop for "opponent's turn" on the phase ladder, it has no effect.

**Impact**: Player cannot customize which phases to stop at during opponent's turn. Current AI_DEFAULTS (COMBAT_BEGIN, COMBAT_DECLARE_ATTACKERS, COMBAT_DECLARE_BLOCKERS, END_OF_TURN) are reasonable but not player-configurable.

**Fix**: Process Opponents-scope stops in `applyStopsToProfile()` using the AI player's ID.

### GAP 10: autoPassCancel is a no-op

When the engine calls `autoPassCancel()` (e.g., opponent casts a spell, stack becomes non-empty), `WebGuiGame.autoPassCancel()` does nothing. In desktop Forge, this breaks the current auto-pass state so the player sees the new stack item.

**Impact**: If the bridge is in an auto-pass loop and the opponent casts a spell, the bridge may auto-pass through the priority granted after the spell goes on the stack. The two-layer auto-pass system partially compensates (session-side `shouldAutoPass` returns false when non-Pass actions exist), but the engine-side smart phase skip in `WebPlayerController` does NOT check stack emptiness during opponent's turn (only during own turn).

**Fix**: Wire `autoPassCancel()` in WebGuiGame/WebPlayerController to clear any pending auto-pass state. At minimum, set a flag that `chooseSpellAbilityToPlay` checks to force blocking on the bridge for the next priority grant.

---

## 5. Priority Flow: End-to-End Trace

### Happy Path: Player casts a spell on own turn

```
Engine thread                         Session thread (Netty)
     |                                       |
  mainLoopStep()                             |
  givePriority=true                          |
  SBA check OK                              |
  chooseSpellAbilityToPlay()                 |
    -> smart phase skip: has playable? yes   |
    -> PhaseStopProfile: MAIN1 enabled? yes  |
    -> awaitAction(MAIN1, seat1, seat1)      |
       [blocks on CompletableFuture]         |
       prioritySignal.signal() ------------->|
                                             | awaitPriority() wakes up
                                             | sendRealGameState(bridge)
                                             |   -> StateMapper.buildDiffFromGame()
                                             |   -> ActionMapper.buildActions()
                                             |   -> send GSM Diff + ActionsAvailableReq
                                             |
                                             | [client receives, shows actions]
                                             | [client clicks "Cast Lightning Bolt"]
                                             |
                                             | onPerformAction(PerformActionResp)
                                             |   actionType = Cast
                                             |   forgeCardId = bridge.getForgeCardId(iid)
                                             |   submitAction(pending.id, CastSpell(id))
       [future completes] <------------------|
    -> return executeCastSpell(cardId)        |
       [spell on stack]                      |
  pFirstPriority = current (117.3c)          |
  [loop back to chooseSpellAbility]          |
    -> targeting prompt if needed            |
    -> or awaitAction for next priority      |
```

### Auto-pass path: Opponent's turn, no relevant actions

```
Engine thread                         Session thread
     |                                       |
  mainLoopStep()                             |
  givePriority=true (UPKEEP, opp turn)       |
  chooseSpellAbilityToPlay()                 |
    -> smart skip: OWN turn only, skip       |
    -> PhaseStopProfile: UPKEEP not in       |
       AI defaults? AI_DEFAULTS has          |
       COMBAT_BEGIN etc, not UPKEEP          |
    -> return null (auto-pass)               |
  [priority rotates to next player]          |
  [phase advances]                           |
  ...                                        |
  givePriority=true (COMBAT_BEGIN, opp turn)  |
  chooseSpellAbilityToPlay()                 |
    -> PhaseStopProfile: COMBAT_BEGIN in     |
       AI_DEFAULTS? yes                      |
    -> awaitAction(COMBAT_BEGIN, seat2, seat1)|
       [blocks] --------------------------->|
                                             | awaitPriority() wakes
                                             | checkHumanActions:
                                             |   buildActions -> only Pass+FloatMana+Mana
                                             |   shouldAutoPass = true
                                             | advanceOrWait:
                                             |   send EdictalMessage (pass)
                                             |   submitAction(pending, PassPriority)
       [completes, auto-pass] <-------------|
    -> return null                           |
  [phase advances]                           |
```

---

## 6. Recommendations (Priority Order)

### Must-Fix for Basic Correctness

1. **GAP 10 (autoPassCancel)**: Wire it to interrupt auto-pass when opponent acts. Without this, the bridge can auto-pass through responses to opponent spells during their turn.

2. **GAP 1 (autoPassPriority)**: Read the field from PerformActionResp. At minimum, when value is 1 (FullControl), disable auto-pass for the next priority grant.

### Should-Fix for Client Experience

3. **GAP 8 (TimerStateMessage)**: Send timer state so the rope UI works. The data is already available (`GameActionBridge.deadlineMs`).

4. **GAP 7 (next_phase/next_step)**: Set these in TurnInfo. Straightforward mapping from Forge's `PhaseType.getNext()`.

5. **GAP 9 (Opponents-scope stops)**: Process in `applyStopsToProfile()`. Small change, enables client phase ladder for opponent's turn.

### Nice-to-Have for Full Fidelity

6. **GAP 2 (AutoPassOption tracking)**: Full auto-pass mode tracking. Complex but enables all phase ladder auto-pass controls.

7. **GAP 3 (shouldStop intelligence)**: Selective shouldStop based on action relevance. Requires analyzing whether instant-speed actions are "relevant" responses.

8. **GAP 4 (Transient stops)**: One-shot stops from phase ladder clicks. Needs transient state per turn.

9. **GAP 5 (Yields)**: Auto-yield for recurring triggers. Needs per-player yield storage keyed by abilityGrpId/cardTitleId.

10. **GAP 6 (decision_player)**: Correct divergence from priority_player during triggered abilities. Edge case, only matters for complex board states.

---

## 7. Source File Index

| Layer | File | Role |
|---|---|---|
| Engine | `forge-game/.../phase/PhaseHandler.java` | Main game loop, priority state machine |
| Engine | `forge-game/.../player/PlayerController.java` | Abstract `chooseSpellAbilityToPlay`, `autoPassCancel` |
| Engine | `forge-game/.../zone/MagicStack.java` | Stack resolution, `autoPassCancel` on add |
| Engine | `forge-game/.../event/GameEventPlayerPriority.java` | Priority change event |
| Engine | `forge-game/.../player/actions/PassPriorityAction.java` | Pass priority action record |
| Bridge | `src/.../bridge/WebPlayerController.kt` | Engine-side priority intercept |
| Bridge | `src/.../bridge/GameActionBridge.kt` | CompletableFuture bridge, timeout, autoPassUntilEndOfTurn |
| Bridge | `src/.../bridge/PrioritySignal.kt` | Semaphore-based wake signal |
| Bridge | `src/.../bridge/PhaseStopProfile.kt` | Phase stop state per player |
| Bridge | `src/.../bridge/PlayableActionQuery.kt` | Engine-side playable action check |
| Bridge | `src/.../bridge/WebGuiGame.kt` | GUI stubs (autoPassCancel = no-op) |
| Session | `src/.../server/MatchSession.kt` | Action dispatch, settings handling |
| Session | `src/.../server/AutoPassEngine.kt` | Session-side auto-pass loop |
| Session | `src/.../game/mapper/ActionMapper.kt` | Builds ActionsAvailableReq proto |
| Session | `src/.../game/mapper/StopTypeMapping.kt` | Arena StopType to Forge PhaseType |
| Session | `src/.../game/BundleBuilder.kt` | Message assembly, edictalPass, shouldAutoPass |
| Session | `src/.../game/GameBridge.kt` | awaitPriority, bridge lifecycle |
| Arena ref | `mtga-internals/docs/phase-transitions-and-autopass.md` | Complete priority/stop/autopass reference |
| Arena ref | `mtga-internals/docs/auto-pass-protocol.md` | AutoPassOption, shouldStop, settings |
| Arena ref | `mtga-internals/docs/action-submission.md` | Client action submission flow |
| Arena ref | `mtga-internals/docs/edictal-message.md` | Server-forced actions |
| Arena ref | `mtga-internals/docs/timer-protocol.md` | Timer/rope protocol |
