# Cauldron Familiar — Card Spec

## Identity

- **Name:** Cauldron Familiar
- **grpId:** 70228 (ELD print used in session)
- **Set:** ELD (Throne of Eldraine)
- **Type:** Creature — Cat
- **Cost:** {B}
- **P/T:** 1/1
- **Forge script:** `forge/forge-gui/res/cardsfolder/c/cauldron_familiar.txt`

## What it does

1. **Cast** ({B}): creature resolves to battlefield.
2. **ETB trigger** (abilityGrpId 119628): "When CARDNAME enters, each opponent loses 1 life and you gain 1 life." Ability instance queued on stack at resolution. On stack resolution: SyntheticEvent type=1, ModifiedLife -1 (opponent), ModifiedLife +1 (self).
3. **Death to GY**: SBA_ZeroToughness (or Destroy/other) moves card to owner's graveyard. ObjectIdChanged fires; iid mutates. GY activated ability immediately appears in the same diff's GSM `actions` list.
4. **GY activated ability** (abilityGrpId 136253): "Sacrifice a Food: Return this card from your graveyard to the battlefield." `ActivationZone$ Graveyard` in Forge DSL. Offered in ActionsAvailableReq `inactiveActions` when no Food is available; would appear in `actions` when a Food is on battlefield.
5. **GY→BF return** (unobserved): when Food cost is paid, expected: Food Sacrifice ZoneTransfer + Cauldron Familiar ZoneTransfer category TBD + new ETB trigger queued.

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast creature | `A:SP$ …` | ZoneTransfer CastSpell | wired |
| ETB trigger — drain 1 life | `T:Mode$ ChangesZone … Execute$ TrigDrain` | AbilityInstanceCreated + ModifiedLife ×2 | wired |
| SBA_ZeroToughness death | SBA rule | ZoneTransfer category=SBA_ZeroToughness | wired (see sba-categories.md) |
| GY ability offered (cost unavailable) | `ActivationZone$ Graveyard` | `inactiveActions` in ActionsAvailableReq | **unknown** — leyline `inactiveActions` wiring unverified |
| GY ability activation (cost available) | `A:AB$ ChangeZone \| Cost$ Sac<1/Food> \| ActivationZone$ Graveyard` | Activate + Sacrifice Food + ZoneTransfer GY→BF | **unobserved** |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 119628 | ETB trigger: "When CARDNAME enters, each opponent loses 1 life and you gain 1 life." |
| 136253 | GY activated: "Sacrifice a Food: Return this card from your graveyard to the battlefield." |

## Trace (session 2026-03-22_23-02-04, seat 1)

Two Cauldron Familiars were cast (iid 280 and 355). Both died without a Food on battlefield. The GY ability was never activated; it appeared in `inactiveActions` across ~200 ActionsAvailableReq messages.

### Cast and ETB (gsId 8 → 12, first Familiar iid 280)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 8 | 164→280 | Hand→Stack (31→27) | Cast: ObjectIdChanged 164→280, ZoneTransfer Hand→Stack category=CastSpell; Swamp tapped for {B} (abilityGrpId 1003, ManaPaid color=3); UserActionTaken actionType=1 |
| 10 | 280, 282 | Stack→BF (27→28) | Resolve: ResolutionStart/Complete grpid=70228; ZoneTransfer category=Resolve zone 27→28; hasSummoningSickness=true; AbilityInstanceCreated affectorId=280 affectedIds=[282] (grpId 119628 on stack); TriggeringObject pAnn affectorId=282 affectedIds=[280] source_zone=28 |
| 12 | 282 | Stack | ETB resolves: ResolutionStart/Complete grpid=119628; SyntheticEvent type=1 affectorId=282 affectedIds=[2]; ModifiedLife affectorId=282 affectedIds=[2] life=-1; ModifiedLife affectorId=282 affectedIds=[1] life=+1; AbilityInstanceDeleted affectorId=280 affectedIds=[282] |

### Death (gsId 74) — SBA_ZeroToughness from Compound Fracture

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 74 | 280→294 | BF→GY (28→33) | Compound Fracture resolves (grpId 75487): -1/-1 reduces CF to 0/0; LayeredEffectCreated ×2, PowerToughnessModCreated ×2; ResolutionComplete; ObjectIdChanged affectorId=None affectedIds=[280] orig_id=280 new_id=294; ZoneTransfer affectedIds=[294] zone_src=28 zone_dest=33 category=SBA_ZeroToughness; GY ability `Activate instanceId=294 abilityGrpId=136253` appears immediately in GSM `actions` list same diff |

### GY ability in inactiveActions

