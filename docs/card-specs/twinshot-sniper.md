# Twinshot Sniper — Card Spec

## Identity

- **Name:** Twinshot Sniper
- **grpId:** 79600
- **Set:** NEO
- **Type:** Artifact Creature — Goblin Archer
- **Cost:** {3}{R}
- **Forge script:** `forge/forge-gui/res/cardsfolder/t/twinshot_sniper.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Reach | `K:Reach` | none (static keyword) | wired |
| ETB triggered ability (2 damage, targeted) | `T:Mode$ ChangesZone \| Destination$ Battlefield` → `DB$ DealDamage` | `GameEventCardDamaged` / `GameEventPlayerDamaged` | wired (damage-numbers, spell-targeting) |
| Channel (activated from hand, discard as cost) | `A:AB$ DealDamage \| Cost$ 1 R Discard<1/CARDNAME> \| ActivationZone$ Hand` | `GameEventCardChangeZone` (discard) + damage events | **not cataloged** |

Channel is an activated ability usable only from hand. The discard is part of the cost (not an effect), so it happens before the ability goes on the stack. The ability itself is a separate object on the stack (grpId 147987), distinct from the ETB trigger (grpId 1553).

## What it does

1. **Cast normally** ({3}{R}): enters the battlefield. ETB trigger goes on the stack — deals 2 damage to any target (player or creature). Requires target selection (SelectTargetsReq).
2. **Channel from hand** ({1}{R}, discard): activated ability from hand. Discard is the cost. Puts a "deal 2 damage to any target" ability on the stack. Same effect as ETB but cheaper, doesn't put a body on the battlefield.
3. **Reach** (static): can block creatures with flying.

## Trace (session 2026-03-27_20-51-57, seat 1)

Twinshot Sniper was cast once from hand (iid 162→386, ETB dealt 2 to a creature) and channeled once from hand (iid 407→414, dealt 2 to opponent). Five instanceIds observed: 162 (original in opening hand), 386 (on stack/battlefield after cast), 407 (second copy drawn later), 408 (third copy drawn alongside 407), 414 (post-channel graveyard identity of 407).

### Normal cast — ETB trigger (gs219–225)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 2 | 162 | Hand (31) | In opening hand |
| 219 | 162→386 | Hand→Stack (31→27) | ObjectIdChanged + ZoneTransfer category=`CastSpell`. Mana payment bracket (4 lands tapped). |
| 221 | 386 | Battlefield (28) | ZoneTransfer Stack→Battlefield, category=`Resolve`. ETB trigger (iid 391, grpId 1553) put on stack. SelectTargetsReq sent — 7 legal targets (2 players + 5 creatures). |
| 225 | 386 | Battlefield (28) | ETB resolves: DamageDealt (2 damage, type=2) to iid 292. ResolutionComplete grpid=1553. AbilityInstanceDeleted for 391. Target creature (iid 292) dies to SBA_Damage (zone 28→37). |

### Channel from hand (gs300–302)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 289 | 407 | Hand (31) | Drawn — ObjectIdChanged new_id=407 + ZoneTransfer Library→Hand |
| 300 | 407 | Hand (31) | Channel activated: AbilityInstanceCreated (iid 411, grpId 147987, source_zone=31). SelectTargetsReq sent for damage target. Card stays in hand (discard is cost, paid on resolution). |
| 302 | 407→414 | Hand→Graveyard (31→33) | Resolution: ObjectIdChanged 407→414. ZoneTransfer zone_src=31, zone_dest=33, category=`Discard`. DamageDealt affectorId=407, affectedIds=2 (opponent player), damage=2, type=2, markDamage=1. ModifiedLife life=-2. ResolutionComplete grpid=147987. AbilityInstanceDeleted for 411. |

### Annotations

**Channel resolution (gs302):**
- `ObjectIdChanged` — affectorId=411 (the ability), orig_id=407, new_id=414. The ability instance is the affector, not the card itself.
- `ZoneTransfer` — Hand(31)→Graveyard(33), category=`Discard`. The discard-as-cost uses standard Discard category — no special "Channel" category.
- `DamageDealt` — affectorId=407 (original card ID, not 414), affectedIds=2 (opponent seat), damage=2, type=2, markDamage=1. Note: damage annotation uses the **pre-ObjectIdChanged** ID.
- `ModifiedLife` — affectedIds=2, life=-2.
- `ResolutionComplete` — grpid=147987 (channel ability, not the card's grpId 79600).

**ETB resolution (gs225):**
- `DamageDealt` — affectorId=386 (the creature on battlefield), affectedIds=292 (target creature), damage=2, type=2, markDamage=1.
- Target creature (292) subsequently received `ZoneTransfer` Battlefield→zone 37, category=`SBA_Damage` — lethal damage SBA.

### Key findings

- **Channel uses `Discard` category, not a custom one.** Wire-identical to normal discard. The only distinguishing signal is that the discard coincides with an AbilityInstanceCreated from the same card (source_zone=31, Hand).
- **Channel ability has its own grpId (147987)** — distinct from the ETB trigger (grpId 1553). Both are "deal 2 damage to any target" but tracked as separate ability objects.
- **DamageDealt affectorId uses pre-ObjectIdChanged ID.** In gs302, damage is attributed to iid 407 even though the card has already become 414. This matches combat damage patterns but worth noting for annotation ordering.
- **Third copy (iid 408) deleted at gs302.** Instance 408 was a separate Twinshot Sniper in hand. It appears as `diffDeletedInstanceIds` in the same frame — likely visibility cleanup unrelated to channel.

## Gaps for leyline

1. **Channel not cataloged.** Add `channel` entry to `docs/catalog.yaml` under spells section. Status depends on whether leyline can activate abilities from hand.
2. **Hand-zone activated abilities.** Channel requires `ActivationZone$ Hand` — the action mapper must offer activated abilities on cards in hand (not just on battlefield). Need to verify if leyline's action generation handles non-battlefield activation zones.
3. **Discard-as-cost wiring.** The discard happens as part of cost payment (before resolution). Need to confirm leyline's cost payment flow handles discard costs that move the source card itself to the graveyard.
4. **Ability grpId 147987 mapping.** The channel ability needs to be resolvable by the engine. Forge's `AB$ DealDamage` with `ActivationZone$ Hand` must map to the correct wire ability object.

## Supporting evidence needed

- [ ] Other channel cards in NEO traces (e.g. Boseiju, Who Endures; Otawara, Soaring City) — confirm same Discard category pattern
- [ ] Verify Forge engine fires `GameEventCardChangeZone` for discard-as-cost (not just discard-as-effect)
- [ ] Puzzle: channel targeting player vs creature — confirm SelectTargetsReq shape is identical to ETB targeting
