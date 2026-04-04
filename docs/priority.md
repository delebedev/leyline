---
summary: "Forge engine priority state machine and Arena client priority protocol: phase loop, SBA checks, auto-pass, stops, yields."
read_when:
  - "debugging stuck priority or auto-pass issues"
  - "modifying priority passing or stop logic"
  - "understanding the Forge PhaseHandler game loop"
---
# Priority System

Forge engine priority state machine + Arena client priority protocol reference.

---

## Forge Engine: Priority Loop

> Source: `forge-game/.../phase/PhaseHandler.java`

`PhaseHandler.mainGameLoop()` calls `mainLoopStep()` until game over.
Each step is one iteration of the priority state machine:

```
                        ┌──────────────────────┐
                        │    onPhaseBegin()     │
                        │  (turn-based actions) │
                        └──────────┬───────────┘
                                   │
                                   │ givePriorityToPlayer?
                                   │
                          no ──────┼────── yes
                          │        │         │
                          │        │    ┌────▼─────────────┐
                          │        │    │ checkSBAs()      │◄──── loops until
                          │        │    │  rule 704.3      │      no new triggers
                          │        │    └────┬─────────────┘
                          │        │         │
                          │        │    ┌────▼─────────────┐
                          │        │    │ player.choose    │
                          │        │    │ SpellAbilityTo   │
                          │        │    │ Play()           │
                          │        │    └────┬─────────────┘
                          │        │         │
                          │        │    action / pass?
                          │        │     │          │
                          │        │  action       pass
                          │        │     │          │
                          │        │  ┌──▼────┐     │
                          │        │  │execute│     │
                          │        │  │spell/ │     │
                          │        │  │ability│     │
                          │        │  └──┬────┘     │
                          │        │     │          │
                          │        │  reset         │
                          │        │  pFirstPriority│
                          │        │  = current     │
                          │        │  (CR 117.3c)   │
                          │        │     │          │
                          │        │     └──► SBAs ─┘  (loop back for more actions)
                          │        │
                          └────────┼──────────────────────────────┐
                                   │                              │
                                   ▼                              │
                        ┌─────────────────────┐                   │
                        │ nextPlayer =        │                   │
                        │  getNextPlayerAfter │                   │
                        │  (priorityPlayer)   │                   │
                        └────────┬────────────┘                   │
                                 │                                │
                        ┌────────▼────────────┐                   │
                        │ pFirstPriority ==   │                   │
                        │   nextPlayer?       │                   │
                        │ (all passed?)       │                   │
                        └────────┬────────────┘                   │
                            no / \ yes                            │
                           /     \                                │
                   ┌──────▼┐   ┌──▼──────────────┐               │
                   │rotate │   │ stack empty?     │               │
                   │to next│   └──┬───────────────┘               │
                   │player │  yes │            no │               │
                   └───┬───┘      │               │               │
                       │    ┌─────▼──────┐  ┌─────▼────────┐     │
                       │    │ onPhaseEnd  │  │ resolveStack │     │
                       │    │ advance     │  │  → resetPri  │     │
                       │    │ onPhaseBegin│  │  → resolve   │     │
                       │    └────────────┘  │  → onStack   │     │
                       │                    │    Resolved() │     │
                       │                    └──────┬───────┘     │
                       │                           │              │
                       │              givePriorityToPlayer = true │
                       │              priority → active player    │
                       │                           │              │
                       └───────────────────────────┴──────────────┘
                                  (next mainLoopStep)
```

### Key Variables

| Variable | Type | Meaning |
|---|---|---|
| `pPlayerPriority` | Player | who currently has priority |
| `pFirstPriority` | Player | who received priority first in this "round" — when it rotates back here, all have passed |
| `playerTurn` | Player | active player (whose turn it is) |
| `givePriorityToPlayer` | boolean | whether the current step should offer priority |

### The "Double-Pass" Mechanism

`pFirstPriority` is the key to understanding the loop:

1. Player gets priority, **acts** → `pFirstPriority = pPlayerPriority` (reset — everyone must pass again)
2. Player gets priority, **passes** → rotate to next player
3. When `nextPlayer == pFirstPriority` → all players have passed in succession
4. All passed + empty stack → phase ends
5. All passed + non-empty stack → resolve top of stack

After stack resolution, `resetPriority()` gives priority back to the **active player** (not the one who put the spell on the stack), and `onStackResolved()` sets `givePriorityToPlayer = true`.

### SBA Checking (Rule 704.3)

`checkStateBasedEffects()` runs **before every priority grant**:

```java
do {
    game.getAction().checkStateEffects(false, allAffectedCards);
    if (game.isGameOver()) return true;
} while (game.getStack().addAllTriggeredAbilitiesToStack());
```

