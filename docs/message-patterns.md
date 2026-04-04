---
summary: "GRE message sequences from proxy recordings: updateType semantics, seat perspective routing, and per-interaction message flows."
read_when:
  - "understanding what messages the client expects for a game event"
  - "debugging message ordering or missing messages"
  - "implementing a new interaction flow (combat, targeting, mulligan)"
---
# Message Patterns

GRE message sequences observed from proxy recordings — what the Arena client expects for each game event.

## updateType Semantics

| Value | Meaning | When |
|-------|---------|------|
| `SendAndRecord` | Sent to acting player; client records for replay | Player actions, game start (player view) |
| `SendHiFi` | Sent to opponent/spectator; high-fidelity update | Opponent sees your actions, AI turns |
| `Send` | Sent during interactive prompts | Target selection, resolution reveals |

## Seat Perspective

Real server sends a separate message stream per seat. Differences are **envelope-only** — state content (annotations, zone transfers, objects) is identical.

| Aspect | Acting player | Opponent/spectator |
|--------|--------------|-------------------|
| updateType | SendAndRecord | SendHiFi |
| ActionsAvailableReq | Present (after each GS) | Absent |
| Annotations | Same | Same |
| Zone/object diffs | Same | Same |

## Message Routing

| Message type | Sent to |
|---|---|
| `GameStateMessage_695e` | Each seat separately (different object counts for private info) |
| `DeclareAttackersReq` / `DeclareBlockersReq` / `SelectTargetsReq` | Deciding player only |
| `SubmitAttackersResp` / `SubmitBlockersResp` / `SubmitTargetsResp` | Submitter only |
| `PromptReq` | Both seats |
| `QueuedGameStateMessage` | Opponent during interactive prompts |
| `EdictalMessage_695e` | Target seat (auto-pass) |

## Prompt IDs (hardcoded)

| id | Purpose |
|----|---------|
| 2 | Generic priority / ActionsAvailable |
| 6 | Declare Attackers |
| 7 | Declare Blockers |
| 10 | Select Targets |
| 34 | Mulligan decision |
| 37 | Announcement (who kept, who has priority) |

## Game Start (post-keep to Main1)

5-message pattern:

```
[idx+0] GS Diff, SendHiFi, PhaseOrStepModified x2    <- AI/spectator view
[idx+1] GS Diff, SendHiFi, empty                      <- marker
[idx+2] GS Diff, SendAndRecord, PhaseOrStepModified    <- player view
[idx+3] PromptReq (promptId=37)
[idx+4] ActionsAvailableReq (promptId=2)               <- first real priority
```

## Phase Transition

Double-diff pair, identical across all recordings:

```
[idx+0] GS Diff, SendHiFi, PhaseOrStepModified   <- turnInfo+actions+annotations
[idx+1] GS Diff, SendHiFi, empty                  <- turnInfo+actions only
```

Every phase change produces this pair. During AI auto-pass, you see long runs of these pairs.

## NewTurnStarted

Always bundled with 4x PhaseOrStepModified (beginning->upkeep->draw->main1):

```
annotations=[NewTurnStarted, PhaseOrStepModified x4]                                        <- no permanents
annotations=[NewTurnStarted, PhaseOrStepModified x4, TappedUntappedPermanent x N]            <- with permanents
```

TappedUntappedPermanent count = number of tapped permanents to untap.

## Play Land

**Player perspective (SendAndRecord):**
```
[idx+0] GS Diff, SendAndRecord, [ObjectIdChanged, UserActionTaken, ZoneTransfer], category=PlayLand
[idx+1] ActionsAvailableReq (updated actions with new mana abilities)
```

Late-game may produce 2 SendAndRecord messages (one per seat), then ActionsAvailableReq.

**AI perspective (SendHiFi):**
```
[idx+0] GS Diff, SendHiFi, [ObjectIdChanged, UserActionTaken, ZoneTransfer]
[idx+1] GS Diff, SendHiFi, empty marker
```

No ActionsAvailableReq during AI's turn.

## Cast Creature

**AI perspective:** 4-message pattern:

```
[idx+0] GS Diff, SendHiFi, CastSpell (AbilityInstanceCreated, ManaPaid, TappedUntappedPermanent, UserActionTaken, ObjectIdChanged, ZoneTransfer)
[idx+1] GS Diff, SendHiFi, empty marker
[idx+2] GS Diff, SendHiFi, Resolution (ResolutionComplete, ResolutionStart, ZoneTransfer), category=Resolve
[idx+3] GS Diff, SendHiFi, empty marker
```

4 consecutive GS Diffs with NO ActionsAvailableReq between them. Annotation count scales with mana cost.

**Player perspective:**

```
[idx+0] GS Diff, SendAndRecord, CastSpell (same annotations as AI view)  <- seat 1
[idx+1] GS Diff, SendAndRecord, CastSpell (same annotations)              <- seat 2
[idx+2] ActionsAvailableReq
```

No separate resolution messages — resolution happens during priority pass.

## Cast Targeted Spell

Full sequence (player casting, `Send` updateType during targeting):

```
[idx+0] GS Diff, Send, [ObjectIdChanged, PlayerSelectingTargets, ZoneTransfer], category=CastSpell
[idx+1] SelectTargetsReq                          <- prompt: pick target
[idx+2] GS Diff, Send, empty                       <- state during targeting
[idx+3] SelectTargetsReq                          <- re-prompt (target confirmed)
[idx+4] GS Diff, SendHiFi, [AbilityInstanceCreated, ManaPaid, PlayerSubmittedTargets, TappedUntappedPermanent, UserActionTaken x2]
[idx+5] GS Diff, SendHiFi, empty marker
[idx+6...] Resolution varies by spell effect
```

