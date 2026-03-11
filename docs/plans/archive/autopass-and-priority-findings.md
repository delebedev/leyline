# Auto-Pass, Priority & Phase Transitions — Findings

> Consolidated reference across forge-game, forge-web, forge-nexus, and MTGA
> client internals. Read before planning implementation work.
>
> Date: 2026-02-22

---

## 1. Architecture: Three Layers, Two Threads

```
                    MTGA Client (Unity)
                         │  protobuf/TLS
                    ┌────▼─────────────────┐
                    │   forge-nexus         │  Session thread
                    │   AutoPassEngine      │  (Netty I/O)
                    │   CombatHandler       │
                    │   TargetingHandler    │
                    │   BundleBuilder       │
                    └────┬─────────────────┘
                         │  CompletableFuture
                    ┌────▼─────────────────┐
                    │   forge-web           │  Engine thread
                    │   WebPlayerController │  (daemon)
                    │   PhaseStopProfile    │
                    │   GameActionBridge    │
                    │   PlayableActionQuery │
                    └────┬─────────────────┘
                         │  extends PlayerController
                    ┌────▼─────────────────┐
                    │   forge-game          │  Engine thread
                    │   PhaseHandler        │  (same daemon)
                    │   mainLoopStep()      │
                    │   MagicStack          │
                    └──────────────────────┘
```

**Engine thread** runs `PhaseHandler.mainLoopStep()` in a tight loop.
Blocks in `WebPlayerController.chooseSpellAbilityToPlay()` →
`GameActionBridge.awaitAction()` → `CompletableFuture.get()`.

**Session thread** (Netty I/O) receives client messages, completes futures,
then runs `AutoPassEngine.autoPassAndAdvance()` to auto-advance through
non-interactive phases before returning control to the client.

---

## 2. Engine State Machine (forge-game)

Source: `PhaseHandler.java:1042` — `mainLoopStep()`

### Priority loop

```
onPhaseBegin()
  └─ sets givePriorityToPlayer (false for UNTAP, CLEANUP, etc.)

if givePriorityToPlayer:
    checkSBAs()
    player.chooseSpellAbilityToPlay()   ← BLOCKS here
        null → pass priority
        SpellAbility → execute, reset pFirstPriority
    rotate to next player
    if all passed:
        stack empty → onPhaseEnd() + advanceToNextPhase() + onPhaseBegin()
        stack non-empty → resolveStack() → priority to active player
```

### Key engine variables

| Variable | Meaning |
|----------|---------|
| `pPlayerPriority` | Who currently has priority |
| `pFirstPriority` | Who received priority first this round (all-passed detection) |
| `playerTurn` | Active player (whose turn) |
| `givePriorityToPlayer` | Whether current step offers priority |

### Phases that skip priority (engine-level)

| Phase | Condition |
|-------|-----------|
| UNTAP | Always |
| CLEANUP | Unless SBAs trigger or stack non-empty |
| COMBAT_FIRST_STRIKE_DAMAGE | No first/double strikers |
| COMBAT_DAMAGE | No damage to assign |
| COMBAT_DECLARE_ATTACKERS | No legal attackers (not in combat) |

---

## 3. Web Layer (forge-web)

### WebPlayerController decision chain

`chooseSpellAbilityToPlay()` — lines 903-965:

```
1. Turn boundary? → reset autoPassUntilEndOfTurn
2. autoPassUntilEndOfTurn? → return null (pass)
3. smartPhaseSkip? (own turn + empty stack + no playable non-mana actions)
   → return null (pass)
4. PhaseStopProfile not enabled for current phase?
   → return null (pass)
5. actionBridge.awaitAction() → BLOCK engine thread
   → handle PassPriority / EndTurn / CastSpell / etc.
```

Steps 1-4 return immediately — engine never blocks, no messages sent. Fast path.

### PhaseStopProfile

Server-owned stop set per player. No persistence. Reset on game start.

