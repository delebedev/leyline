# Surgehacker Mech — Card Spec

## Identity

- **Name:** Surgehacker Mech
- **grpId:** 79700 (79818 secondary/art variant)
- **Set:** NEO
- **Type:** Artifact — Vehicle
- **Cost:** {4}
- **P/T:** 5/5
- **Forge script:** `forge/forge-gui/res/cardsfolder/s/surgehacker_mech.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast artifact | `ManaCost:4` | `GameEventCardChangeZone` | wired |
| Menace | `K:Menace` | — (blocking rules) | wired |
| ETB damage trigger | `T:Mode$ ChangesZone … DB$ DealDamage` | `GameEventCardDamaged` | **needs vehicle-count check** |
| Vehicle (not a creature until crewed) | `Types:Artifact Vehicle` | — | **missing** |
| Crew 4 | `K:Crew:4` | `GameEventCardTapped` | **missing** |

**Unobserved:** No instance of Surgehacker being the blocker target (menace not tested defensively). No instance of ETB with 3+ vehicles on board.

## What it does

1. **Cast** — generic artifact, costs {4}. Not a creature on its own.
2. **ETB trigger** — when it enters the battlefield, deals damage equal to **twice the number of Vehicles you control** to target creature or planeswalker an opponent controls. With 1 vehicle (itself), deals 2. With 2 vehicles, deals 4, etc.
3. **Crew 4** — tap any number of creatures you control with total power 4 or greater. Surgehacker becomes an artifact creature (5/5 with menace) until end of turn.
4. **Menace** — can only be blocked by two or more creatures.

## Trace (session 2026-03-27_21-15-58, seat 1)

UBR deck, game 3. Surgehacker cast once, crewed at least twice, attacked 3 times. Brute Suit (79681, Crew 1) also on battlefield — two vehicles present simultaneously.

### Cast and ETB (gsId 138–144)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 2 | 165 | Hand (31) | In opening hand |
| 138 | 165→304 | Hand→Stack (31→27) | Cast: ObjectIdChanged (165→304) + ZoneTransfer category=`CastSpell` |
| 140 | 304 | Stack→Battlefield (27→28) | Resolves. ETB trigger fires: AbilityInstanceCreated (id 309, parentId=304) |
| 140 | — | — | SelectTargetsReq for ETB — must pick opponent's creature/planeswalker |
| 144 | 304 | Battlefield (28) | ETB resolves: DamageDealt affectorId=304 → affectedIds=[288] (Shrine Keeper), damage=2, type=2 (non-combat) |
| 144 | 288→310 | Battlefield→GY (28→37) | Shrine Keeper dies: ZoneTransfer category=`SBA_Damage` |

ETB dealt 2 damage (= 2 × 1 vehicle, only Surgehacker itself was on board at cast time).

### Crew activation (gsId 271–274)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 271 | 356 | Stack (27) | Crew ability (grpId 76611) on stack, parentId=304 |
| 271 | — | — | PayCostsReq: effectCostType=`Select`, minSel=4 (power total), ids=[293, 348] weights=[2, 4] |
| 272 | 348 | Battlefield (28) | TappedUntappedPermanent: Brute Suit tapped (affectorId=356) |
| 272 | 293 | Battlefield (28) | TappedUntappedPermanent: Prosperous Thief tapped (affectorId=356) |
| 272 | — | — | CrewedThisTurn persistent annotation: affectorId=304, affectedIds=[293, 348] |
| 274 | 304 | Battlefield (28) | Surgehacker now a creature. ModifiedType + LayeredEffect persistent annotation (effect_id=7004, sourceAbilityGRPID=76611) |

Two creatures tapped: Brute Suit (power 4, weight=4) + Prosperous Thief (power 2, weight=2). Combined power 6 ≥ 4 crew cost. The `weights` field in the cost selection = creature power.

### Attacks

| gsId | Event | Damage |
|------|-------|--------|
| ~292 | Attack 1 | 7 combat damage to player 2 (with +2/+2 buff — PowerToughnessModCreated at gsId 282) |
| ~374 | Attack 2 | 5 combat damage to player 2 |
| ~437 | Attack 3 | 5 combat damage to player 2 |

### Annotations

**ETB resolve (gsId 144):**
- `DamageDealt` — affectorId=304, affectedIds=[288], damage=2, type=2 (non-combat), markDamage=1
- `ZoneTransfer` — zone_src=28 (Battlefield), zone_dest=37, category=`SBA_Damage` — Shrine Keeper dies from the 2 damage

**Crew payment (gsId 272):**
- `TappedUntappedPermanent` × 2 — affectorId=356 (crew ability on stack), tapped=1 for each crew source
- `CrewedThisTurn` (persistent) — affectorId=304 (vehicle), affectedIds=[293, 348] (crew sources). New annotation type — not previously documented.
- `ModifiedType` + `LayeredEffect` (persistent, gsId 274) — affectedIds=[304], sourceAbilityGRPID=76611, effect_id=7004. This is what makes the vehicle a creature.

**ShouldntPlay/RedundantActivation:**
- From gsId ~357 onward, server emits `ShouldntPlay` with Reason=`RedundantActivation`, abilityGrpId=76611 on Surgehacker (304). Fires when the vehicle is already crewed — redundant to crew again.
- Same annotation appears on Brute Suit (348, abilityGrpId=76556) starting earlier (~gsId 269). Both vehicles already crewed = both get RedundantActivation markers.

### Key findings

- **`CrewedThisTurn` is a new persistent annotation type.** Not in rosetta or catalog. Lists the vehicle as affectorId and all crew sources as affectedIds. Persists until end of turn.
- **Crew cost uses `PayCostsReq` with `effectCostType: Select`**, not mana payment. The `weights` field = creature power. `minSel` = crew number. `context: NonManaPayment`, `optionContext: Payment`.
- **`ModifiedType` + `LayeredEffect` persistent annotation** marks the vehicle-becomes-creature effect. `effect_id=7004` — this is the type-changing layered effect that makes it an artifact creature.
- **DamageDealt type=2 = non-combat damage.** Confirmed: type=1 is combat, type=2 is non-combat (matches existing rosetta for damage types).
- **`SBA_Damage` zone transfer category** — creature dying from ETB damage uses this category (not `Destroy` or `LethalDamage`). Zone dest=37 (not 33/Graveyard — needs rosetta check, may be a limbo zone before GY).
- **`RedundantActivation` ShouldntPlay** fires for already-crewed vehicles. Both vehicles get it independently. This is a UI hint — "don't offer crew again."

## Gaps for leyline

1. **Vehicle type support** — Vehicles are artifacts that become creatures when crewed. Leyline needs: (a) vehicle type recognition in card type mapping, (b) crew ability activation handling, (c) the `ModifiedType`/`LayeredEffect` persistent annotation to signal the type change to the client.
2. **Crew cost payment** — `PayCostsReq` with `effectCostType: Select` and power-weighted creature selection. This is a non-mana cost flow — need to implement the `NonManaPayment` context in cost payment handling.
3. **`CrewedThisTurn` persistent annotation** — new annotation type. Must be emitted when crew resolves, cleared at end of turn.
4. **ETB damage = 2× vehicle count** — the X calculation depends on counting vehicles you control. Forge handles this via `Count$Valid Vehicle.YouCtrl/Times.2`. Need to verify leyline's triggered ability → DamageDealt annotation path works for engine-calculated X values.
5. **`ShouldntPlay` / `RedundantActivation` emission** — server hints to the client that crewing an already-crewed vehicle is redundant. Low priority but needed for conformance.
6. **Zone 37 identification** — ETB damage kill sends creature to zone 37, not 33 (Graveyard). Need to confirm what zone 37 is and add to rosetta if missing.
7. **Update `docs/catalog.yaml`** — add `vehicle` and `crew` mechanics.

## Supporting evidence needed

- [ ] Other vehicles in recordings — Brute Suit (79681) in same session, compare crew annotation shape
- [ ] Vehicle with crew cost < total available power — does `minSel` always equal crew number?
- [ ] ETB with 2+ vehicles already on board — confirm X scales correctly (expect damage=4+ with 2 vehicles pre-ETB)
- [ ] Puzzle: minimal crew scenario — 1 creature exactly meeting crew cost, verify annotation sequence