`updateType=Send` (not SendHiFi or SendAndRecord) is used during target selection.

## Discard + Reveal Spell

After targeted spell sequence, resolution involves reveal:

```
GS Diff, Send, [ResolutionStart, RevealedCardCreated x N]
SubmitTargetsResp
SelectNreq                                                  <- "choose N cards to discard"
QueuedGameStateMessage x2
GS Diff, SendHiFi, [PlayerSubmittedTargets, ...]
GS Diff, Send, [ResolutionStart, RevealedCardCreated x N]   <- re-reveal
GS Diff, SendHiFi, [ResolutionComplete, RevealedCardDeleted, ZoneTransfer x2], categories=[Discard, Resolve]
```

## Spell Casting Priority

```
ActionsAvailableReq with N actions (includes cast option)
Player casts -> GameStateMessage: spell on Stack
  [caster retains priority -- gets ActionsAvailableReq]
  [opponent gets ActionsAvailableReq to respond]
  Both pass -> GameStateMessage: Stack empties, permanent enters Battlefield
  [ETB trigger? -> Stack gets trigger, both get priority, resolves]
```

Caster retains priority after casting (per MTG rules). Opponent also gets priority while spell is on stack.

## Combat (no blockers)

```
DeclareAttackersReq
GS Diff, SendHiFi, CastSpell (AI cast during declare)    <- optional
GS Diff, SendHiFi, empty marker
GS Diff, SendHiFi, Resolution                             <- optional
GS Diff, SendHiFi, empty marker
PHASE_TRANSITION x1-2
SubmitAttackersResp
GS Diff, SendHiFi, empty x2                              <- damage step
```

## Combat (with blockers)

```
BeginCombat  -> pair of diffs (enter + priority pass)
DeclareAttack -> DeclareAttackersReq (Prompt id=6) to active player ONLY
  [interactive: each toggle = GameStateMessage + new DeclareAttackersReq]
  SubmitAttackersResp -> finalize
  ActionsAvailableReq -> NAP gets priority (for instants/abilities)
DeclareBlock -> DeclareBlockersReq (Prompt id=7) to defending player ONLY
  [interactive: each toggle = GameStateMessage + new DeclareBlockersReq]
  SubmitBlockersResp -> finalize
  ActionsAvailableReq -> both get priority (combat tricks window)
CombatDamage -> state diff (creatures die, triggers fire)
EndCombat -> pair of diffs
```

If no legal attackers: `DeclareAttackersReq` is **never sent** — server auto-skips from DeclareAttack to EndCombat.

### Attacker Toggling (interactive)

Each creature click during `DeclareAttackersReq` produces:
- `GameStateMessage` with `objects=1` (creature's attack state toggled)
- Fresh `DeclareAttackersReq` with new msgId (same Prompt id=6)
- Opponent sees only the `GameStateMessage` (no request)

### Blocker Assignment

Same interactive pattern. `DeclareBlockersReq` (Prompt id=7). Each toggle = state diff + new request. `SubmitBlockersResp` confirms.

From recording `2026-02-28_14-15-29`:
```
S->C: DeclareBlockersReq (blocker=360, attackerInstanceIds=[355], maxAttackers=1)
C->S: DeclareBlockersResp (blocker 360 -> attacker 355)
S->C: DeclareBlockersReq echo (attackerInstanceIds=[] -- server reflects toggle-off)
C->S: DeclareBlockersResp (toggle again)
  ... 6 echo-backs total
C->S: SubmitBlockersReq (finalize -- empty payload, type=33)
```

The echo-back `DeclareBlockersReq` updates `attackerInstanceIds` to reflect current assignment state (empty when unassigned, populated when assigned).

### Combat Damage

State diff: creatures leave Battlefield -> enter Graveyards simultaneously. Death triggers go on Stack, resolve normally.

Full message sequence:
```
GS Diff, SendAndRecord x2                                <- attacker state (dual seat)
DeclareAttackersReq (re-prompt)
GS Diff, SendHiFi, [TappedUntappedPermanent]              <- creatures tapped
GS Diff, SendHiFi, empty
GS Diff, SendAndRecord, PhaseOrStepModified               <- enter declare-blockers
DeclareBlockersReq
SubmitAttackersResp
GS Diff, SendHiFi, [TappedUntappedPermanent]
GS Diff, SendHiFi, empty
GS Diff, SendAndRecord, PhaseOrStepModified               <- enter damage
SubmitBlockersResp
GS Diff, SendHiFi x4                                      <- damage resolution
GS Diff, SendHiFi, [DamageDealt, ModifiedLife, PhaseOrStepModified, SyntheticEvent]
```

## Activated Abilities + Targeting

```
Player activates -> Stack gets ability object
SelectTargetsReq (Prompt id=10) -> interactive target selection
  [each retarget = new GameStateMessage + new SelectTargetsReq]
SubmitTargetsResp -> finalize
  [opponent sees QueuedGameStateMessage while prompt is active]
Ability resolves -> Stack empties, effect applied
```

While a player is in an interactive prompt (target selection, attacker/blocker selection), the opponent's state updates are delivered as `QueuedGameStateMessage` instead of `GameStateMessage_695e`. These are batched/held until the prompt resolves.

## EdictalMessage

`EdictalMessage_695e` — server-forced auto-pass/advance. Appears when AI decides to move phases.

## Game End (concede)

```
GS Diff, SendAndRecord, empty (actions only, no annotations)  <- repeated 3x
IntermissionReq
```