Starting gsId=81 (and every subsequent ActionsAvailableReq for seat 1), the GY ability appears as `inactiveActions[N].instanceId=294 facetId=294`. No abilityGrpId field is visible in the jsonl decoder for `inactiveActions` — only confirmed via raw proto trace. The ability is never moved to `actions` in this session; no Food was available to pay the cost.

## Annotations

### ETB trigger queued (gsId 10)

```
ResolutionStart       affectorId=280  affectedIds=[280]  grpid=70228
ResolutionComplete    affectorId=280  affectedIds=[280]  grpid=70228
ZoneTransfer          affectorId=1    affectedIds=[280]  zone_src=27  zone_dest=28  category=Resolve
AbilityInstanceCreated affectorId=280 affectedIds=[282]  source_zone=28
pAnn: TriggeringObject affectorId=282 affectedIds=[280]  source_zone=28
```

AbilityInstanceCreated fires in the same diff as Resolve (same gsId=10). The triggering object pAnn points back from the ability instance (282) to the permanent (280). Pattern matches `a-most-helpful-weaver-wire.md`.

### ETB trigger resolves (gsId 12)

```
ResolutionStart       affectorId=282  affectedIds=[282]  grpid=119628
SyntheticEvent        affectorId=282  affectedIds=[2]    type=1
ModifiedLife          affectorId=282  affectedIds=[2]    life=-1
ModifiedLife          affectorId=282  affectedIds=[1]    life=+1
ResolutionComplete    affectorId=282  affectedIds=[282]  grpid=119628
AbilityInstanceDeleted affectorId=280 affectedIds=[282]
```

SyntheticEvent type=1 precedes ModifiedLife — same pattern as LoseLife/GainLife effects elsewhere. Both ModifiedLife annotations use the ability instance (282) as affectorId, not the permanent. AbilityInstanceDeleted uses the **permanent** iid (280) as affectorId, not the ability (282).

### Death + GY ability appearance (gsId 74)

```
[Compound Fracture resolution …]
ObjectIdChanged       affectedIds=[280]  orig_id=280  new_id=294   (no affectorId)
ZoneTransfer          affectedIds=[294]  zone_src=28  zone_dest=33  category=SBA_ZeroToughness
```

The affectorId field is absent on ObjectIdChanged (consistent with SBA death — no explicit spell/ability affector). ZoneTransfer similarly has no affectorId. This matches `sba-categories.md` observations.

Immediately in the same gsId=74 diff, the GSM `actions` array contains:
```
{ type: Activate, instanceId: 294, seatId: 1, abilityGrpId: 136253 }
```

The server populates `actions` in the GameStateMessage (not ActionsAvailableReq) before passing priority. This is the first moment the GY ability is visible. In the next ActionsAvailableReq, it moves to `inactiveActions` because no Food is on the battlefield.

## Gaps for leyline

1. **GY ability offering — `inactiveActions` field.** When a card has `ActivationZone$ Graveyard` and its cost is unpayable, the server puts it in `inactiveActions` in ActionsAvailableReq (not the `actions` list). Verify leyline populates `inactiveActions` for abilities with unmet costs. This is the first confirmed instance of `inactiveActions` usage in card specs.

2. **GY activated ability in GSM `actions`.** The `Activate` entry for iid 294 appears in the GSM-level `actions` array in the same diff as death (gsId=74). This is the `actions` field on GameStateMessage (not ActionsAvailableReq). Confirm leyline emits this for GY-activatable cards when they enter the graveyard.

3. **GY→BF return ZoneTransfer category — unobserved.** The Food sacrifice + Familiar return was never triggered in this session. Expected: Sacrifice ZoneTransfer for Food (category=Sacrifice, BF→GY) and a separate ZoneTransfer for the Familiar (category=Resolve or unknown GY-return category, GY→BF). Category string for GY→BF return from activated ability is unknown.

4. **No ObjectIdChanged on GY→BF return.** Based on other GY recursion patterns (Raise Dead: grpId 82133 in this session), a return from GY to BF likely triggers ObjectIdChanged. Unverified for self-recursion.

## Supporting evidence

- `sba-categories.md` — SBA_ZeroToughness category, affectorId absent on ObjectIdChanged/ZoneTransfer
- `a-most-helpful-weaver-wire.md` — TriggeringObject pAnn shape on ETB triggers
- `docs/priority.md` §Priority Flow — documents `actions[] + inactiveActions[]` in ActionsAvailableReq
- `controllerchanged-wire.md` — same session (2026-03-22_23-02-04) for cross-reference

**Note:** the GY activated ability was never exercised. All GY→BF mechanics are inferred or flagged as unobserved. A follow-up session with a Food token on battlefield is required to confirm activation flow and return category.
