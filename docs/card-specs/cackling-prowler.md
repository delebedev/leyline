# Cackling Prowler — Card Spec

## Identity

- **Name:** Cackling Prowler
- **grpId:** 93814
- **Set:** FDN
- **Type:** Creature — Hyena Rogue
- **Cost:** {3}{G}
- **P/T:** 4/3
- **Forge script:** `forge/forge-gui/res/cardsfolder/c/cackling_prowler.txt`

## Mechanics

| Mechanic | Forge DSL | Forge events | Catalog status |
|----------|-----------|--------------|----------------|
| Ward {2} | `K:Ward:2` | triggered ability fires when targeted by opponent; counter unless {2} paid | partial |
| Morbid trigger | `T:Mode$ Phase \| Phase$ End of Turn \| CheckSVar$ Morbid \| Execute$ TrigPutCounter` | `AbilityInstanceCreated` at end step; `CounterAdded` + `PowerToughnessModCreated` + `LayeredEffectCreated` at resolution | partial (AbilityWordActive Morbid pAnn not emitted) |
| Put +1/+1 counter | `DB$ PutCounter \| CounterType$ P1P1 \| CounterNum$ 1` | `CounterAdded counter_type=1 transaction_amount=1` | wired |

Morbid condition: `SVar:Morbid:Count$Morbid.1.0` — evaluates to 1 if any creature died
this turn, 0 otherwise. Forge checks `CheckSVar$ Morbid` before queuing the end-step trigger.

## What it does

1. **Ward {2}:** whenever an opponent targets Cackling Prowler with a spell or ability,
   that spell/ability is countered unless the opponent pays {2}.
2. **Morbid:** at the beginning of your end step, if a creature died this turn, put a
   +1/+1 counter on Cackling Prowler.

The trigger checks the Morbid condition at trigger creation time (end step). If met, an
ability object (grpId=175866) appears on the stack and resolves immediately. No player
choice is required.

## Trace (session 2026-03-29_16-18-08, seat 1)

Cackling Prowler (iid=323, cast from iid=319) observed across turns 8–18. Morbid trigger
fired twice (turns 8 and 12). Ward not triggered (no opponent removal targeting Prowler).
Counter progression: 4/3 → 5/4 (T8) → 6/5 (T12).

### Cast (gs=190, turn 8)

| Field | Value |
|-------|-------|
| Original iid | 319 (hand) |
| New iid (stack) | 323 |
| Zone transfer | Hand (31) → Stack (27), category=`CastSpell` |
| Cost paid | {3}{G} (4 lands tapped) |

`uniqueAbilityCount: 2` on the stack object — ward and morbid trigger both registered.
No `uniqueAbilities` list in the wire object (count only, no grpId enumeration).

### Resolve / ETB (gs=192, turn 8)

```
annotations:
  ResolutionStart   affectorId=323  grpid=93814
  ResolutionComplete affectorId=323  grpid=93814
  ZoneTransfer      affectedIds=[323]  zone_src=27 zone_dest=28  category="Resolve"

objects:
  instanceId=323  grpId=93814  zoneId=28  power=4  toughness=3
  uniqueAbilityCount=2  hasSummoningSickness=true
```

No ETB trigger — Cackling Prowler has no enters-the-battlefield ability.

### Morbid trigger queued (gs=194, turn 8 end step)

A creature (iid=316 → renamed 322) died in combat at gs=185 (SBA_Damage, zone_dest=37),
triggering the Morbid condition. At the beginning of P1's end step:

```
turnInfo: phase=Ending  step=End  turn=8

annotations:
  PhaseOrStepModified  phase=5  step=0
  AbilityInstanceCreated  affectorId=323  affectedIds=[328]  details={source_zone: 28}

objects:
  instanceId=328  grpId=175866  zoneId=27  type=Ability  owner=1

persistentAnnotations:
  id=18  types=[AbilityWordActive]  affectorId=1  affectedIds=[323, 328]
  details={AbilityWordName: "Morbid"}
```

Key: the `AbilityWordActive` pAnn updates `affectedIds` to include the ability instance
(328) while it is on the stack, then drops back to `[323]` alone after resolution.
`affectorId=1` is the player seat — not the permanent or ability.

Morbid ability grpId: **175866** (distinct from card grpId 93814).

### Morbid trigger resolves (gs=196, turn 8 end step)

