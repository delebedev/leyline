# Bite Down — Card Spec

## Identity

- **Name:** Bite Down
- **grpId:** 93925
- **Set:** FDN
- **Type:** Instant
- **Cost:** {1}{G}
- **Forge script:** `forge/forge-gui/res/cardsfolder/b/bite_down.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| Cast instant | `A:SP$ Pump` | wired |
| Two-target selection (your creature + their creature/PW) | `ValidTgts$ Creature.YouCtrl` / `ValidTgts$ Creature.YouDontCtrl,Planeswalker.YouDontCtrl` | wired (SelectTargetsReq two groups) |
| One-directional damage (your creature as source) | `DamageSource$ ParentTarget`, `NumDmg$ X` where `X:ParentTargeted$CardPower` | wired (DamageDealt affectorId=your creature) |
| SBA_Damage cleanup | (rules-level) | wired |

## What it does

1. Cast for {1}{G}: present two separate SelectTargetsReq target groups simultaneously.
   - Group 1 (targetIdx=1): a creature you control (the damage dealer).
   - Group 2 (targetIdx=2): a creature or planeswalker you don't control (the target).
2. On resolution: your creature deals damage equal to its power to the opponent's creature/planeswalker. One-directional — the target does NOT deal back.
3. If the damage is lethal, the target dies to SBA_Damage in the same resolution diff.
4. Bite Down goes to graveyard (category=`Resolve`).

This is mechanically distinct from Fight: Fight is mutual. Bite Down is a directed pump-damage where the dealer is your creature but only the opponent's permanent takes damage (and dies).

## Trace (session 2026-03-29_17-04-26, seat 1, turn 14)

Bite Down iid 378 (hand) → cast as iid 423 (stack) → resolves, kills opponent's 3/3 Beast (iid 372→427).
The controller's creature (iid 346, Dog 3/1) deals 3 damage. Beast toughness = 3, so lethal.

### Cast (gsId 344)

| Field | Value |
|-------|-------|
| instanceId (hand) | 378 |
| instanceId (stack) | 423 |
| grpId | 93925 |
| ObjectIdChanged | orig_id=378, new_id=423 |
| ZoneTransfer zone_src | 31 (Hand) |
| ZoneTransfer zone_dest | 27 (Stack) |
| ZoneTransfer category | `CastSpell` |
| PlayerSelectingTargets | affectorId=1 (seat), affectedIds=423 |
| uniqueAbilities on stack object | `{id:326, grpId:121962}` (the spell ability) |

SelectTargetsReq follows in same bin (msgId 450, gsId 344):
- `abilityGrpId: 93925` (spell-level, not ability instance)
- `sourceId: 423` (stack object)
- Two target groups:

| Group | targetIdx | promptId | Legal targets | Constraint |
|-------|-----------|----------|---------------|------------|
| 1 | 1 | 152 | 335 (Wall 0/4), 346 (Dog 3/1), 416, 422 | Creature.YouCtrl |
| 2 | 2 | 2401 | 359, 372 (Beast 3/3), 409 | Creature.YouDontCtrl + PW.YouDontCtrl |

Both groups share `targetingAbilityGrpId: 121962` and `targetingPlayer: 1`.
`allowCancel: Abort`, `allowUndo: true`.

Note: both groups in one SelectTargetsReq message (same as Bushwhack fight — two `targets {}` blocks, not two separate messages).

### Targeting confirmed (gsId 347)

- `PlayerSubmittedTargets` annotation: affectorId=1, affectedIds=423
- Two TargetSpec pAnns on stack object 423:
  - index=1, abilityGrpId=121962, promptParameters=423, affectedIds=**346** (Dog — dealer)
  - index=2, abilityGrpId=121962, promptParameters=423, affectedIds=**372** (Beast — target)
- Mana payment: 2 lands tapped (Gate iid 379 abilityGrpId=1203, Forest/Plains iid 396 abilityGrpId=1203)

### Resolution (gsId 349)

| Annotation | affectorId | affectedIds | Details |
|-----------|------------|-------------|---------|
| ResolutionStart | 423 | 423 | grpid=93925 |
| DamageDealt | **346** (Dog 3/1) | **372** (Beast 3/3) | damage=3, type=2, markDamage=1 |
| ResolutionComplete | 423 | 423 | grpid=93925 |
| ObjectIdChanged | 1 (seat) | 423 | orig_id=423, new_id=426 |
| ZoneTransfer (spell) | 1 (seat) | 426 | zone_src=27→zone_dest=33, category=`Resolve` |
| ObjectIdChanged | — | 372 | orig_id=372, new_id=427 |
| ZoneTransfer (beast) | — | 427 | zone_src=28→zone_dest=37, category=`SBA_Damage` |

Key findings on resolution diff:
- **DamageDealt affectorId = the controlling creature (iid 346)**, not the spell (423). The spell is not the damage source; your creature is.
- The opponent's creature transitions to their GY (zone 37) via `SBA_Damage` in the **same diff** as resolution — no separate SBA step.
- The `damage: 3` field is present on the dying creature's gameObject in this diff (the 3/3 took exactly 3 damage).
- No `DamagedThisTurn` pAnn observed on the dying creature — it goes straight to GY before any per-turn badge is needed.
- Bite Down itself resolves to GY (zone 33, not exile): category=`Resolve`.

## Gaps for leyline

1. **DamageDealt affectorId semantics** — must use the creature's iid (target of group 1), not the spell's iid. Spell orchestrates but creature is the damage source. Confirmed: affectorId=346, not 423.
2. **Two-group SelectTargetsReq shape** — identical to Bushwhack fight (see `bushwhack-fight-wire.md`): both groups in a single SelectTargetsReq, not sequenced. Both share `abilityGrpId: 121962` and `abilityGrpId: 93925` at the outer level.
3. **SBA_Damage co-resolution** — when damage kills the target, SBA fires in the same diff as ResolutionComplete. No separate gsId for the SBA. Already observed in other fight/damage specs.
4. **`abilityGrpId: 121962`** — this is the sub-ability grpId used on the targeting and TargetSpec annotations. The spell-level grpId (93925) appears on the outer selectTargetsReq and ResolutionStart/Complete. Both must be tracked.

## Supporting evidence

- Session `2026-03-29_17-04-26`: two cast attempts (gsId 272 was undone via Full Undo at gsId 273; gsId 343–349 is the live cast + resolution).
- Comparison: `docs/card-specs/` — no direct fight spec; closest prior art is `bushwhack-fight-wire.md` (two-group SelectTargetsReq confirmed identical).
- DamageDealt type=2 (creature-to-creature damage) consistent with combat specs.

## Agent Feedback

**`tape proto show` verdict: essential.** The JSONL frames strip critical fields (`affectorId` on DamageDealt, `abilityGrpId` on TargetSpec, `targetingAbilityGrpId`, inner promptIds on target groups). Without `show`, the affectorId=346 finding for DamageDealt would have been missed — easily the most implementation-critical detail in this spec.

**Pain points:**

1. **abilityGrpId at two levels is confusing.** The outer `selectTargetsReq.abilityGrpId` is 93925 (the spell); the per-group `targetingAbilityGrpId` is 121962 (sub-ability). The JSONL only surfaces the outer one. Took two proto lookups to see both levels.

2. **Undo/Full Undo noise.** gsId 272 was a cast that was rolled back at gsId 273 (Full updateType). Without knowing to look for `updateType: Undo` in the proto, a naive scan would double-count the casts. `tape proto show` makes this visible; JSONL does not mark undos distinctly.

3. **No "find gsIds for grpId" shortcut.** The workflow is: `grep grpId:N md-frames.jsonl` → identify interesting gsIds → `tape proto show` each. A `just tape find-casts <grpId>` command that emits just the cast/resolve gsIds would halve this.

4. **Two-seat duplication in `show` output.** For gsId 349 the proto shows both seat 1 and seat 2 views of the same diff (identical annotations). It's clear once you know, but a `--seat` filter flag would reduce noise.