- `checkStateEffects` runs up to **9 iterations** internally (handles cascading SBAs)
- Each pass checks: toughness <= 0, lethal damage, deathtouch, legend rule, planeswalker uniqueness, world rule, +1/+1 and -1/-1 counter annihilation, etc.
- Any triggers from SBAs are added to the stack
- Loops until no new triggers are generated
- **All of this happens before the player sees priority**

This means: creature dies → death trigger → trigger on stack → *then* priority is offered.

### Phases Without Priority

`onPhaseBegin()` sets `givePriorityToPlayer = false` for:

| Phase | Reason |
|---|---|
| UNTAP | Rule 502.4 — no player gets priority during untap |
| CLEANUP | Rule 514.3 — normally no priority (exception if SBAs trigger or stack non-empty) |
| COMBAT_FIRST_STRIKE_DAMAGE | Skipped if no first/double strikers |
| COMBAT_DAMAGE | Skipped if no damage to assign |
| COMBAT_DECLARE_ATTACKERS | Skipped if not in combat (no legal attackers) |

### Cleanup Exception (Rule 514.3a)

Cleanup normally gives no priority, but if SBAs trigger or the stack has items after cleanup actions, cleanup **repeats with priority**. This is how "discard Madness card during cleanup" works.

### Stack Resolution

`MagicStack.resolveStack()`:

1. Freeze stack (prevent new entries during resolution)
2. `resetPriority()` → active player will get priority after
3. Check fizzle (illegal targets)
4. If fizzled: special handling for Bestow/Mutate (resolve as creature instead)
5. If not fizzled: `AbilityUtils.resolve(sa)` — execute the effect
6. Fire `AbilityResolves` trigger
7. Check static abilities
8. `finishResolving()`: remove from stack, unfreeze, `onStackResolved()` → `givePriorityToPlayer = true`

### Combat Turn-Based Actions

Declared during `onPhaseBegin()`, not during the priority loop:

- **COMBAT_DECLARE_ATTACKERS**: stack freezes → `declareAttackersTurnBasedAction()` → unfreeze → priority only if combat is happening
- **COMBAT_DECLARE_BLOCKERS**: stack freezes → `declareBlockersTurnBasedAction()` → unfreeze → priority
- **COMBAT_DAMAGE**: `assignCombatDamage()` → `dealAssignedDamage()` → priority

The stack freeze during declarations prevents triggers from going on the stack mid-declaration.

### Debugging Checklist

1. **Which `mainLoopStep` state are you in?** Log `givePriorityToPlayer`, `phase`, `pPlayerPriority`, `pFirstPriority`
2. **Is the SBA loop stuck?** The 9-iteration cap in `checkStateEffects` should prevent infinite loops, but cascading triggers can re-enter
3. **Is `pFirstPriority` being reset correctly?** If not reset after an action, the "all passed" check fires too early (premature phase advance or stack resolution)
4. **Is `givePriorityToPlayer` correct for this phase?** Untap/cleanup should be false unless exceptions apply
5. **After stack resolution, does priority return to active player?** `resetPriority()` sets it to `playerTurn`, not the spell's controller

### Engine Source Files

| File | What |
|---|---|
| `forge-game/.../phase/PhaseHandler.java:1042` | `mainLoopStep()` — the state machine |
| `forge-game/.../phase/PhaseHandler.java:1168` | `checkStateBasedEffects()` — SBA loop |
| `forge-game/.../phase/PhaseHandler.java:238` | `onPhaseBegin()` — turn-based actions per phase |
| `forge-game/.../zone/MagicStack.java:599` | `resolveStack()` — spell/ability resolution |
| `forge-game/.../GameAction.java:1404` | `checkStateEffects()` — individual SBA rules |

---

## Arena Client: Priority Protocol

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

### AutoPassOption Enum

| Value | Name | Behavior |
|---|---|---|
| 0 | None | No auto-pass |
| 1 | Turn | Pass for rest of turn |
| 2 | UnlessAction | Pass unless player has playable action |
| 3 | EndStep | Pass until end step |
| 6 | ResolveMyStackEffects | **Default** — auto-pass own stack resolutions |
| 7 | FullControl | Never auto-pass |
| 9 | ResolveAll | Resolve everything |

Client default = `ResolveMyStackEffects (6)`.

### Client-Side TryAutoRespond

```pseudocode
bool TryAutoRespond():
    if !_pendingAutoPass:        return false
    if !AutoPassEnabled:         return false
    if !request.CanPass:         return false
    request.SubmitPass()
    return true
```

Auto-respond ONLY fires Pass. Never auto-submits non-pass actions.

### shouldStop Field

On each `Action` in `ActionsAvailableReq`, `shouldStop = true` means the client should break auto-pass and show the action to the player. Combined with `highlight` field for visual urgency (Cold/Tepid/Hot/Counterspell).