```
annotations:
  ResolutionStart    affectorId=328  affectedIds=[328]  grpid=175866
  CounterAdded       affectorId=328  affectedIds=[323]  counter_type=1  transaction_amount=1
  PowerToughnessModCreated  affectorId=323  affectedIds=[323]  power=1  toughness=1
  LayeredEffectCreated  affectedIds=[7004]
  ResolutionComplete affectorId=328  affectedIds=[328]  grpid=175866
  AbilityInstanceDeleted  affectorId=323  affectedIds=[328]

persistentAnnotations (new):
  id=428  types=[ModifiedToughness, ModifiedPower, Counter]
  affectorId=4003  affectedIds=[323]  details={count: 1, counter_type: 1}
```

Prowler object updates: `power=5  toughness=4` in the same diff.

Second morbid fire (gs=313, turn 12) follows identical shape:
- Ability iid=362, grpId=175866
- `CounterAdded affectorId=362 affectedIds=[323] counter_type=1 transaction_amount=1`
- Prowler: 5/4 → 6/5

### AbilityWordActive Morbid — lifecycle and shape

The pAnn (id=18 in session 1) appears atomically in the same diff as a creature's death,
NOT at the start of end step. It persists until `NewTurnStarted` clears it.

| Moment | affectedIds | Notes |
|--------|-------------|-------|
| Creature dies in combat (gs=185) | [319] | Prowler still in hand (iid 319) |
| Prowler cast → stack (gs=190) | [323] | iid updated after ObjectIdChanged |
| Second morbid card (Needletooth Pack) cast (gs=194 era) | [323, 328] | ability instance added |
| Prowler dead (gs=504) | [424, 407] | dead Prowler (424) + Needletooth Pack (407) |

**No `value`, `threshold`, or `AbilityGrpId` fields in details.** Boolean-only: presence
of the pAnn indicates condition met. Contrast with Threshold/Case pAnns which carry a
numeric `value` and `threshold`.

Shape from wire:
```json
{
  "id": 18,
  "types": ["AbilityWordActive"],
  "affectorId": 1,
  "affectedIds": [<morbid_card_iid>, ...],
  "details": { "AbilityWordName": "Morbid" }
}
```

### Morbid fires at CombatDamage step (session 2026-03-29_16-32-23)

In session `16-32-23`, Cackling Prowler (iid=350, entered gs=582) has morbid pAnn id=20.

At gs=362 (turn 16, `phase=Combat step=CombatDamage`): 4 creatures die simultaneously
via SBA_Damage in one diff. `AbilityWordActive Morbid` fires in the same diff — same
atomic update as session 1. Prowler is not in combat in this diff; the pAnn tracks any
creature death on P1's turn, not just deaths involving Prowler.

At gs=672 (turn 26), Prowler iid=435 has the same pAnn when two creatures die.

**Morbid pAnn fires in the combat damage diff, not at a phase boundary.** The
condition is evaluated reactively (death event), not at end-step entry.

### Negative case (session 2026-03-29_16-32-23)

Cackling Prowler (iid=435) entered at gs=582 and attacked several subsequent turns. No
`CounterAdded` was observed on it during this session even though morbid pAnn fired (gs=672).
The game ended on P1's attack before the end step was reached — the trigger fires at end
step of the turn, not during combat. Confirmed that morbid pAnn ≠ counter; the actual
`CounterAdded` only fires at end-step resolution.

## Key findings

1. **Morbid `AbilityWordActive` is boolean** — no `value`, `threshold`, or `AbilityGrpId`
   in details. Only `AbilityWordName: "Morbid"`. Presence of the pAnn = condition met.

2. **Fires at creature death, not at end-step entry** — the pAnn appears in the same diff
   as the `ZoneTransfer(SBA_Damage)` for the dying creature. Not polled at phase start.

3. **`affectorId=1` (player seat)** — not the permanent, not the ability. Same as Raid
   pAnn observed in session 2 (`affectorId=1, affectedIds=[GoblinBoarders, RaidCard]`).

4. **`affectedIds` tracks all morbid cards in play** — multiple morbid cards appear in the
   same pAnn. When the ability is on the stack, the ability iid is also added to affectedIds.

5. **Morbid ability grpId=175866** — the triggered ability has its own grpId distinct from
   the card grpId (93814). `ResolutionStart.grpid=175866` in both observed fires.

