# Mardu Outrider — Card Spec

## Identity

- **Name:** Mardu Outrider
- **grpId:** 75493
- **Set:** TDM
- **Type:** Creature — Orc Warrior
- **Cost:** {1}{B}{B}
- **Base P/T:** 5/5
- **Forge script:** `forge/forge-gui/res/cardsfolder/m/mardu_outrider.txt`

## What it does

1. **Cast with mandatory additional cost** — to cast, the controller must discard a card as part of casting. There is no optional modal; the cost is required.
2. **Enters the battlefield** — 5/5 creature, no ETB trigger.

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast (CastSpell) | `A:SP$ PermanentCreature \| Cost$ 1 B B Discard<1/Card>` | ObjectIdChanged + ZoneTransfer category=CastSpell, inline mana sequence | wired |
| Mandatory additional cost: discard | `Cost$ … Discard<1/Card>` | PayCostsReq promptId=1024 → EffectCostResp → ZoneTransfer category=Discard (separate gsId) | **missing — mandatory-additional-cost not wired** |
| Resolve to BF | (spell ability resolution) | ResolutionStart/Complete grpId=75493, ZoneTransfer stack→BF category=Resolve | wired |

### Ability grpIds

No activated or triggered ability grpIds observed. The discard cost is part of the spell ability itself, not a separate ability instance.

## Trace (session 2026-03-22_13-21-18, seat 1)

Seat 1 cast Mardu Outrider twice. Both casts paid the mandatory discard cost. First cast (iid 298) discarded Eternal Thirst (iid 302, grpId 75490). Second cast (iid 313) discarded Compound Fracture (iid 317, grpId 75487).

### First cast (iid 298, T5 Main1)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 91 | 160→298 | Hand→Stack (31→27) | ObjectIdChanged 160→298; ZoneTransfer Hand→Stack category=CastSpell; 3× Swamp tapped inline (AbilityInstanceCreated abilityGrpId=1003 → TappedUntappedPermanent → ManaPaid color=3 → AbilityInstanceDeleted) |
| 91 | — | — | PayCostsReq promptId=1024 systemSeatIds=[1] |
| 91 | — | — | EffectCostResp (C→S, no payload) |
| 92 | 162→302 | Hand→GY (31→33) | ObjectIdChanged affectorId=298; ZoneTransfer affectorId=298 affectedIds=[302] category=Discard; UserActionTaken affectorId=1 affectedIds=[298] actionType=1 |
| 94 | 298 | Stack→BF (27→28) | ResolutionStart/Complete grpid=75493; ZoneTransfer affectorId=1 affectedIds=[298] category=Resolve |

### Second cast (iid 313, T7 Main1)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 131 | 159→313 | Hand→Stack (31→27) | ObjectIdChanged 159→313; ZoneTransfer Hand→Stack category=CastSpell; 3× Swamp tapped inline (same shape as first cast) |
| 131 | — | — | PayCostsReq promptId=1024 systemSeatIds=[1] |
| 131 | — | — | EffectCostResp (C→S, no payload) |
| 132 | 296→317 | Hand→GY (31→33) | ObjectIdChanged affectorId=313; ZoneTransfer affectorId=313 affectedIds=[317] category=Discard; UserActionTaken affectorId=1 affectedIds=[313] actionType=1 |
| 134 | 313 | Stack→BF (27→28) | ResolutionStart/Complete grpid=75493; ZoneTransfer affectorId=1 affectedIds=[313] category=Resolve |

Key observation: the PayCostsReq and EffectCostResp share gsId with the cast GSM (gsId=91 / gsId=131). The discard ZoneTransfer fires in the *next* gsId (92 / 132). No SelectNReq is present — the client selects the discard target internally and the server observes the result via EffectCostResp.

## Annotations

### Cast + mana (gsId=91, first cast)

- `ObjectIdChanged` — affectorId=0, affectedIds=[160], details={orig_id: 160, new_id: 298}
- `ZoneTransfer` — affectorId=0, affectedIds=[298], zone_src=31, zone_dest=27, category="CastSpell"
- Per land (×3): `AbilityInstanceCreated` affectorId=landIid affected=[manaAbilityIid]; `TappedUntappedPermanent` affectorId=manaAbilityIid affected=[landIid]; `UserActionTaken` affectorId=1 actionType=4 abilityGrpId=1003; `ManaPaid` affectorId=landIid affected=[298] color=3; `AbilityInstanceDeleted`

### PayCostsReq (gsId=91, between cast GSM and discard GSM)

```json
{
  "greType": "PayCostsReq",
  "gsId": 91,
  "promptId": 1024,
  "systemSeatIds": [1]
}
```

EffectCostResp (C→S) has no payload beyond gsId.

### Discard (gsId=92, first cast)

- `ObjectIdChanged` — affectorId=298 (the spell on stack), affectedIds=[162], details={orig_id: 162, new_id: 302}
- `ZoneTransfer` — affectorId=298, affectedIds=[302], zone_src=31 (Hand), zone_dest=33 (GY), category="Discard"
- `UserActionTaken` — affectorId=1 (seat), affectedIds=[298], details={actionType: 1, abilityGrpId: 0}

The Discard ZoneTransfer affectorId is the spell's instanceId (298), not a separate ability instance. This is the defining shape for mandatory-additional-cost discard: the spell itself is the affector of the discard.

### Resolution (gsId=94, first cast)

- `ResolutionStart` — affectorId=298, affectedIds=[298], details={grpid: 75493}
- `ResolutionComplete` — affectorId=298, affectedIds=[298], details={grpid: 75493}
- `ZoneTransfer` — affectorId=1 (seat), affectedIds=[298], zone_src=27, zone_dest=28, category="Resolve"

No ETB annotations. No persistent annotations observed on ETB (no keyword abilities on this card).

## Gaps for leyline

1. **Mandatory additional cost (discard) not wired.** Leyline must detect `Discard<N/Card>` in the spell cost, send `PayCostsReq promptId=1024` immediately after the mana-payment GSM (same gsId as cast), then emit the Discard ZoneTransfer in the next gsId once the client responds with `EffectCostResp`. The affectorId on the Discard ZoneTransfer must be the spell's instanceId, not a separate ability instance.

2. **No SelectNReq for discard target.** The client chooses the discard target from its hand after receiving PayCostsReq — the server does not send a SelectNReq prompt. Leyline must not emit SelectNReq for this flow. (Contrast: hand-size discard at cleanup does use SelectNReq — see `discard-selectnreq-bug.md`.)

3. **gsId sequencing.** PayCostsReq shares the cast gsId. Discard fires in cast_gsId+1. Resolution fires in cast_gsId+3 (with a priority window at cast_gsId+2). Leyline must advance the gsId correctly across these steps.

## Supporting evidence

- `mandatory-additional-cost-wire.md` — wire shape memo (PayCostsReq promptId=1024, EffectCostResp, Discard ZoneTransfer)
- `docs/rosetta.md` — zone IDs: Hand=31, Stack=27, BF=28, GY=33
- `docs/catalog.yaml` `optional-costs` entry — kicker/buyback use CastingTimeOptionsReq; mandatory costs use PayCostsReq (distinct path)
- `discard-selectnreq-bug.md` — SelectNReq used for hand-size cleanup discard; confirms mandatory-additional-cost flow does NOT use SelectNReq
