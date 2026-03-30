# Treasure Map — Card Spec

## Identity

- **Name:** Treasure Map // Treasure Cove
- **grpId:** 87432 (front face) / 87433 (back face)
- **Set:** XLN
- **Type:** Artifact (front) / Land (back)
- **Cost:** {2} (front face only)
- **Forge script:** `forge/forge-gui/res/cardsfolder/t/treasure_map_treasure_cove.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| Cast artifact | `A:SP$ …` | wired |
| Activated ability: Scry 1 + landmark counter | `A:AB$ Scry \| Cost$ 1 T \| SubAbility$ DBLandmark` | **unknown — transform trigger not wired** |
| Branch on 3+ counters → remove + transform + tokens | `DB$ Branch … DBRemoveCtrs … DBTransform … DBTreasureTokens` | **missing** |
| DoubleFaced transform | `AlternateMode:DoubleFaced` | **missing** |
| Treasure Cove: {T} → add {C} | `A:AB$ Mana \| Cost$ T \| Produced$ C` | wired (post-transform land) |
| Treasure Cove: {T}, Sac Treasure → draw | `A:AB$ Draw \| Cost$ T Sac<1/Treasure>` | wired (Sac-for-draw) |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 1308 | Scry 1 + landmark counter activated ability (front face) |
| 1152 | Treasure Cove: {T} add {C} |
| 116859 | Treasure Cove: {T}, Sacrifice a Treasure: draw a card |

## What it does

1. **Cast** ({2}): artifact resolves to battlefield. No ETB effect.
2. **Activation** ({1}, {T}): ability instance (grpId 1308) goes on stack. On resolution: GroupReq (Scry) for 1 card, then CounterAdded (counter_type=127, +1 landmark). If the total reaches 3, the same resolution also fires CounterRemoved (−3), silently mutates the object from grpId 87432 → 87433, and emits three TokenCreated events.
3. **Treasure Cove (back face)**: land. {T}: add {C} (abilityGrpId 1152). {T}, Sacrifice a Treasure: draw a card (abilityGrpId 116859).

## Trace (session 2026-03-25_22-22-14, seat 1)

Seat 1 (human) drew Treasure Map (iid 313→315), cast it turn 7, activated it three times across turns, transforming on the third activation. The same session has subsequent Treasure Cove activations (mana tap) and Treasure sacrifice for mana.

### Draw and cast

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 128 | 313 | Hand (31) | Drawn: ObjectIdChanged 313→313, ZoneTransfer Library→Hand, category=Draw |
| 132 | 313→315 | Hand→Stack (31→27) | Cast: ObjectIdChanged 313→315, ZoneTransfer Hand→Stack, category=CastSpell; 2 mana paid (Island + Forest) |
| 134 | 315 | Battlefield (28) | Resolved: ResolutionStart/Complete grpId=87432; ZoneTransfer Stack→BF, category=Resolve; uniqueAbilityCount=1 |

### First activation (counter → 1)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 135 | 318 | Stack (27) | Ability iid 318 grpId=1308 on stack; AbilityInstanceCreated affectorId=315; tap cost paid (TappedUntappedPermanent); 1 Island tapped for mana |
| 137 | — | — | GroupReq promptId=92, context="Scry", 1 card (iid 175), groupSpecs=[Library/Top, Library/Bottom], groupType=Ordered |
| 138 | 318 deleted | — | Resolve: Scry annotation (affectorId=318); MultistepEffectComplete SubCategory=15; CounterAdded counter_type=127 amt=1; ResolutionComplete grpId=1308; AbilityInstanceDeleted |
| 138 | pAnn 306 | — | Counter pAnn: affectorId=4002, affectedIds=315, count=1, counter_type=127 |

### Second activation (counter → 2)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 249 | 372 | Stack (27) | Ability iid 372 grpId=1308 on stack; same shape as first activation |
| 252 | 372 deleted | — | Resolve: Scry, CounterAdded counter_type=127 amt=1; ResolutionComplete grpId=1308 |
| 252 | pAnn 306 | — | Counter pAnn updated: count=2, counter_type=127 |

### Third activation — transform trigger (counter 2→3→0 + transform + 3 tokens)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 295 | 387 | Stack (27) | Ability iid 387 grpId=1308 on stack; AbilityInstanceCreated affectorId=315; tap cost + mana paid |
| 298 | 315 | Battlefield (28) | Object 315 silently changes: grpId 87432→87433, cardTypes=Land, isTapped=true, uniqueAbilityCount=2 |
| 298 | — | — | CounterAdded counter_type=127 amt=1 (peak=3); CounterRemoved counter_type=127 amt=3 (all removed) |
| 298 | 389, 390, 391 | Battlefield | TokenCreated ×3 (each separate annotation); grpId=87485, type=Token, subtype=Treasure |
| 298 | pAnn 699 | — | ColorProduction: affectorId=315, affectedIds=315, colors=12 (new land ability) |
| 298 | pAnn 306 deleted | — | Counter pAnn removed (no counters remain) |

### Treasure Cove post-transform

After transform, ActionsAvailableReq includes:
- `ActivateMana` instanceId=315, grpId=87433, abilityGrpId=1152 ({T}: add {C})
- `Activate` instanceId=315, grpId=87433, abilityGrpId=116859 ({T}, Sacrifice Treasure: draw)

Treasure sacrifice for draw (gsId 364) follows the standard mana-ability bracket per Treasure already documented: AbilityInstanceCreated / TappedUntappedPermanent / ObjectIdChanged / ZoneTransfer (category=Sacrifice) / UserActionTaken / ManaPaid / AbilityInstanceDeleted, then the draw ability resolves.

## Annotations (from raw proto)

### Third activation — transform resolution (gsId 298, file 000000295_MD_S-C_MATCH_DATA.bin)

All annotations share `affectorId=387` (the ability instance):

- `Scry` — affectorId=387, affectedIds=[179], details={topIds: 179, bottomIds: "?"}
- `MultistepEffectComplete` — affectorId=387, affectedIds=[1], details={SubCategory: 15}
- `CounterAdded` — affectorId=387, affectedIds=[315], counter_type=127, transaction_amount=1
- `CounterRemoved` — affectorId=387, affectedIds=[315], counter_type=127, transaction_amount=3
- `TokenCreated` — affectorId=387, affectedIds=[389] (no details)
- `TokenCreated` — affectorId=387, affectedIds=[390] (no details)
- `TokenCreated` — affectorId=387, affectedIds=[391] (no details)
- `ResolutionComplete` — affectorId=387, affectedIds=[387], grpid=1308
- `AbilityInstanceDeleted` — affectorId=315, affectedIds=[387]

**No separate transform annotation fires.** The transform is expressed entirely by the object diff: grpId changes from 87432 to 87433, cardTypes changes from Artifact to Land, and uniqueAbilityCount changes from 1 to 2. The object keeps the same instanceId (315) throughout.

### First activation resolve (gsId 138)

- `Scry` — affectorId=318, affectedIds=[175], details={topIds: "?", bottomIds: 175} (card sent to bottom)
- `MultistepEffectComplete` — affectorId=318, affectedIds=[1], details={SubCategory: 15}
- `CounterAdded` — affectorId=318, affectedIds=[315], counter_type=127, transaction_amount=1
- `ResolutionComplete` — affectorId=318, affectedIds=[318], grpid=1308
- `AbilityInstanceDeleted` — affectorId=315, affectedIds=[318]

### Key findings

- **counter_type=127 = LANDMARK.** First observation of this counter type. No P/T mod fires (landmark has no stat effect). Persistent Counter pAnn (affectorId=4002) tracks running total.
- **Transform is an in-place grpId mutation.** Same pattern as Concealing Curtains (prior conformance research (Concealing Curtains transform)): object stays at same instanceId, grpId silently changes, no ZoneTransfer. The back face appears with the expected new card types and ability count.
- **CounterAdded + CounterRemoved fire in the same diff.** The branch logic (check ≥3, then remove all, then transform, then tokens) produces all events atomically in gsId 298. The counter reaches 3 and is immediately removed — the client never observes a persistent annotation with count=3.
- **Three TokenCreated annotations, one per token.** Each is a separate annotation with affectorId=ability instance. No batching. Token grpId=87485 (Treasure), consistent with existing prior conformance research.
- **MultistepEffectComplete (SubCategory=15) fires on every activation resolve** — precedes CounterAdded. Appears to be a mandatory sequencing marker for multi-step activated abilities.
- **Scry uses GroupReq** (not SelectNReq) — consistent with prior conformance research (GroupReq for surveil/scry). context="Scry", groupSpecs=[Library/Top, Library/Bottom], groupType=Ordered.
- **grpId in ActionsAvailableReq reflects current face.** Before transform: grpId=87432. After transform: grpId=87433. The client tracks this from the object diff.

## Gaps for leyline

1. **AlternateMode:DoubleFaced not wired.** The branch ability (DBBranch → DBRemoveCtrs → DBTransform → DBTreasureTokens) resolves entirely in the Forge engine, but leyline does not emit the grpId mutation in the Diff object. The transform moment requires: CounterRemoved, three TokenCreated, and the object diff changing grpId/cardTypes/uniqueAbilityCount — all in one Diff GSM.
2. **counter_type=127 (LANDMARK) not mapped.** CounterMapper needs `LANDMARK → 127`. Update `docs/card-specs/SYNTHESIS.md` (counter types).
3. **CounterAdded + CounterRemoved in same resolution.** The DBBranch executes synchronously — the single ability resolution emits both a CounterAdded (the +1 that gets you to 3) and then CounterRemoved (−3). The emit order must match: Scry → MultistepEffectComplete → CounterAdded → CounterRemoved → TokenCreated ×3 → ResolutionComplete → AbilityInstanceDeleted.
4. **Three Treasure tokens created atomically.** DBTreasureTokens creates 3 tokens in one ability step. Each needs its own TokenCreated annotation (three separate annotations, same affectorId). Token grpId=87485.
5. **Post-transform object diff.** The Diff must update: grpId (87432→87433), cardTypes ([Artifact]→[Land]), uniqueAbilityCount (1→2), isTapped=true (tap was part of the activation cost). A new ColorProduction persistent annotation (colors=12) must appear for the land.
6. **Treasure Cove abilities in ActionsAvailableReq.** Post-transform, leyline must offer ActivateMana (abilityGrpId=1152) and Activate (abilityGrpId=116859) for instanceId=315 with grpId=87433.

## Supporting evidence needed

- [ ] Update `docs/card-specs/SYNTHESIS.md` counter type table with counter_type=127 (LANDMARK)
- [ ] Cross-check prior conformance research (Concealing Curtains transform) — confirm the in-place grpId mutation pattern also applies here (not just Concealing Curtains)
- [ ] Puzzle: `treasure-map.pzl` — enter with 2 landmark counters already on Treasure Map; one activation should trigger transform + 3 tokens
- [ ] Verify Treasure Cove draw ability (abilityGrpId=116859) wire shape — gsId=364 in session shows the Sac-for-mana bracket but the draw resolution was not captured (player used mana for another spell instead)