`shouldStop` is per-action, not per-request. A single `ActionsAvailableReq` can have some actions with `shouldStop=true` and others with `shouldStop=false`. The client breaks auto-pass if ANY action has `shouldStop=true`.

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

---

## Arena Client: Three-Layer Priority UX

Arena's priority system combines server protocol, client-side auto-pass logic, and "smart priority" heuristics.

### Layer 1: Server Protocol (GRE)

The server always follows MTG rules faithfully:
- Priority is granted per the comprehensive rules (rule 117)
- Every priority grant sends `ActionsAvailableReq` with the full legal action set
- The server never skips a priority grant — it always asks the client

The server cooperates with the client's auto-pass via two mechanisms:
1. **`autoPassPriority` on `PerformActionResp`**: client tells server "I'm in auto-pass mode, you may optimize"
2. **`shouldStop` on individual actions**: server hints "this action is worth breaking auto-pass for"

### Layer 2: Client-Side Auto-Pass

The client has its own priority-passing logic that runs *before* showing anything to the player.

**Keyboard controls map to auto-pass modes:**

| Key | Mode | Behavior |
|-----|------|----------|
| Space | — | Pass priority once (single priority grant) |
| Enter | UnlessAction (2) | Pass until opponent does something OR you have a playable response |
| Shift+Enter | Turn (1) | Hard pass — pass all priority for rest of turn, even through opponent actions |
| Ctrl (hold) | FullControl (7) | Temporary full control — stop at every priority grant while held |
| Ctrl+Shift | FullControl (7) | Permanent full control — stop at every priority grant until toggled off |

**"Resolve All" (Z key or button):** Maps to `ResolveAll (9)` — auto-passes through all stack resolutions. Combined with `stack_auto_pass_option` to control stack-specific behavior separately from phase-pass behavior.

**Default mode is `ResolveMyStackEffects (6)`:** Auto-pass when your own spells/abilities resolve, but stop when opponent's things resolve (you might want to respond).

### Layer 3: Smart Priority (Server-Side Heuristics)

Arena's server-side intelligence layer that decides whether a priority grant is worth interrupting the player for. Implemented via `shouldStop` and `highlight` fields on actions.

**Core principle:** Arena only stops the player if they have a *meaningful* play available. Having cards in hand isn't enough — the cards must be *relevant* to the current game state.

**Known smart priority rules:**

| Situation | Behavior |
|-----------|----------|
| Opponent controls Sheoldred | Stop in your upkeep (remove it before draw trigger) |
| You control Teferi, Hero of Dominaria | Stop in your end step (tap lands before untap trigger) |
| Opponent flashes in a creature during your end step | Re-grant priority (respond to new threat) |
| You have a beginning-of-combat trigger | Stop in main phase before combat |
| Stack has opponent's spell + you have a counterspell | `shouldStop=true, highlight=Counterspell` |
| Stack has opponent's spell + you have only creatures/sorceries | `shouldStop=false` — nothing relevant |

**Not covered** (requires manual full control): LED timing tricks, Vampiric Tutor in own upkeep, any unusual priority timing.

### How the Three Layers Interact

```
Priority granted (MTG rules) --> Server builds ActionsAvailableReq
                                      |
                                      +-- shouldStop=true on relevant actions?
                                      |     YES -> client shows prompt (breaks auto-pass)
                                      |     NO  -> client's TryAutoRespond fires Pass
                                      |
                                      +-- client in FullControl mode?
                                      |     YES -> always show prompt regardless of shouldStop
                                      |
                                      +-- client in Turn/Shift+Enter hard-pass?
                                      |     YES -> auto-pass even through shouldStop
                                      |     (server can override via EdictalMessage if needed)
                                      |
                                      +-- client has phase stop set?
                                            YES -> break auto-pass at that phase
                                            NO  -> continue auto-passing
```

### Phase Ladder and Stops

The phase ladder UI (toggled with L key) shows phase/step icons:
- **Regular click:** Toggle persistent stop for that phase (own turn or opponent's turn)
- **Right-click:** Set transient stop (one-shot, applies once then auto-removes)

Stops are sent to server via `SetSettingsReq` with `SettingScope`:
- `Team` = your own turn
- `Opponents` = opponent's turn
- `AnyPlayer` = both

Default stops (implicit in client behavior):
- Own turn: First Main, Declare Attackers, Declare Blockers, Second Main
- Opponent turn: Beginning of Combat, Declare Attackers, Declare Blockers

### Yields (Auto-Accept Triggers)

When a recurring trigger fires repeatedly (e.g., Ajani's Pridemate gaining counters):
- Right-click the trigger on stack → "Always Yield"
- This sends `AutoYield` in `SetSettingsReq` keyed by `abilityGrpId` or `cardTitleId`
- Server then sets `shouldStop=false` for that trigger, and client auto-passes

Yields scope: `ByAbility` (specific ability) or `ByCardTitle` (all abilities of that card).
