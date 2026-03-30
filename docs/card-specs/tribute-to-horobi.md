# Tribute to Horobi / Echo of Death's Wail — Card Spec

## Identity

- **Name:** Tribute to Horobi // Echo of Death's Wail
- **grpId:** 79552 (saga front) / 79553 (creature back)
- **Set:** NEO
- **Type:** Enchantment — Saga (front) / Enchantment Creature — Spirit 3/3 (back)
- **Cost:** {1}{B}
- **Forge script:** `forge/forge-gui/res/cardsfolder/t/tribute_to_horobi_echo_of_deaths_wail.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Saga (lore counters, chapters) | `K:Chapter:3:DBToken,DBToken,DBExile` | `GameEventCardCounters` | **missing** (no saga entry in catalog) |
| Token creation (opponent creates) | `DB$ Token \| TokenOwner$ Opponent` | `GameEventTokenCreated` | wired |
| Exile-return transform | `DB$ ChangeZone` (BF→Exile→BF Transformed) | `GameEventCardChangeZone` | **missing** (no transform support) |
| Flying | `K:Flying` | -- | wired |
| Haste | `K:Haste` | -- | wired |
| Gain control (all Rat tokens) | `DB$ GainControl \| AllValid$ Rat.token` | `GameEventControllerChanged` | wired (annotation only) |
| Attack trigger (sacrifice + draw) | `T:Mode$ Attacks` + `AB$ Draw \| Cost$ Sac<1/...>` | `GameEventCardSacrificed` | wired |

**Unobserved:** Attack trigger sacrifice+draw — Echo attacked in this session but the optional sacrifice was declined (no resolution frame). Needs dedicated game or puzzle.

## What it does

1. **Enters as Saga.** On ETB and after each draw step, adds a lore counter (counter_type 108).
2. **Chapter I:** Each opponent creates a 1/1 black Rat Rogue creature token.
3. **Chapter II:** Same — each opponent creates another 1/1 black Rat Rogue.
4. **Chapter III:** Exile this Saga, then return it to the battlefield transformed under your control.
5. **Back face (Echo of Death's Wail):** 3/3 Spirit with Flying and Haste.
6. **Echo ETB:** Gain control of all Rat tokens (the ones chapters I/II created for opponents).
7. **Echo attack trigger:** Whenever Echo attacks, you may sacrifice another creature. If you do, draw a card.

The card's design arc: donate rats to opponents, then steal them all back when the saga transforms.

## Trace (session 2026-03-27_21-29-18, seat 1)

Tribute to Horobi was cast once by seat 1. Full saga lifecycle observed: cast, chapters I/II token creation, chapter III exile-return transform, Echo ETB gain-control, Echo attacked.

### Saga cast + Chapter I (gsId 49-53)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 49 | 161→288 | Hand→Stack (31→27) | Cast: ObjectIdChanged + ZoneTransfer (CastSpell) |
| 51 | 288 | Stack→Battlefield (27→28) | Resolve: enters BF, lore counter added (type=108, amount=1) |
| 51 | 291 | Stack (27) | Chapter I ability created (abilityGrpId=147926, parentId=288) |
| 53 | 292 | Battlefield (28) | Token created: 1/1 Rat Rogue (grpId=79747), ownerSeatId=2, controllerSeatId=2 |

### Chapter II (gsId 95-98)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 95 | 300 | Stack (27) | Chapter II ability created (abilityGrpId=147927, parentId=288) |
| 98 | 301 | Battlefield (28) | Second Rat token (grpId=79747), ownerSeatId=2, controllerSeatId=2 |

### Chapter III — Transform (gsId 143-147)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 143 | 317 | Stack (27) | Chapter III ability (abilityGrpId=147760, parentId=288) |
| 145 | 288→318 | BF→Exile (28→29) | ObjectIdChanged (288→318) + ZoneTransfer category=`Exile` |
| 145 | 318→319 | Exile→BF (29→28) | ObjectIdChanged (318→319) + ZoneTransfer category=`Return`, grpId changes 79552→79553 |
| 145 | 320 | Stack (27) | Echo ETB trigger ability (grpId=147928, parentId=319) |
| 147 | 292, 301 | Battlefield | ControllerChanged: both Rats now controllerSeatId=1 |
| 147 | 7004 | -- | LayeredEffectCreated for gain-control effect |

### Echo attacks (gsId 161-162)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 161 | 319 | Battlefield | DeclareAttackersReq includes 319 as qualified attacker |
| 162 | 319 | Battlefield | Attack confirmed — no sacrifice trigger resolution observed (may ability declined) |

### Annotations

**Chapter I resolve (gsId 51):**
- `CounterAdded` — affectorId=288, counter_type=108 (Lore), transaction_amount=1
- `AbilityInstanceCreated` — affectorId=288, affectedIds=291, source_zone=28

**Transform (gsId 145):**
- `ObjectIdChanged` — orig_id=288, new_id=318 (exile step)
- `ZoneTransfer` — zone_src=28 (BF), zone_dest=29 (Exile), category=`Exile`
- `ObjectIdChanged` — orig_id=318, new_id=319 (return step)
- `ZoneTransfer` — zone_src=29 (Exile), zone_dest=28 (BF), category=`Return`
- `AbilityInstanceCreated` — affectorId=319, affectedIds=320 (ETB trigger on stack)

**Gain control (gsId 147):**
- `ResolutionStart` — grpid=147928 (Echo ETB ability)
- `LayeredEffectCreated` — affectorId=320, affectedIds=7004
- `ControllerChanged` — affectorId=320, affectedIds=292 (Rat 1)
- `ControllerChanged` — affectorId=320, affectedIds=301 (Rat 2)
- `ResolutionComplete` — grpid=147928

### Key findings

- **Transform is a two-step zone transfer with two ObjectIdChanged.** The saga gets a fresh instanceId on exile (288→318), then another fresh instanceId on return (318→319). The returned object has grpId=79553 (creature back face) — the client uses `othersideGrpId` from the gameObject to know about the flip.
- **Both old IDs (288, 318) persist in Limbo.** After transform, gsId=145 shows 288 and 318 both in Limbo (zone 30) alongside 319 on Battlefield. The client needs all three for animation continuity.
- **Lore counter type is 108** in the proto CounterType enum — same numeric value as `SuppressedPowerAndToughness` annotation type (collision is fine, different namespaces).
- **Token ownership vs control diverges.** Rats are created with ownerSeatId=2 (opponent) but after ControllerChanged, controllerSeatId flips to 1. The `objectSourceGrpId=79552` on tokens traces back to the saga front face.
- **ControllerChanged is a transient annotation** — affectorId is the ETB trigger ability instance (320), not the creature itself. A `LayeredEffectCreated` fires alongside for the persistent steal effect.
- **ZoneTransfer categories for transform:** `Exile` for BF→Exile, `Return` for Exile→BF. Not `CastSpell` or `Resolve`.

## Gaps for leyline

1. **Saga mechanic** — No saga support at all. Needs: lore counter auto-increment on ETB + draw step, chapter ability triggering based on counter count, chapter III final-chapter auto-sacrifice (or in this case, exile-return). This is a new card type, not just a mechanic overlay.
2. **Exile-return transform** — The two-step ObjectIdChanged + ZoneTransfer sequence (BF→Exile with category `Exile`, Exile→BF with category `Return`) is novel. StateMapper needs to handle the double ObjectIdChanged in a single GSM, emit correct categories, and swap grpId to the back face.
3. **Token creation for opponent** — TokenOwner$ Opponent means the token's ownerSeatId is the opponent, not the caster. Verify TokenCreated annotation carries correct ownerSeatId when the creator differs from the owner.
4. **Gain control (AllValid targeting)** — The `GainControl | AllValid$ Rat.token` DSL means "all matching permanents" with no target selection. Wire shape: ControllerChanged per affected permanent + one LayeredEffectCreated. Confirm leyline's ControllerChanged annotation emitter handles multi-permanent steal.
5. **Add saga + transform to catalog.yaml** — Neither mechanic has a catalog entry.

## Supporting evidence needed

- [ ] Other saga cards in games (Okiba Reckoner Raid was in this deck — check if it also transformed)
- [ ] Puzzle for attack trigger: Echo attacks, player sacrifices a Rat, draws a card — exercises the unobserved may-sacrifice ability
- [ ] Variance: confirm `Exile`/`Return` categories are consistent across other exile-return transform cards (e.g. Fable of the Mirror-Breaker)
- [ ] Check if lore counter removal (saga leaves BF) emits CounterRemoved or if the exile handles it implicitly
