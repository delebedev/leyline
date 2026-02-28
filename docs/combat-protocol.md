# Combat & Stack Protocol

Observed during a 7-turn Sparky game. Human (seat1) vs Sparky (seat2).

## Combat Flow

```
BeginCombat  -> pair of diffs (enter + priority pass)
DeclareAttack -> DeclareAttackersReq_695e (Prompt id=6) to active player ONLY
  [interactive: each toggle = GameStateMessage + new DeclareAttackersReq]
  SubmitAttackersResp_695e -> finalize
  ActionsAvailableReq -> NAP gets priority (for instants/abilities)
DeclareBlock -> DeclareBlockersReq_695e (Prompt id=7) to defending player ONLY
  [interactive: each toggle = GameStateMessage + new DeclareBlockersReq]
  SubmitBlockersResp_695e -> finalize
  ActionsAvailableReq -> both get priority (combat tricks window)
CombatDamage -> state diff (creatures die, triggers fire)
EndCombat -> pair of diffs
```

If no legal attackers: `DeclareAttackersReq` is **never sent** — server auto-skips from DeclareAttack to EndCombat.

### Attacker toggling (interactive)

Each creature click during `DeclareAttackersReq` produces:
- `GameStateMessage` with `objects=1` (creature's attack state toggled)
- Fresh `DeclareAttackersReq` with new msgId (same Prompt id=6)
- Opponent sees only the `GameStateMessage` (no request)

In the capture, player toggled 5 times before confirming.

### Blocker assignment

Same interactive pattern. `DeclareBlockersReq` (Prompt id=7). Each toggle = state diff + new request. `SubmitBlockersResp` confirms.

Confirmed in recording `2026-02-28_14-15-29` (real server):
```
S→C: DeclareBlockersReq (blocker=360, attackerInstanceIds=[355], maxAttackers=1)
C→S: DeclareBlockersResp (blocker 360 → attacker 355)
S→C: DeclareBlockersReq echo (attackerInstanceIds=[] — server reflects toggle-off)
C→S: DeclareBlockersResp (toggle again)
  ... 6 echo-backs total
C→S: SubmitBlockersReq (finalize — empty payload, type=33)
```

Key detail: the echo-back `DeclareBlockersReq` updates `attackerInstanceIds` to reflect current assignment state (empty when unassigned, populated when assigned).

### Combat damage

State diff: creatures leave Battlefield → enter Graveyards simultaneously. Death triggers go on Stack, resolve normally.

## Spell Casting Flow

```
ActionsAvailableReq with N actions (includes cast option)
Player casts -> GameStateMessage: spell on Stack
  [caster retains priority — gets ActionsAvailableReq]
  [opponent gets ActionsAvailableReq to respond]
  Both pass -> GameStateMessage: Stack empties, permanent enters Battlefield
  [ETB trigger? -> Stack gets trigger, both get priority, resolves]
```

Key: caster retains priority after casting (per MTG rules). Opponent also gets priority while spell is on stack.

## Activated Abilities & Targeting

```
Player activates -> Stack gets ability object
SelectTargetsReq_695e (Prompt id=10) -> interactive target selection
  [each retarget = new GameStateMessage + new SelectTargetsReq]
SubmitTargetsResp_695e -> finalize
  [opponent sees QueuedGameStateMessage while prompt is active]
Ability resolves -> Stack empties, effect applied
```

### QueuedGameStateMessage

While a player is in an interactive prompt (target selection, attacker/blocker selection), the opponent's state updates are delivered as `QueuedGameStateMessage` instead of `GameStateMessage_695e`. These are batched/held until the prompt resolves.

## Universal Patterns

### Double-diff per step

Every phase/step transition sends exactly **two** `GameStateMessage` diffs:
1. Enter the step (phase/step fields updated)
2. Priority-pass marker (empty diff, gsId increments)

This is universal — even when nothing happens.

### EdictalMessage

`EdictalMessage_695e` — server-forced auto-pass/advance. Appears when AI decides to move phases.

### Message routing

| Message type | Sent to |
|---|---|
| `GameStateMessage_695e` | Each seat separately (different object counts for private info) |
| `DeclareAttackersReq` / `DeclareBlockersReq` / `SelectTargetsReq` | Deciding player only |
| `SubmitAttackersResp` / `SubmitBlockersResp` / `SubmitTargetsResp` | Submitter only |
| `PromptReq` | Both seats |
| `QueuedGameStateMessage` | Opponent during interactive prompts |
| `EdictalMessage_695e` | Target seat (auto-pass) |

### Prompt IDs (hardcoded)

| id | Purpose |
|----|---------|
| 2 | Generic priority / ActionsAvailable |
| 6 | Declare Attackers |
| 7 | Declare Blockers |
| 10 | Select Targets |
| 34 | Mulligan decision |
| 37 | Announcement (who kept, who has priority) |

## Implications for Forge Arena Bridge

### What needs changing

1. **Combat**: replace `ActionType.Cast` hack with real `DeclareAttackersReq`/`DeclareBlockersReq` messages
2. **Targeting**: add `SelectTargetsReq` flow for spells/abilities with targets
3. **Double-diff**: auto-pass should emit paired diffs per step transition
4. **Stack priority**: after casting, give opponent `ActionsAvailableReq` before resolving
5. **QueuedGameStateMessage**: send queued state to opponent during interactive prompts
6. **EdictalMessage**: emit for server-forced advances (AI auto-pass)

### What's correct already

- Phase/step mapping (BeginCombat, DeclareAttack, etc.)
- Spell on stack → resolve → permanent enters battlefield
- ETB triggers on stack
- Zone IDs (27=Stack, 28=Battlefield, etc.)
- Card data from ArenaCardDb (grpId, overlayGrpId, name, types, abilities)
