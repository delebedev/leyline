# Message Patterns

Real server message patterns observed across proxy recordings. Each pattern shows the exact message sequence the Arena client expects.

## Game start (post-keep → Main1)

5-message pattern:

```
[idx+0] GS Diff, SendHiFi, PhaseOrStepModified x2    ← AI/spectator view
[idx+1] GS Diff, SendHiFi, empty                      ← marker
[idx+2] GS Diff, SendAndRecord, PhaseOrStepModified    ← player view
[idx+3] PromptReq (promptId=37)
[idx+4] ActionsAvailableReq (promptId=2)               ← first real priority
```

## Phase transition

Double-diff pair, identical across all recordings:

```
[idx+0] GS Diff, SendHiFi, PhaseOrStepModified   ← turnInfo+actions+annotations
[idx+1] GS Diff, SendHiFi, empty                  ← turnInfo+actions only
```

Every phase change produces this pair. During AI auto-pass, you see long runs of these pairs.

## NewTurnStarted

Always bundled with 4x PhaseOrStepModified (beginning→upkeep→draw→main1):

```
annotations=[NewTurnStarted, PhaseOrStepModified x4]                                        ← no permanents
annotations=[NewTurnStarted, PhaseOrStepModified x4, TappedUntappedPermanent x N]            ← with permanents
```

TappedUntappedPermanent count = number of tapped permanents to untap.

## Play land

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

## Cast creature (AI perspective)

4-message pattern:

```
[idx+0] GS Diff, SendHiFi, CastSpell (AbilityInstanceCreated, ManaPaid, TappedUntappedPermanent, UserActionTaken, ObjectIdChanged, ZoneTransfer)
[idx+1] GS Diff, SendHiFi, empty marker
[idx+2] GS Diff, SendHiFi, Resolution (ResolutionComplete, ResolutionStart, ZoneTransfer), category=Resolve
[idx+3] GS Diff, SendHiFi, empty marker
```

4 consecutive GS Diffs with NO ActionsAvailableReq between them. Annotation count scales with mana cost.

## Cast creature (player perspective)

```
[idx+0] GS Diff, SendAndRecord, CastSpell (same annotations as AI view)  ← seat 1
[idx+1] GS Diff, SendAndRecord, CastSpell (same annotations)              ← seat 2
[idx+2] ActionsAvailableReq
```

No separate resolution messages — resolution happens during priority pass.

## Cast targeted spell

Full sequence (player casting, `Send` updateType during targeting):

```
[idx+0] GS Diff, Send, [ObjectIdChanged, PlayerSelectingTargets, ZoneTransfer], category=CastSpell
[idx+1] SelectTargetsReq                          ← prompt: pick target
[idx+2] GS Diff, Send, empty                       ← state during targeting
[idx+3] SelectTargetsReq                          ← re-prompt (target confirmed)
[idx+4] GS Diff, SendHiFi, [AbilityInstanceCreated, ManaPaid, PlayerSubmittedTargets, TappedUntappedPermanent, UserActionTaken x2]
[idx+5] GS Diff, SendHiFi, empty marker
[idx+6...] Resolution varies by spell effect
```

`updateType=Send` (not SendHiFi or SendAndRecord) is used during target selection.

## Discard + reveal spell

After targeted spell sequence, resolution involves reveal:

```
GS Diff, Send, [ResolutionStart, RevealedCardCreated x N]
SubmitTargetsResp
SelectNreq                                                  ← "choose N cards to discard"
QueuedGameStateMessage x2
GS Diff, SendHiFi, [PlayerSubmittedTargets, ...]
GS Diff, Send, [ResolutionStart, RevealedCardCreated x N]   ← re-reveal
GS Diff, SendHiFi, [ResolutionComplete, RevealedCardDeleted, ZoneTransfer x2], categories=[Discard, Resolve]
```

## Combat (no blockers)

```
DeclareAttackersReq
GS Diff, SendHiFi, CastSpell (AI cast during declare)    ← optional
GS Diff, SendHiFi, empty marker
GS Diff, SendHiFi, Resolution                             ← optional
GS Diff, SendHiFi, empty marker
PHASE_TRANSITION x1-2
SubmitAttackersResp
GS Diff, SendHiFi, empty x2                              ← damage step
```

## Combat (with blockers)

```
DeclareAttackersReq
GS Diff, SendAndRecord x2                                ← attacker state (dual seat)
DeclareAttackersReq (re-prompt)
GS Diff, SendHiFi, [TappedUntappedPermanent]              ← creatures tapped
GS Diff, SendHiFi, empty
GS Diff, SendAndRecord, PhaseOrStepModified               ← enter declare-blockers
DeclareBlockersReq
SubmitAttackersResp
GS Diff, SendHiFi, [TappedUntappedPermanent]
GS Diff, SendHiFi, empty
GS Diff, SendAndRecord, PhaseOrStepModified               ← enter damage
SubmitBlockersResp
GS Diff, SendHiFi x4                                      ← damage resolution
GS Diff, SendHiFi, [DamageDealt, ModifiedLife, PhaseOrStepModified, SyntheticEvent]
```

## Game end (concede)

```
GS Diff, SendAndRecord, empty (actions only, no annotations)  ← repeated 3x
IntermissionReq
```

## Confirmed stable patterns

| Pattern | Msgs | Stable? | Notes |
|---------|------|---------|-------|
| Game start | 5 | Yes | actionTypes vary by deck |
| Phase transition | 2 | Yes (zero structural diff) | actionTypes vary |
| Play land (player) | 2 (+1 dual) | Yes | late-game adds dual SendAndRecord |
| Play land (AI) | 2 | Yes | — |
| Cast creature (AI) | 4 | Yes | annotation counts scale with mana |
| Cast creature (player) | 2-3 | Yes | dual SendAndRecord + ActionsAvailableReq |
| NewTurnStarted | 2 | Yes | TappedUntappedPermanent count varies |
| Combat | 8-15 | Yes (shape) | variable, depends on blockers |

## updateType semantics

| Value | Meaning | When |
|-------|---------|------|
| `SendAndRecord` | Sent to acting player; client records for replay | Player actions, game start (player view) |
| `SendHiFi` | Sent to opponent/spectator; high-fidelity update | Opponent sees your actions, AI turns |
| `Send` | Sent during interactive prompts | Target selection, resolution reveals |

## Seat perspective

Real server sends a separate message stream per seat. Differences are **envelope-only** — state content (annotations, zone transfers, objects) is identical.

| Aspect | Acting player | Opponent/spectator |
|--------|--------------|-------------------|
| updateType | SendAndRecord | SendHiFi |
| ActionsAvailableReq | Present (after each GS) | Absent |
| Annotations | Same | Same |
| Zone/object diffs | Same | Same |
