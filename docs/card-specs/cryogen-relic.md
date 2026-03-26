# Cryogen Relic — Card Spec

## Identity

- **Name:** Cryogen Relic
- **grpId:** 96626
- **Set:** FIN
- **Type:** Artifact
- **Cost:** {1}{U}
- **Forge script:** `forge/forge-gui/res/cardsfolder/c/cryogen_relic.txt`

## What it does

1. **Cast** ({1}{U}): artifact resolves to battlefield.
2. **ETB/LTB trigger** (abilityGrpId 190898): "When CARDNAME enters or leaves the battlefield, draw a card." Both enter and leave share one grpId; trigger fires on either zone transition. On resolution: ZoneTransfer(Draw) moves one card from library to hand.
3. **Activated ability** (abilityGrpId 190899): "{1}{U}, Sacrifice CARDNAME: Put a stun counter on up to one target tapped creature." SelectTargetsReq expected (TargetMin=0 TargetMax=1); on resolution: CounterAdded (STUN) on chosen creature, or nothing if no target chosen.

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast artifact | `A:SP$ …` | ZoneTransfer CastSpell → Resolve | wired |
| ETB trigger → Draw | `T:Mode$ ChangesZone \| Destination$ Battlefield \| Execute$ TrigDraw` | AbilityInstanceCreated + ZoneTransfer(Draw) | wired (see draw-step) |
| LTB trigger → Draw | `T:Mode$ ChangesZone \| Origin$ Battlefield \| Execute$ TrigDraw \| Secondary$ True` | AbilityInstanceCreated + ZoneTransfer(Draw) | **unobserved** |
| Sacrifice + stun counter | `A:AB$ PutCounter \| Cost$ 1 U Sac<1/CARDNAME> \| ValidTgts$ Creature.tapped \| CounterType$ STUN` | SelectTargetsReq + PayCostsReq + CounterAdded | **unobserved** |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 190898 | ETB and LTB trigger: "When CARDNAME enters or leaves the battlefield, draw a card." (shared grpId, two trigger conditions) |
| 190899 | Activated: "{1}{U}, Sacrifice CARDNAME: Put a stun counter on up to one target tapped creature." |

## Trace (session 2026-03-21_22-05-00, seat 2)

One Cryogen Relic cast by seat 2 (iid 290). Sacrifice ability offered every turn but never activated; LTB trigger therefore unobserved. Game ended with the relic still on battlefield.

### Cast and ETB (gsId 45 → 49)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 45 | 223→290 | Hand→Stack (35→27) | Cast: ObjectIdChanged 223→290, ZoneTransfer Hand→Stack category=CastSpell; Island + Mountain tapped for {U}+{1} |
| 47 | 290, 293 | Stack→BF (27→28) | Resolve: ResolutionStart/Complete grpId=96626; ZoneTransfer category=Resolve; AbilityInstanceCreated affectorId=290 → ability iid 293 (grpId 190898) on stack; TriggeringObject pAnn on 293 |
| 49 | 293, 294 | — | ETB trigger resolves: ResolutionStart grpId=190898; ObjectIdChanged library card → 294; ZoneTransfer category=Draw zone 36→35; ResolutionComplete; AbilityInstanceDeleted affectorId=290 affectedIds=[293] |

### Sacrifice ability availability (gsId 49–98)

`Activate instanceId=290 abilityGrpId=190899` present in `actions` continuously from gsId 49 through gsId 98. Never activated. iid 290 removed via `diffDeletedInstanceIds` at gsId 112 (end-of-game cleanup, no ZoneTransfer).

## Annotations (findings)

### ETB trigger wire (gsId 47)

```
AbilityInstanceCreated  affectorId=290 (permanent), affectedIds=[293], source_zone=28
TriggeringObject pAnn   affectorId=293 (ability iid), affectedIds=[290], source_zone=28
```

Same shape as ETB triggers observed on other cards (Novice Inspector, equipment triggers). The ETB and LTB triggers share a single abilityGrpId (190898) per `just ability` output; the server presumably fires the same grpId for both.

### Draw resolution (gsId 49)

```
ResolutionStart    affectorId=293, grpid=190898
ObjectIdChanged    affectorId=293, affectedIds=[230→294]
ZoneTransfer       affectorId=293, affectedIds=[294], zone_src=36, zone_dest=35, category=Draw
ResolutionComplete affectorId=293, grpid=190898
AbilityInstanceDeleted affectorId=290, affectedIds=[293]
```

`AbilityInstanceDeleted` affectorId is the **permanent** (290), not the ability iid (293). Consistent with Novice Inspector draw resolution pattern.

### LTB trigger — unobserved

The LTB trigger (same grpId 190898, Forge: `Secondary$ True`) never fired. Expected wire shape by analogy with ETB: AbilityInstanceCreated fires in the same diff as the ZoneTransfer that removes the permanent from the battlefield (sacrifice or destruction), with TriggeringObject pAnn pointing back to the departing permanent. Whether the permanent's iid appears in the zone lists at that point (pre-removal) or is already absent is unknown.

## Gaps for leyline

1. **LTB trigger not tested.** leyline's triggered-ability dispatch must fire grpId 190898 when `ChangesZone Origin$ Battlefield` fires for the relic. Untested because sacrifice was never used in this session.
2. **Stun counter wire unobserved.** Sacrifice ability (190899) requires SelectTargetsReq (TargetMin=0 TargetMax=1, ValidTgts=Creature.tapped), PayCostsReq for sacrifice cost, then CounterAdded with CounterType=STUN on target. All three phases unobserved. CounterType value for STUN unknown (reference: type 1=+1/+1, 108=quest, 127=landmark, 200=incubation per `docs/conformance/counter-type-reference.md`).
3. **Optional target (TargetMin=0).** Sacrifice ability allows 0 targets. Whether SelectTargetsReq is still sent with `minCount=0` or skipped entirely when no target chosen is unobserved.

## Supporting evidence

- `docs/card-specs/novice-inspector.md` — draw resolution and AbilityInstanceDeleted affectorId pattern
- `/Users/denis/src/leyline/.claude/agent-memory/conformance/ward-counter-wire.md` — PayCostsReq for sacrifice costs
- `/Users/denis/src/leyline/.claude/agent-memory/conformance/counter-type-reference.md` — known counter_type values
- `docs/catalog.yaml` — `draw-step` entry (wired); upkeep-triggers (wired)
