# Immersturm Predator — Card Spec

## Identity

- **Name:** Immersturm Predator
- **grpId:** 94088
- **Set:** VOW (Innistrad: Crimson Vow)
- **Type:** Creature — Vampire Dragon
- **Cost:** {2}{B}{R}
- **P/T:** 3/3
- **Keywords:** Flying
- **Forge script:** `forge/forge-gui/res/cardsfolder/i/immersturm_predator.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Flying (intrinsic keyword) | `K:Flying` | — | wired |
| Whenever ~ becomes tapped, exile up to one target card from a GY; put a +1/+1 counter on ~ | `T:Mode$ Taps … Execute$ TrigExile … SubAbility$ DBPutCounter` | AbilityInstanceCreated + SelectTargetsReq + ZoneTransfer(Exile) + CounterAdded | wired |
| Sacrifice another creature: ~ gains indestructible until EOT, tap it | `A:AB$ Pump | Cost$ Sac<1/Creature.Other/…>` | AbilityInstanceCreated + PayCostsReq/EffectCostResp + ZoneTransfer(Sacrifice) + LayeredEffectCreated + AddAbility(grpId=104) | **unknown — sacrifice-as-cost interaction with PayCostsReq unverified in leyline** |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 140120 | Triggered: whenever ~ becomes tapped → exile GY card + +1/+1 counter |
| 140121 | Activated: sacrifice another creature → indestructible until EOT, tap ~ |
| 104 | Indestructible (granted by 140121 resolution) |

## What it does

1. **Enters battlefield** (3/3 Flying). Three `uniqueAbilities` on the object: Flying, the tap-trigger (140120), and the sacrifice-as-cost activation (140121).
2. **Triggered ability (grpId=140120)**: fires whenever the Predator becomes tapped — by attacking, by the sacrifice ability's "Tap it" rider, or any other tap effect. The ability goes on the stack immediately; player selects up to one target card in any graveyard (SelectTargetsReq, up to 1, optional). On resolution: ObjectIdChanged+ZoneTransfer(GY→Exile) for the chosen card, CounterAdded(counter_type=1, amount=1) on the Predator, and PowerToughnessModCreated.
3. **Activated ability (grpId=140121)**: player chooses "Sacrifice another creature: ~ gains indestructible until end of turn. Tap it." Cost payment uses PayCostsReq (promptId=1074 observed; unstable). Player confirms via EffectCostResp. The sacrifice ZoneTransfer fires immediately as cost payment (BF→GY, category=Sacrifice, affectorId=ability instance). The ability then resolves: LayeredEffectCreated (effect_id=7013) + AddAbility persistent annotation (grpId=104, sourceAbilityGRPID=140121) on the Predator. The "Tap it" rider is part of resolution — if the Predator is untapped, the tap fires 140120 again.
4. **Interaction**: a common play pattern is Predator attacks (tap → triggers 140120), player responds by activating 140121 (sacrifice → indestructible; tapping is a no-op since already tapped from attacking). The trigger (140120) then resolves with the counter and exile. In combat the Predator is now indestructible until EOT.

## Trace (session 2026-03-25_21-47-59, seat 2)

Predator (iid=225, grpId=94088 in Limbo) → cast at gs=175 (iid=249) → entered BF at gs=177. Three activations of 140120 observed (gs=240, 313, 368). One activation of 140121 observed (gs=369–372).

### Triggered ability resolution — 1st attack (gs=240)

Player attacked (SubmitAttackersReq gs=235), Predator tapped → 140120 triggered.

| gsId | instanceId | What happened |
|------|-----------|---------------|
| 236 | 303 | AbilityInstanceCreated affector=249 (140120 goes on stack); TappedUntappedPermanent on 249; PlayerSelectingTargets affector=2; SelectTargetsReq (up to 1, from any GY, abilityGrpId=140120, sourceId=303) |
| 238 | 303 | PlayerSubmittedTargets affector=2 |
| 240 | 303 | ResolutionStart grpId=140120; ObjectIdChanged 262→304; ZoneTransfer src=33(GY) dst=29(Exile) category=Exile affector=303; CounterAdded counter_type=1 amount=1 affector=303 affected=[249]; PowerToughnessModCreated affector=249; LayeredEffectCreated affected=[7008]; ResolutionComplete; AbilityInstanceDeleted |

Predator becomes 4/4 at gs=240.

### Activated ability (sacrifice for indestructible) — 3rd attack (gs=369–372)

After the 3rd trigger resolved (gs=368), player activated the sacrifice ability while Predator was still tapped as an attacker.

| gsId | instanceId | What happened |
|------|-----------|---------------|
| 369 | 345 | AbilityInstanceCreated affector=249 (140121 on stack); PayCostsReq promptId=1074 |
| 369 | — | EffectCostResp (client selects sacrifice target) |
| 370 | 345 | LayeredEffectDestroyed for effect 7011 (sacrificed creature's effect); ObjectIdChanged 338→346; ZoneTransfer src=28(BF) dst=37(GY) category=Sacrifice affector=345; UserActionTaken affector=2 affected=[345] actionType=2 abilityGrpId=140121 |
| 372 | 345 | ResolutionStart grpId=140121; LayeredEffectCreated affected=[7013]; ResolutionComplete; AbilityInstanceDeleted |

## Annotations

### Triggered ability resolution (gs=240, representative)

- `ResolutionStart` — affectorId=303, affectedIds=[303], details={grpid: 140120}
- `ObjectIdChanged` — affectorId=303, affectedIds=[262], details={orig_id: 262, new_id: 304}
- `ZoneTransfer` — affectorId=303, affectedIds=[304], details={zone_src: 33, zone_dest: 29, category: "Exile"}
- `CounterAdded` — affectorId=303, affectedIds=[249], details={counter_type: 1, transaction_amount: 1}
- `PowerToughnessModCreated` — affectorId=249, affectedIds=[249], details={power: 1, toughness: 1}
- `LayeredEffectCreated` — affectorId: absent, affectedIds=[7008]
- `ResolutionComplete` — affectorId=303, affectedIds=[303], details={grpid: 140120}
- `AbilityInstanceDeleted` — affectorId=249, affectedIds=[303]

Note: `LayeredEffectCreated` for the counter's P/T modification has **no affectorId**. This matches the pattern from counter_type_reference — the effect tracks the counter itself (as the permanent annotation source), not the resolving ability.

### Sacrifice cost payment (gs=370)

- `LayeredEffectDestroyed` — affectorId=338 (the sacrificed creature), affectedIds=[7011] — fires before ZoneTransfer
- `ObjectIdChanged` — affectorId=345, affectedIds=[338], details={orig_id: 338, new_id: 346}
- `ZoneTransfer` — affectorId=345, affectedIds=[346], details={zone_src: 28, zone_dest: 37, category: "Sacrifice"}
- `UserActionTaken` — affectorId=2, affectedIds=[345], details={actionType: 2, abilityGrpId: 140121}

No TappedUntappedPermanent for the Predator at gs=370 — already tapped as an attacker.

### Activated ability resolution (gs=372)

- `ResolutionStart` — affectorId=345, affectedIds=[345], details={grpid: 140121}
- `LayeredEffectCreated` — affectorId=345, affectedIds=[7013]
- `ResolutionComplete` — affectorId=345, affectedIds=[345], details={grpid: 140121}
- `AbilityInstanceDeleted` — affectorId=249, affectedIds=[345]

Persistent annotation added in same diff:

- `AddAbility+LayeredEffect` — affectorId=249, affectedIds=[249], details={grpid: 104, sourceAbilityGRPID: 140121, effect_id: 7013, UniqueAbilityId: 239}

### SelectTargetsReq shape for triggered ability (gs=236)

```
promptId: 10
sourceId: 303 (ability instance)
abilityGrpId: 140120
targetIdx: 1
maxTargets: 1
targets: [instanceId, legalAction="Select_a1ad", highlight=Hot/Cold]
```

"Hot" targets are the preferred GY exile candidates (opponent's creatures/relevant cards). "Cold" targets are legal but lower-priority. The ability is optional (minTargets not enforced — player may submit zero).

## Unobserved mechanics

- **Tap trigger from sacrifice rider**: the "Tap it" part of 140121 should tap the Predator and re-trigger 140120 if the Predator is untapped when the sacrifice ability is activated. Not observed — in the recorded game the Predator was always already tapped (as an attacker) when 140121 was used.
- **Empty GY target**: the ability allows "up to one" target — player may choose no GY card and still get the counter. The SelectTargetsReq confirms it (maxTargets=1 with no minTargets), but a zero-target resolution was not captured.
- **Sacrifice artifact**: the Forge script says `Sac<1/Creature.Other/another creature>` — only creatures. The recorded card text says "Sacrifice another creature or artifact" per user prompt, but the Forge script and ability DB text (grpId=140121) say "another creature". The artifact case is unconfirmed.
- **Tap trigger from non-attack tap effect**: only attack-taps observed. Activation via Prodigal Sorcerer or similar untap-and-tap effect not captured.

## Gaps for leyline

1. **PayCostsReq for sacrifice-as-cost (abilityGrpId=140121).** The real server sends PayCostsReq (promptId=1074 observed, but promptId is not stable) when the player activates 140121. The player responds with EffectCostResp and selects the creature to sacrifice. Verify leyline emits PayCostsReq for sacrifice-cost activated abilities (not just for ward/additional-cast-cost cases).
2. **ZoneTransfer category=Sacrifice at cost-payment time.** The sacrifice fires at gs=370 (cost payment), before resolution at gs=372. The ZoneTransfer affectorId=345 (the ability instance on the stack). The sacrificed permanent gets ObjectIdChanged before ZoneTransfer, consistent with other sacrifice patterns.
3. **LayeredEffectDestroyed before sacrifice ZoneTransfer.** When the sacrificed creature has an active LayeredEffect (e.g., an aura or enchantment granting it an ability), LayeredEffectDestroyed fires before the ZoneTransfer in the same diff. Verify leyline's sacrifice sequencing matches this order.
4. **AddAbility+LayeredEffect persistent annotation shape for temporary indestructible.** The pAnn uses affectorId=249 (the Predator, the permanent gaining the ability), NOT affectorId=345 (the ability instance). The details include `sourceAbilityGRPID=140121`. This differs from standard static AddAbility (where affectorId is the enchantment/equipment). Flag if leyline uses a different affectorId convention for "self-grants indestructible until EOT" effects.
5. **LayeredEffectCreated affectorId absent on counter P/T modification.** At gs=240 the LayeredEffectCreated for the +1/+1 counter's P/T effect has no affectorId. Verify leyline omits affectorId on counter-derived P/T LayeredEffects (consistent with counter_type_reference findings).

## Supporting evidence

- `docs/.claude/agent-memory/conformance/counter-type-reference.md` — counter_type=1 is +1/+1; LayeredEffectCreated affectorId absent for counter P/T effects
- `docs/.claude/agent-memory/conformance/exile-aura-wire.md` — Exile category ZoneTransfer shape (ObjectIdChanged + ZoneTransfer pair, affectorId=resolving ability instance)
- `docs/.claude/agent-memory/conformance/mandatory-additional-cost-wire.md` — PayCostsReq promptId patterns for effect costs
- `docs/catalog.yaml` — indestructible: wired; exile: wired; counters: wired
