# Forge Priority Loop State Machine

> Reference for debugging game-loop issues. Eliminates guesswork
> from traces — read this first, then instrument the specific transition.
>
> Source: `forge-game/.../phase/PhaseHandler.java` (lines 1035-1183)

## The Loop

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

## Key Variables

| Variable | Type | Meaning |
|---|---|---|
| `pPlayerPriority` | Player | who currently has priority |
| `pFirstPriority` | Player | who received priority first in this "round" — when it rotates back here, all have passed |
| `playerTurn` | Player | active player (whose turn it is) |
| `givePriorityToPlayer` | boolean | whether the current step should offer priority |

## The "Double-Pass" Mechanism

`pFirstPriority` is the key to understanding the loop:

1. Player gets priority, **acts** → `pFirstPriority = pPlayerPriority` (reset — everyone must pass again)
2. Player gets priority, **passes** → rotate to next player
3. When `nextPlayer == pFirstPriority` → all players have passed in succession
4. All passed + empty stack → phase ends
5. All passed + non-empty stack → resolve top of stack

After stack resolution, `resetPriority()` gives priority back to the **active player** (not the one who put the spell on the stack), and `onStackResolved()` sets `givePriorityToPlayer = true`.

## SBA Checking (Rule 704.3)

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

## Phase-Specific Behaviors

### No-Priority Phases

These phases skip the priority loop entirely (`givePriorityToPlayer = false`):

| Phase | Reason |
|---|---|
| UNTAP | Rule 502.4 — no player gets priority during untap |
| CLEANUP | Rule 514.3 — normally no priority (but see exception below) |
| COMBAT_FIRST_STRIKE_DAMAGE | Skipped if no first/double strikers |
| COMBAT_DAMAGE | Skipped if no damage to assign |
| COMBAT_DECLARE_ATTACKERS | Skipped if not in combat (no legal attackers) |

### Cleanup Exception (Rule 514.3a)

Cleanup normally gives no priority, but:

```java
// After discard + remove damage:
if (game.getAction().checkStateEffects(true)) {
    bRepeatCleanup = true;
    givePriorityToPlayer = true;  // SBAs happened → priority
}
// Also:
if (!game.getStack().isEmpty() || game.getStack().hasSimultaneousStackEntries()) {
    bRepeatCleanup = true;
    givePriorityToPlayer = true;  // triggers on stack → priority
}
```

If SBAs trigger or the stack has items after cleanup actions, cleanup **repeats with priority**. This is how "discard Madness card during cleanup" works.

## Stack Resolution Detail

`MagicStack.resolveStack()` (line 599):

1. Freeze stack (prevent new entries during resolution)
2. `resetPriority()` → active player will get priority after
3. Check fizzle (illegal targets)
4. If fizzled: special handling for Bestow/Mutate (resolve as creature instead)
5. If not fizzled: `AbilityUtils.resolve(sa)` — execute the effect
6. Fire `AbilityResolves` trigger
7. Check static abilities
8. `finishResolving()`:
   - Remove spell from stack
   - Unfreeze stack
   - `onStackResolved()` → sets `givePriorityToPlayer = true`

## Combat Turn-Based Actions

Declared during `onPhaseBegin()`, not during the priority loop:

- **COMBAT_DECLARE_ATTACKERS**: stack freezes → `declareAttackersTurnBasedAction()` → unfreeze → priority only if combat is happening
- **COMBAT_DECLARE_BLOCKERS**: stack freezes → `declareBlockersTurnBasedAction()` → unfreeze → priority
- **COMBAT_DAMAGE**: `assignCombatDamage()` → `dealAssignedDamage()` → priority

The stack freeze during declarations prevents triggers from going on the stack mid-declaration.

## Debugging Checklist

When a game-loop bug occurs, check these in order:

1. **Which `mainLoopStep` state are you in?** Log `givePriorityToPlayer`, `phase`, `pPlayerPriority`, `pFirstPriority`
2. **Is the SBA loop stuck?** The 9-iteration cap in `checkStateEffects` should prevent infinite loops, but cascading triggers can re-enter
3. **Is `pFirstPriority` being reset correctly?** If not reset after an action, the "all passed" check fires too early (premature phase advance or stack resolution)
4. **Is `givePriorityToPlayer` correct for this phase?** Untap/cleanup should be false unless exceptions apply
5. **After stack resolution, does priority return to active player?** `resetPriority()` sets it to `playerTurn`, not the spell's controller

## Source Files

| File | What |
|---|---|
| `forge-game/.../phase/PhaseHandler.java:1042` | `mainLoopStep()` — the state machine |
| `forge-game/.../phase/PhaseHandler.java:1168` | `checkStateBasedEffects()` — SBA loop |
| `forge-game/.../phase/PhaseHandler.java:238` | `onPhaseBegin()` — turn-based actions per phase |
| `forge-game/.../zone/MagicStack.java:599` | `resolveStack()` — spell/ability resolution |
| `forge-game/.../GameAction.java:1404` | `checkStateEffects()` — individual SBA rules |
