# Cleric Class — Card Spec

## Identity

- **Name:** Cleric Class
- **grpId:** 77111
- **Set:** AFR (Adventures in the Forgotten Realms)
- **Type:** Enchantment — Class
- **Cost:** {W}
- **Forge script:** `forge/forge-gui/res/cardsfolder/c/cleric_class.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast enchantment {W} | `A:SP$ …` | ZoneTransfer CastSpell | wired |
| L1 static: life gain replacement (+1 extra) | `R:Event$ GainLife … ReplaceWith$ ReplaceGainLife` | ReplacementEffect pAnn | **missing — replacement not wired** |
| Level 2 activate ({3}{W}) | `K:Class:2:3 W` | AbilityInstanceCreated on stack | **missing — Class level-up not wired** |
| L2 trigger: life gain → +1/+1 counter on creature | `T:Mode$ LifeGained … Execute$ TrigPutCounter` | CounterAdded + SelectTargetsReq | **missing** |
| Level 3 activate ({4}{W}) | `K:Class:3:4 W` | AbilityInstanceCreated on stack | **missing — Class level-up not wired** |
| L3 trigger: when becomes L3, return creature from GY to BF | `T:Mode$ ClassLevelGained … Execute$ TrigReanimate` | ZoneTransfer GY→BF | **missing — unobserved** |

### Ability grpIds

| grpId | uniqueAbilityId | Description |
|-------|----------------|-------------|
| 133622 | 169 | L1: life gain replacement effect |
| 143561 | 170 | Level 2 activate (`{3}{W}`) |
| 146500 | 171 | L2: life gain → +1/+1 counter trigger |
| 143564 | 172 | Level 3 activate (`{4}{W}`) |
| 146501 | 173 | L3: becomes-level-3 trigger (reanimate) |

## What it does

1. **Cast** ({W}): enters the stack; one White mana paid.
2. **Resolve** (L1 ETB): moves Stack→BF. Two `LayeredEffectCreated` fire (effect_ids 7002 and 7003). The object keeps grpId=77111 throughout. The L1 replacement effect is active: if the controller would gain life, they gain that much plus 1 instead.
3. **Level 2 activate** ({3}{W}): player pays `{3}{W}` as a sorcery. Ability (grpId 143561, uniqueAbilityId 170) goes on stack. On resolve the Class transitions to L2. The L2 trigger becomes active: whenever the controller gains life, put a +1/+1 counter on target creature they control.
4. **L2 trigger** (whenever controller gains life): SelectTargetsReq (target creature controller controls), then CounterAdded (+1/+1, counter_type=1).
5. **Level 3 activate** ({4}{W}): player pays `{4}{W}` as a sorcery. Ability (grpId 143564, uniqueAbilityId 172) goes on stack. On resolve the Class transitions to L3, which fires the L3 triggered ability.
6. **L3 trigger** (when becomes L3): SelectTargetsReq (target creature card from controller's GY); ZoneTransfer GY→BF; controller gains life equal to that creature's toughness.

## Trace (session 2026-03-22_23-10-20, seat 1)

Seat 1 cast Cleric Class turn 1. The game ended T12 (AI win). The level-up was never activated — the player never had sufficient mana ({3}{W} requires 5 mana, game was over). The player gained no life all game, so the L1 replacement never fired.

### Cast and resolve

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 8 | 170→288 | Hand→Stack (31→27) | Cast: ObjectIdChanged 170→288, ZoneTransfer Hand→Stack, category=CastSpell; Plains (iid 287, abilityGrpId 1001) tapped for {W} |
| 10 | 288 | Stack→BF (27→28) | Resolve: ResolutionStart/Complete grpId=77111; LayeredEffectCreated ×2 (effect_ids 7002, 7003) affectorId=288; ZoneTransfer Stack→BF category=Resolve |

### Object shape at resolve (gs=10)

Object 288 on BF: grpId=77111, type=Enchantment, subtypes=[Class], color=White. Five uniqueAbilities present (all levels shown at L1):

```
uniqueAbilities { id: 169  grpId: 133622 }   // L1 replacement
uniqueAbilities { id: 170  grpId: 143561 }   // Level 2 activate
uniqueAbilities { id: 171  grpId: 146500 }   // L2 trigger
uniqueAbilities { id: 172  grpId: 143564 }   // Level 3 activate
uniqueAbilities { id: 173  grpId: 146501 }   // L3 trigger
```

All five abilities are present on the object from the moment it resolves at L1. The server does not add abilities incrementally on level-up — they are present but inactive. Only `EnteredZoneThisTurn` pAnn (affectorId=28, battlefieldZone) was observed at ETB; no ClassLevel pAnn was present.

### Level-up action in ActionsAvailableReq (T3 gs=43)

```
actions {
  actionType: Activate
  grpId: 77111
  instanceId: 288
  abilityGrpId: 143561      // Level 2 activate
  manaCost: {3}{W}
  uniqueAbilityId: 170
}
inactiveActions {
  actionType: Activate
  grpId: 77111
  instanceId: 288
  abilityGrpId: 143564      // Level 3 activate
  manaCost: {4}{W}
  disqualifyingSourceId: 288
  uniqueAbilityId: 172
}
```

Level 2 activate is offered as an active `Activate` action. Level 3 activate appears in `inactiveActions` with `disqualifyingSourceId=288` — the Class itself disqualifies it (you must be at L2 to activate L3).

## Annotations (from raw proto)

### Resolve (gs=10, file 000000025_MD_S-C_MATCH_DATA.bin)

All annotations have affectorId=288 (the Class itself; no separate ability instance):

- `ResolutionStart` — affectorId=288, affectedIds=[288], details={grpid: 77111}
- `LayeredEffectCreated` — affectorId=288, affectedIds=[7002], details={}
- `LayeredEffectCreated` — affectorId=288, affectedIds=[7003], details={}
- `ZoneTransfer` — affectorId=1 (seat), affectedIds=[288], zone_src=27, zone_dest=28, category=Resolve
- `ResolutionComplete` — affectorId=288, affectedIds=[288], details={grpid: 77111}

No separate `AbilityInstanceCreated` for any triggered ability at resolve — L1 has no ETB effect. No `ClassLevel` persistent annotation observed.

### Key findings

- **All 5 uniqueAbilities present at L1.** The server sends the full ability list for all three levels on the initial ETB object. The client determines which are active by level. No uniqueAbility additions or removals are expected on level-up — the transition must be communicated differently (likely via `ClassLevel` pAnn type=88 from the catalog, but this was not observed in this session).
- **Level-up is an `Activate` action (not `Cast`).** actionType in ActionsAvailableReq is `Activate_add3`, grpId=77111 (the enchantment itself, not a separate ability object). The abilityGrpId differentiates L2 (143561) from L3 (143564).
- **L3 is pre-disqualified at L1.** The L3 activate appears in `inactiveActions` immediately, with `disqualifyingSourceId=288`. This persists until the Class reaches L2.
- **Two LayeredEffectCreated at resolve.** Effect_ids 7002 and 7003. These likely correspond to the L1 replacement effect structure and the Class's level-gate infrastructure. No `LayeredEffect` persistent annotations were observed for Cleric Class (those appeared only for Dragoon's Lance in this session).
- **grpId 80908** — appears in card database alongside 77111 for "Cleric Class". Not observed on the wire in this session; likely a display variant (e.g., the foil/reprint version). All wire traffic used grpId=77111.

## Gaps for leyline

1. **Class level-up not wired.** The `K:Class:N:cost` activated ability needs to put an ability instance on the stack (grpId 143561/143564, source iid=288). On resolution the Class transitions to the new level. The wire shape for level-up resolution is unobserved — flag for engine-bridge; likely emits a `ClassLevel` pAnn update (type=88, niche catalog entry).
2. **ClassLevel pAnn (type=88) not mapped.** Catalog lists it as a niche pAnn. No instance was observed in this session (never leveled up). When level-up resolves, the server presumably sets or updates a `ClassLevel` pAnn on iid=288. Exact fields unknown — requires a session where leveling occurs.
3. **L1 life gain replacement not wired.** The `R:Event$ GainLife` replacement (grpId 133622) must intercept life gain events and add +1. The wire shape for replacement effects is documented at `docs/docs/rosetta.md` (ReplacementEffect pAnn observed for Reduce to Ashes). Expected: `ReplacementEffect` transient annotation + the modified `ModifiedLife` value. Exact sequencing unconfirmed for this card.
4. **L2 trigger (counter on life gain) not wired.** When controller gains life at L2, a triggered ability (grpId 146500) queues. Requires `SelectTargetsReq` for target creature and `CounterAdded` (counter_type=1, +1/+1). Wire shape follows standard counter trigger pattern.
5. **L3 trigger (reanimate from GY) not wired and unobserved.** The `T:Mode$ ClassLevelGained` trigger (grpId 146501) fires when the Class becomes L3. Requires `SelectTargetsReq` for target GY creature, ZoneTransfer GY→BF (category unknown — likely "Return" or "Reanimate"), then a life gain equal to toughness. No data point exists in this project's recordings.
6. **disqualifyingSourceId semantics.** The L3 activate appears in `inactiveActions` with `disqualifyingSourceId=288` (the Class itself). This must be generated correctly: at L1 both L3 is blocked; at L2 only L2 should be absent (already paid) and L3 offered; at L3 both level-up actions should be absent. Exact transition logic for removing/promoting inactiveActions on level-up requires an observed level-up session.

## Supporting evidence needed

- [ ] Session where Cleric Class reaches L2: confirm ClassLevel pAnn shape, L2 trigger wire, and whether uniqueAbilities list changes on level-up
- [ ] Session where Cleric Class reaches L3: confirm L3 trigger wire (SelectTargetsReq for GY, ZoneTransfer category, life gain annotation)
- [ ] Any session where L1 replacement fires: confirm ReplacementEffect annotation shape for life gain +1 modification
