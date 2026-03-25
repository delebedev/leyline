# Drake Hatcher — Card Spec

## Identity

- **Name:** Drake Hatcher
- **grpId:** 93748
- **Set:** FDN
- **Type:** Creature — Human Wizard
- **Cost:** {1}{U}
- **Base P/T:** 1/3
- **Forge script:** `forge/forge-gui/res/cardsfolder/d/drake_hatcher.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| Vigilance | `K:Vigilance` | wired |
| Prowess | `K:Prowess` | wired |
| Combat damage trigger → incubation counters | `T:Mode$ DamageDone … Execute$ TrigPutCounter` | **unknown — counter_type 200 not documented** |
| Activated ability: remove 3 incubation counters → create Drake token | `A:AB$ Token \| Cost$ SubCounter<3/INCUBATION>` | **unknown — SubCounter cost not tested** |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 15 | Vigilance |
| 137 | Prowess |
| 175786 | Combat damage trigger (put incubation counters) |
| 175787 | Activated ability (remove 3 → Drake token) |

## What it does

1. **Vigilance** — does not tap when attacking.
2. **Prowess** — whenever you cast a noncreature spell, gets +1/+1 until end of turn (trigger goes to Pending zone, then stack).
3. **Combat damage trigger** — whenever it deals combat damage to a player, put that many incubation counters on it. Damage dealt = 3 (base 1 + 2×prowess pump in recorded game) → 3 incubation counters placed.
4. **Activated ability** — pay `{-3 incubation counters}`: create a 2/2 blue Drake creature token with flying. No mana cost. Ability goes on stack, counters removed at activation, token created on resolution.

## Trace (session 2026-03-25_22-37-18, seat 1)

Seat 1 (human) controlled Drake Hatcher (iid 299). Cast on turn 10 from hand, attacked the same turn (after two prowess pumps), dealt 3 combat damage, accumulated 3 incubation counters, and activated to create a Drake token (iid 351) in the same post-combat main phase. Second copy (iid 360) cast from graveyard region later (opponent had another copy, grpId 93748 iid 360 at zone 28).

### Cast

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 61 | 289 | Hand (31) | Drake Hatcher in opening hand (seat 1) |
| 99 | 289→299 | Limbo→Stack | Cast: ObjectIdChanged + ZoneTransfer (hand→stack); isTapped=false (Vigilance) |
| 101 | 299 | Battlefield (28) | Resolved to BF; P/T 1/3; uniqueAbilities 194(Vigilance), 195(Prowess), 196(175786), 197(175787) |

### Prowess trigger (first pump — gsId 192–196)

Drake Hatcher has Prowess (grpId 137). When a noncreature spell was cast (iid 319 on stack, e.g. Think Twice), the prowess trigger (iid 324) appeared in zone 25 (Pending), then moved to stack alongside another trigger.

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 192 | 324 | Pending (25) | Prowess trigger iid 324 grpId=137 queued; SelectNReq for trigger ordering (context=TriggeredAbility) |
| 194 | 324 | Stack (27) | Prowess trigger on stack; objectSourceGrpId=93748; parentId=299 |
| 196 | 324 | — | Trigger resolves: LayeredEffectCreated + PowerToughnessModCreated (+1/+1); ResolutionComplete grpId=137; AbilityInstanceDeleted; iid 299 updated to P/T 2/4 |

### Attack declaration and combat damage (gsId 220–222)

Drake Hatcher attacked unblocked (Vigilance — not tapped). At combat damage step:

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 220 | 299 | Battlefield (28) | DeclareBlock step; iid 299 shows attackState=Attacking, blockState=Unblocked, attackInfo.targetId=2; P/T 3/5 (after two prowess pumps) |
| 222 | 339 | Stack (27) | CombatDamage step; DamageDealt fired (damage=3, type=1, markDamage=1); AbilityInstanceCreated iid 339 grpId=175786 (damage trigger queued); opponent life 20→14 (SyntheticEvent+ModifiedLife=-6, also 310 hit for 3) |

Note: iid 299 is not tapped in gsId 228 — Vigilance confirmed on wire (no isTapped=true after attack).

### Counter placement (gsId 224)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 224 | 339 | — | Trigger resolves: ResolutionStart grpId=175786; CounterAdded affectedIds=299, counter_type=200, transaction_amount=3; ResolutionComplete; AbilityInstanceDeleted 339 |
| 224 | 299 pAnn | persistent | Counter pAnn id=496: affectorId=4002, affectedIds=299, type=Counter, count=3, counter_type=200 |

### Activated ability: remove counters → token (gsId 261–263)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 261 | 350 | Stack (27) | Activated ability iid 350 grpId=175787 on stack; AbilityInstanceCreated affectorId=299; CounterRemoved counter_type=200, transaction_amount=3; UserActionTaken actionType=2, abilityGrpId=175787 |
| 262 | 350 | Stack (27) | Priority passes |
| 263 | 351 | Battlefield (28) | Ability resolves: ResolutionStart grpId=175787; TokenCreated affectedIds=351; ResolutionComplete; AbilityInstanceDeleted 350; persistent pAnn EnteredZoneThisTurn affectedIds=[351, 341] |

Drake token (iid 351): grpId=94163, type=Token, P/T 2/2, subtype=Drake, color=Blue, flying (grpId 8), hasSummoningSickness=true, objectSourceGrpId=93748.

### Annotations (from raw proto)

**Prowess resolve (gsId 196, file 000000149_MD_S-C_MATCH_DATA.bin):**
- `LayeredEffectCreated` — affectorId=324, affectedIds=7002 (effect slot)
- `PowerToughnessModCreated` — affectorId=324, affectedIds=299, power=+1, toughness=+1
- `ResolutionComplete` — affectorId=324, grpid=137
- `AbilityInstanceDeleted` — affectorId=299, affectedIds=324
- persistent `[ModifiedToughness, ModifiedPower, LayeredEffect]` — affectorId=299, affectedIds=299, sourceAbilityGRPID=137, effect_id=7002

**Combat damage step (gsId 222, file 000000206_MD_S-C_MATCH_DATA.bin):**
- `DamageDealt_af5a` — affectorId=299, affectedIds=2 (player), damage=3, type=1, markDamage=1
- `AbilityInstanceCreated` — affectorId=299, affectedIds=339, source_zone=28 ← damage trigger goes on stack
- `SyntheticEvent` — affectorId=299, affectedIds=2, type=1
- `ModifiedLife` — affectorId=299, affectedIds=2, life=-6 (both attackers combined)
- persistent `TriggeringObject` — affectorId=339, affectedIds=299, source_zone=28

**Counter placement (gsId 224, file 000000217_MD_S-C_MATCH_DATA.bin):**
- `ResolutionStart` — affectorId=339, affectedIds=339, grpid=175786
- `CounterAdded` — affectorId=339, affectedIds=299, counter_type=200, transaction_amount=3
- `ResolutionComplete` — affectorId=339, affectedIds=339, grpid=175786
- `AbilityInstanceDeleted` — affectorId=299, affectedIds=339
- persistent `Counter` — affectorId=4002, affectedIds=299, count=3, counter_type=200

**Activation (gsId 261, file 000000259_MD_S-C_MATCH_DATA.bin):**
- `AbilityInstanceCreated` — affectorId=299, affectedIds=350, source_zone=28
- `CounterRemoved` — affectorId=350, affectedIds=299, counter_type=200, transaction_amount=3
- `UserActionTaken` — affectorId=1 (seat), affectedIds=350, actionType=2, abilityGrpId=175787
- ActionsAvailableReq at gsId 228 confirmed `Activate_add3` on instanceId=299, abilityGrpId=175787, uniqueAbilityId=197

**Token creation (gsId 263, file 000000263_MD_S-C_MATCH_DATA.bin):**
- `ResolutionStart` — affectorId=350, affectedIds=350, grpid=175787
- `TokenCreated` — affectorId=350, affectedIds=351 (no details field)
- `ResolutionComplete` — affectorId=350, affectedIds=350, grpid=175787
- `AbilityInstanceDeleted` — affectorId=299, affectedIds=350
- persistent `EnteredZoneThisTurn` — affectorId=28, affectedIds=[351, 341]

### Key findings

- **counter_type=200 = INCUBATION.** This is a new counter type not previously documented. The reference doc (`counter-type-reference.md`) only covers type 1 (+1/+1) and type 108 (quest/lore). Type 200 needs adding.
- **CounterAdded/CounterRemoved shape is consistent** with +1/+1 counters — same keys (`counter_type`, `transaction_amount`), just type=200 instead of 1. No P/T mod fires (incubation has no stat effect).
- **Counter pAnn affectorId = 4002/4003** — these appear to be sequentially assigned IDs for the persistent state annotation, not tied to the ability instance. Same pattern as equipment Attachment pAnn IDs.
- **Activated ability uses `SubCounter` cost** — no mana required. The wire shows CounterRemoved fires at gsId 261 (activation) before the ability resolves at 263. The removal is immediate on activation, not on resolution.
- **TriggeringObject fires as persistent annotation** at combat damage step, matching the established pattern (source_zone=28).
- **Vigilance confirmed** — iid 299 never shows `isTapped: true` after attacking. No special wire annotation needed — the engine handles it.
- **Drake token has no separate TokenCreated details** — same as previously observed tokens (token-creation-wire.md). `objectSourceGrpId=93748` on the token object traces it back to Drake Hatcher.
- **Prowess trigger stacking** — two prowess triggers queued at gsId 192 (two noncreature spells cast in sequence), SelectNReq used for ordering. This matches the existing selectnreq-modal-protocol.md pattern.

## Gaps for leyline

1. **counter_type=200 (INCUBATION) not mapped** — CounterMapper (or equivalent) needs `INCUBATION → 200`. The engine uses `CounterType.INCUBATION`; the wire needs type=200. Update `counter-type-reference.md`.
2. **SubCounter activation cost** — `Cost$ SubCounter<3/INCUBATION>` in Forge DSL. Need to verify ActivatedAbilityHandler passes the `SubCounter` cost correctly, and that CounterRemoved fires at activation time (not resolution). The wire shows CounterRemoved in the same GSM as AbilityInstanceCreated (gsId 261).
3. **Incubation counter persistent annotation** — the pAnn at gsId 224 uses `affectorId=4002` (a generated ID, not the ability instance). Verify leyline's Counter pAnn emitter generates a stable ID for the state annotation, distinct from the transient CounterAdded annotation.
4. **No PowerToughnessModCreated for incubation** — incubation counters do not modify P/T. Verify the engine does not emit a stray P/T mod annotation when adding these counters (it should not, but worth a puzzle check).
5. **Drake token grpId 94163** — verify `TokenRegistry` (or `TokenFactory`) maps the Forge token script `u_2_2_drake_flying` to grpId 94163 and that `objectSourceGrpId` on the token object is set to 93748 (the hatcher, not the token's own grpId).

## Supporting evidence needed

- [ ] Update `docs/annotation-field-notes.md` with counter_type=200 (INCUBATION)
- [ ] Update `docs/card-specs/../annotation-field-notes.md` — CounterRemoved at activation time (before resolution) confirmed
- [ ] Update `counter-type-reference.md` memory file with type 200
- [ ] Cross-reference: other cards with INCUBATION counters (e.g. Ozolith, The Shattered Spire) to confirm type=200 is universal
- [ ] Puzzle: `drake-hatcher.pzl` — cast Drake, cast noncreature spell (prowess pump), attack unblocked, trigger resolves, counters placed, activate ability, Drake token created
- [ ] Confirm `Activate_add3` action type is correctly offered by leyline for SubCounter cost abilities (ActionsAvailableReq uniqueAbilityId=197 seen at gsId 228)