6. **Counter resolution shape** — `CounterAdded` + `PowerToughnessModCreated` + `LayeredEffectCreated`
   all in one diff (gs=196). `ModifiedToughness+ModifiedPower+Counter` pAnn created with
   `count=1, counter_type=1`.

7. **Ward abilityGrpId=141939** — from notes.md analysis. Ward not exercised in either
   session (no opponent removal targeted Prowler).

8. **`uniqueAbilityCount=2`** — constant throughout; no uniqueAbilities list decoded
   from wire (count only).

## Gaps for leyline

1. **`AbilityWordActive` Morbid not emitted** — catalog marks `threshold` (and by
   extension Morbid) as `partial` for turn-scoped ability words. Forge uses event-driven
   detection for `CheckSVar$ Morbid`. Leyline needs to emit this pAnn when a creature
   dies during P1's turn while a morbid card is in play. Existing issue: #177.

2. **Ward wire unobserved** — ward is `partial` in catalog. No test of the counter-unless-
   pays-{2} path was captured for this specific card.

## Supporting evidence

- Session `2026-03-29_16-18-08`: gs=185 (creature dies → morbid pAnn appears), gs=194
  (end step: AbilityInstanceCreated iid=328 grpId=175866), gs=196 (CounterAdded T8),
  gs=311/313 (second fire T12, iid=362)
- Session `2026-03-29_16-32-23`: gs=362 (morbid fires at CombatDamage step, iid=350),
  gs=474 (second fire same session), gs=672 (iid=435, Prowler in session 2)
- Raid pAnn in session 2 confirms `affectorId=1` is the shared pattern for turn-condition
  ability words (`AbilityWordName: "Raid"`, same shape)

## Agent Feedback

### Tooling gaps

- **`just tape proto decode -s <session> -g <gsId>` does not exist.** The expected
  decode-by-gsId shortcut is missing. Had to write inline Python to filter md-frames.jsonl
  by gsId manually. This was the single biggest time cost (~40% of trace time). A
  `just wire <session> <gsId>` or `just tape proto decode -s <session> -g <gsId>` command
  would eliminate this entirely.

- **`find-card` output truncated to zone transitions only.** It shows zones but no
  annotation summary. A `--annotations` flag that shows per-gsId annotation types for
  that card's iid would speed up "what happened to this card" queries dramatically.

- **notes.md error:** session `16-18-08` notes.md stated "morbid trigger not exercised"
  but two fires (gs=196, gs=313) are clearly in the JSONL. The notes.md is hand-written
  and unreliable for "what fired" claims — automation should derive this from the
  recording, not human summary.

### Information hard to find

- **abilityGrpId for ward** — not visible in the wire object (no `uniqueAbilities` list,
  just `uniqueAbilityCount`). Had to rely on notes.md secondary reference (141939). No
  tool surfaces this directly. The only reliable path is: find a frame where ward fires,
  read the `AbilityInstanceCreated.affectedIds[0]` grpId. Needs a live test.

- **When morbid pAnn first appears** — the lifecycle tracker script found the pAnn at
  gs=185, which precedes the cast (gs=190). This was surprising: the pAnn tracks cards
  in hand, not just battlefield. No existing memory note covered this.

- **Multi-card affectedIds** — took a second pass to realise the pAnn lists all morbid
  permanents (and in-hand cards), not just one. The ability instance being added to
  affectedIds while on stack was also non-obvious until decoded.

### What would have saved time

1. `just tape proto decode -s <session> -g <gsId>` — one command per frame instead of
   a Python script.
2. Automated notes verification: flag when notes.md "not exercised" claim contradicts
   JSONL annotation data.
3. A `just tape proto ability-word <session> <AbilityWordName>` command that prints all
   pAnn lifecycle events for a named ability word.

### Suggestions for card-spec skill

- Add a step: "check notes.md for accuracy; compare claimed 'not exercised' mechanics
  against JSONL before accepting at face value."
- The template should include a **"Morbid / turn-scoped ability words" vs
  "value-tracking ability words"** distinction note — the boolean-only wire shape is
  a key difference from Threshold/Case.
- Recording selection guidance: for morbid specifically, the best sessions are ones where
  the controlling player's creatures die on *their own turn* (i.e. P1's blockers die in
  P2's attack, or P1's creatures die from SBA). This game's sessions had deaths during
  P1's attack phase — the morbid fired correctly but counter resolution happened on end step.