**HUMAN_DEFAULTS**: Main1, DeclareAttackers, DeclareBlockers, Main2
**AI_DEFAULTS** (opponent's turn): CombatBegin, DeclareAttackers, DeclareBlockers, EndOfTurn

When a stop is not set → WebPlayerController auto-passes. Engine keeps running.
The auto-pass is invisible to the nexus session thread.

### Smart phase skip (ADR-008)

Extra auto-pass on own turn when `PlayableActionQuery.hasPlayableNonManaAction()`
returns false. Checks: playable lands, castable spells, non-mana activated abilities.
Respects timing restrictions. Disabled on opponent's turn (need priority for instants).

### autoPassUntilEndOfTurn

`GameActionBridge` flag. Set when client sends `EndTurn` action. Cleared on turn
boundary. Causes all remaining priority stops this turn to auto-pass.

---

## 4. Nexus Layer (forge-nexus)

### AutoPassEngine

`autoPassAndAdvance()` — main loop (max 50 iterations):

```
1. Game over? → sendGameOver, return
2. drainPlayback() → send queued AI-action diffs
3. combatHandler.checkCombatPhase()
   STOP → return (waiting for client combat response)
   SEND_STATE → send state, return
4. targetingHandler.checkPendingPrompt()
   SENT_TO_CLIENT → return
   AUTO_RESOLVED → continue loop
5. checkHumanActions() → shouldAutoPass(actions)?
   no → sendRealGameState, return
   yes → continue to step 6
6. advanceOrWait():
   human has pending + pass-only → edictalPass + PassPriority submit
   AI turn → wait for AI (30s timeout)
   else → wait for engine priority
```

### shouldAutoPass()

`BundleBuilder.shouldAutoPass(actions)` — returns true when all available
actions are `Pass`, `FloatMana`, or `ActivateMana`. This is the nexus-level
decision of "nothing interesting to do."

### edictalPass

Server-forced pass: `EdictalMessage` wrapping a `PerformActionResp` with
`ActionType.Pass`. Tells the client "I passed for you." Only sent during
human turn (skipped during AI turn to avoid interrupting animations).

### CombatHandler

Detects combat phases in the auto-pass loop:

| Phase | Human turn | AI turn |
|-------|-----------|---------|
| DECLARE_ATTACKERS | Send DeclareAttackersReq → STOP | If attackers: pace + SEND_STATE |
| DECLARE_BLOCKERS | If attackers: pace + SEND_STATE | Send DeclareBlockersReq → STOP |
| COMBAT_DAMAGE | Always: pace + SEND_STATE | Same |
| COMBAT_END | If attackers: SEND_STATE | Same |

### Client settings handling

`onSettings()` saves `SettingsMessage` and echoes `SetSettingsResp`. **Settings
are not applied** — stops/yields from the client are acknowledged but ignored.

### Handshake stop defaults

`HandshakeMessages.buildDefaultSettings()` sends stops matching real Arena:

| StopType | Team (own turn) | Opponents |
|----------|-----------------|-----------|
| PrecombatMain | Set | Clear |
| BeginCombat | Set | Set |
| DeclareAttackers | Set | Set |
| DeclareBlockers | Set | Set |
| PostcombatMain | Set | Clear |
| EndStep | Clear | Set |
| FirstStrikeDamage | Set | Set |
| Upkeep, Draw, CombatDamage, EndCombat | Clear | Clear |

---

## 5. MTGA Client Expectations (from doc 18)

### TurnInfo — every GameStateMessage

Fields: `phase`, `step`, `turn_number`, `active_player`, `priority_player`,
`decision_player`, `next_phase`, `next_step`. All must be set.

### Phase transition protocol

Every phase/step change must:
1. Update TurnInfo in GSM
2. Emit `PhaseOrStepModified` (type 8) annotation with `phase` + `step` detail keys
3. On new turn: also emit `NewTurnStarted` (type 48) annotation

### Priority granting

Server sends `ActionsAvailableReq` with available actions (must include
`ActionType.Pass` for the client's auto-pass to work). Client auto-pass
(`TryAutoRespond`) only fires Pass — never auto-submits non-pass.

### AutoPassPriority (in PerformActionResp)

| Value | Meaning | Server behavior |
|-------|---------|-----------------|
| 0 (None) | Explicit pass | Normal MTG pass |
| 1 (No) | Full control on | Always return priority after state change |
| 2 (Yes) | Full control off | May skip returning priority for trivial changes |

### Default auto-pass

`AutoPassOption.ResolveMyStackEffects (6)` — client auto-passes during
own stack resolution. Server should expect most priority responses to be
auto-passed during own triggers resolving.

### Settings (stops/yields)

Client sends `SetSettingsReq` to update stops/yields. Server should store
and respect them. Stops are per-StopType × SettingScope (Team/Opponents/AnyPlayer).
Transient stops (field 15) apply once then discard.

---

## 6. Current Bugs & Gaps

### P0 — PhaseStopProfile not wired in nexus

**Root cause of client stalls.** `GameBridge.start()` creates
`WebPlayerController` with no `phaseStopProfile`. Every phase grants priority.
Client receives `ActionsAvailableReq` at COMBAT_DAMAGE, COMBAT_END, etc. where
real Arena would auto-pass. If client doesn't respond → 120s timeout → silent
auto-pass → client never gets updated state.

**Fix:** Wire `PhaseStopProfile.createDefaults()` in `GameBridge.start()`.
**Blocked on:** `PhaseOrStepModified` annotation regression — the first
GSM on human's turn loses its phase annotation because `prevSnapshot` is
stale/null at the AI→human turn boundary. See `phase-stop-profile.md`.

### P0 — Blocking declaration broken

`CombatHandler.onDeclareBlockers()` maps instanceIds but the combat
flow may not be fully working. BUGS.md says "declaring blockers targeting
doesn't work." The `defenderPlayerId` was fixed for attackers but blocker
path wasn't changed.

### P1 — Client settings ignored

`onSettings()` saves the `SettingsMessage` but stops/yields are never
applied. If the client toggles a stop (e.g., adds Upkeep stop), the server
ignores it. This means client phase ladder UI is decorative.

### P1 — No turn-boundary stop

`autoPassAndAdvance` can loop past turn boundaries without stopping. If
the AI turn ends and human turn begins with only pass-only actions, the
loop continues auto-passing into the human's turn without sending state.
The client may miss the turn transition entirely.

### P1 — Activate action not handled

`ActionType.Activate` in `onPerformAction()` falls through to the `else`
branch (logged, treated as pass). Players can't activate abilities.

### P2 — No SmartStops integration

Real Arena has SmartStops: auto-sets stops when player has relevant cards
(e.g., instant in hand → stop at opponent's end step). forge-web has
`smartPhaseSkip` (similar idea) but it only skips on own turn. The nexus
path doesn't use either — it relies purely on `shouldAutoPass()`.

### P2 — autoPassUntilEndOfTurn not supported

The web UI supports "End Turn" action (auto-pass rest of turn). The nexus
path has no equivalent — the client can't signal "pass until end of turn."
Arena's `AutoPassOption.Turn (1)` maps to this.

### P2 — No AutoPassPriority handling

The nexus doesn't read `auto_pass_priority` from `PerformActionResp`. All
client responses are treated equally regardless of this field. Missing
optimization: when client signals `Yes (2)`, server could skip returning
priority for trivial changes.

### P2 — next_phase / next_step not set

`TurnInfo` built by StateMapper doesn't set `next_phase`/`next_step`.
Client's phase ladder hint glow doesn't work.

---

## 7. Two Independent Auto-Pass Systems (Root Design Tension)

The web layer and nexus layer have **independent, overlapping** auto-pass logic:

| Concern | forge-web (engine thread) | forge-nexus (session thread) |
|---------|--------------------------|------------------------------|
| "Should I stop here?" | PhaseStopProfile + smartPhaseSkip | shouldAutoPass(actions) + CombatHandler |
| When it fires | Before bridge blocks | After bridge blocks |
| Thread | Engine thread | Session/Netty thread |
| Effect | Returns null → engine keeps running | Submits PassPriority → engine unblocks |

**With PhaseStopProfile wired:** Most non-essential phases never reach the
nexus session thread at all. The engine auto-passes internally.
`AutoPassEngine` handles the remaining cases: combat pacing, AI turn waiting,
and edge cases where the engine blocks but the client shouldn't be prompted.

**Without PhaseStopProfile (current state):** Every phase blocks the engine.
`AutoPassEngine` is doing all the heavy lifting — checking shouldAutoPass,
sending edictal passes, and trying to replicate what PhaseStopProfile would
do more cleanly.

**Target state:** PhaseStopProfile handles coarse "don't stop here" decisions
on the engine thread (fast, no messages). AutoPassEngine handles:
- AI turn playback and pacing
- Combat prompt routing (attacker/blocker requests)
- Targeting prompt routing
- Edge cases: stack resolution visibility, turn transitions
- edictalPass for explicit client notification

---

## 8. MTGA vs Forge Phase Stop Alignment

### MTGA default stops (from handshake)

| Phase | Own turn | Opp turn |
|-------|----------|----------|
| Upkeep | — | — |
| Draw | — | — |
| Main1 | **Stop** | — |
| BeginCombat | **Stop** | **Stop** |
| DeclareAttackers | **Stop** | **Stop** |
| DeclareBlockers | **Stop** | **Stop** |
| CombatDamage | — | — |
| EndCombat | — | — |
| Main2 | **Stop** | — |
| EndStep | — | **Stop** |
| FirstStrikeDmg | **Stop** | **Stop** |

### forge-web HUMAN_DEFAULTS

Main1, DeclareAttackers, DeclareBlockers, Main2

### forge-web AI_DEFAULTS (used during opponent's turn)

CombatBegin, DeclareAttackers, DeclareBlockers, EndOfTurn

### Gap

forge-web's HUMAN_DEFAULTS are missing:
- **BeginCombat** (own turn stop in Arena) — less critical since DeclareAttackers follows immediately
- **FirstStrikeDamage** (own turn stop in Arena) — edge case

AI_DEFAULTS are close but don't map 1:1 to Arena's opponent-turn stops.
Arena has EndStep; forge-web has EndOfTurn. Arena has FirstStrikeDamage;
forge-web doesn't.

For the nexus path, what matters is that the client receives stops in the
handshake AND the server actually respects them. Currently neither layer
is connected — handshake sends stops, server ignores them, engine doesn't
use PhaseStopProfile.

---

## 9. Client Auto-Pass Interaction Model

The real Arena flow for a "boring" phase:

```
Server: GSM (TurnInfo shows new phase) + ActionsAvailableReq (with Pass)
Client: auto-pass fires TryAutoRespond() → PerformActionResp(Pass, autoPassPriority=0)
Server: processes pass → next phase
```

The key insight: **the real server sends ActionsAvailableReq at every stop,
and the client auto-passes most of them.** The server doesn't skip sending
priority — it's the client that responds instantly.

Forge-nexus currently tries to be smarter by not sending priority at all
(via shouldAutoPass → edictalPass). This works but means the client never
"sees" the phase transition at the protocol level. The edictalPass is a
workaround, not the real protocol flow.

**Ideal nexus behavior:** Send state + ActionsAvailableReq at every stop
the client has configured. Let the client auto-pass. Only use edictalPass
for genuine server-forced advances (timeouts, disconnects).

**Pragmatic short-term:** Keep AutoPassEngine for now (changing to
client-driven auto-pass is a large refactor). Wire PhaseStopProfile to
reduce how often AutoPassEngine needs to fire. Fix the annotation
regression blocking it.

---

## 10. File Reference

### Engine (forge-game)

| File | Key area |
|------|----------|
| `phase/PhaseHandler.java:1042` | mainLoopStep() — priority state machine |
| `phase/PhaseHandler.java:238` | onPhaseBegin() — per-phase setup |
| `zone/MagicStack.java:599` | resolveStack() |
| `player/PlayerController.java:332` | autoPassCancel() |

### Web layer (forge-web)

| File | Key area |
|------|----------|
| `game/WebPlayerController.kt:903` | chooseSpellAbilityToPlay() decision chain |
| `game/PhaseStopProfile.kt` | Stop set per player |
| `game/GameActionBridge.kt` | CompletableFuture bridge, autoPassUntilEndOfTurn |
| `game/PlayableActionQuery.kt:126` | hasPlayableNonManaAction() |
| `game/GameMutations.kt:119` | passPriority() stop-aware loop (REST path) |

### Nexus (forge-nexus)

| File | Key area |
|------|----------|
| `server/AutoPassEngine.kt` | Main auto-pass loop |
| `server/CombatHandler.kt` | Combat phase detection + prompts |
| `server/TargetingHandler.kt` | Targeting/choice prompt handling |
| `server/MatchSession.kt` | Orchestration, onPerformAction |
| `game/GameBridge.kt:176` | WebPlayerController creation (no profile!) |
| `game/BundleBuilder.kt:196` | shouldAutoPass() |
| `game/StateMapper.kt:258` | TurnInfo mapping |
| `protocol/HandshakeMessages.kt:259` | Default stop settings for client |

### Reference

| File | Key area |
|------|----------|
| (from Arena client decompilation) | Full MTGA priority/autopass reference |
| `forge-nexus/docs/priority-loop.md` | Engine state machine diagram |
| `forge-nexus/docs/combat-protocol.md` | Combat wire protocol |
| `forge-nexus/docs/plans/phase-stop-profile.md` | Blocked fix + regression analysis |
| `forge-nexus/docs/rosetta.md` | Arena ↔ Forge ↔ nexus translation table |
