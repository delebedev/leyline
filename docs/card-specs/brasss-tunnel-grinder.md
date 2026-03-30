# Brass's Tunnel-Grinder — Card Spec

## Identity

- **Name:** Brass's Tunnel-Grinder // Tecutlan, the Searing Rift
- **grpId:** 87283 (front face)
- **Set:** LCI
- **Type:** Legendary Artifact (front) / Legendary Land — Cave (back)
- **Cost:** {2}{R} (front); no mana cost (back)
- **Forge script:** `forge/forge-gui/res/cardsfolder/b/brasss_tunnel_grinder_tecutlan_the_searing_rift.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| Cast artifact | `A:SP$ ...` | wired |
| ETB: discard any number, draw that many +1 | `T:Mode$ ChangesZone \| ValidCard$ Card.Self \| Destination$ Battlefield` | **missing** |
| Descended condition tracking | `ValidPlayer$ You.descended` | **missing** |
| End-step bore counter trigger | `T:Mode$ Phase \| Phase$ End of Turn` | **missing** |
| Bore counter (counter_type 182) | `CounterType$ BORE` | **missing** |
| Transform at 3+ bore counters | `DB$ SetState \| Mode$ Transform` | **missing** |
| Back face: {T} add {R} | `A:AB$ Mana \| Cost$ T` | wired (generic land) |
| Back face: discover on permanent spell cast | `T:Mode$ SpellCast \| ValidCard$ Permanent` | **missing** |

## What it does

1. **Cast (turn 6, {2}{R}):** 3 mana paid (Swamp + 2 Mountains). Resolves onto battlefield as Legendary Artifact.
2. **ETB trigger:** When Brass's Tunnel-Grinder enters, a triggered ability (grpId 169600) fires. The controller uses SelectNReq to choose cards to discard from hand (0–N), then draws that many plus one. Player discarded 1 card, drew 2.
3. **Descended condition:** After a permanent card goes from the player's hand to graveyard (via ETB discard), the `AbilityWordActive(Descended)` persistent annotation fires on iid 303 for the rest of that turn.
4. **End-step bore counter trigger:** At the beginning of the controller's end step, if they descended that turn, the bore counter trigger (grpId 169601) fires. Counter_type 182 is added to iid 303. A persistent `Counter` annotation tracks the current count.
   - T6 end step: count 0 → 1
   - T10 end step: count 1 → 2
5. **Transform:** After 3+ bore counters, all bore counters are removed and the card transforms into Tecutlan, the Searing Rift (Legendary Land). **Not observed in this session** — game ended at T13 with count=2.

## Trace (session 2026-03-25_22-31-50, seat 1)

Seat 1 (human) cast Brass's Tunnel-Grinder on turn 6. Game ended at T13 (opponent won via combat damage) with 2 bore counters. Transform was not reached.

### Cast (gsId 100, T6 Main1)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 100 | 167→303 | Limbo→Stack (30→27) | Cast: ObjectIdChanged(167→303) + ZoneTransfer(zone_src=31, zone_dest=27, category=CastSpell); 3× mana bracket (Swamp+2 Mountains tapped) |
| 102 | 303 | Stack→Battlefield (27→28) | Resolve: ResolutionStart/Complete(grpid=87283), ZoneTransfer(zone_src=27, zone_dest=28, category=Resolve); ETB trigger AbilityInstanceCreated(affectorId=303, affectedIds=[307], grpId=169600 on stack) |

### ETB trigger resolution (gsId 104–105)

| gsId | Notes |
|------|-------|
| 104 (Send, not HiFi) | ResolutionStart(affectorId=307, grpid=169600); SelectNreq fired (promptId=1480, gsId=104) |
| 104 (SelectNreq) | context=`Resolution_a163`, optionContext=`Resolution_a9d7`, listType=Dynamic; ids=[165,168,169,170,301] (hand contents); maxSel=5; sourceId=303 |
| 105 | Discard: ObjectIdChanged(168→308) + ZoneTransfer(affectorId=307, zone_src=31, zone_dest=33, category=Discard); Draw ×2: ObjectIdChanged+ZoneTransfer(category=Draw) for each drawn card; ResolutionComplete(grpid=169600); AbilityInstanceDeleted(affectorId=303, affectedIds=[307]) |

### Descended annotation (gsId 105)

After the ETB ability resolves (a permanent card entered the graveyard from hand), a persistent `AbilityWordActive` annotation appears:
- `affectorId: 1` (seat), `affectedIds: [303]`
- `details: { "AbilityWordName": "Descended" }`

No `threshold` or `value` fields — just the name flag. Annotation is present only while the condition holds that turn.

### End-step bore counter (gsId 121–123, T6 Ending/End)

| gsId | Notes |
|------|-------|
| 121 | PhaseOrStepModified (phase=5/End); AbilityInstanceCreated(affectorId=303, affectedIds=[311]) — bore counter trigger pushed to stack; new Ability obj iid=311 grpId=169601 |
| 123 | ResolutionStart(affectorId=311, grpid=169601); CounterAdded(affectorId=311, affectedIds=[303], counter_type=182, transaction_amount=1); ResolutionComplete; AbilityInstanceDeleted; Counter pAnn(affectorId=4002, affectedIds=[303], count=1, counter_type=182) |

Second bore counter at T10 (gsId 258→260):
- Same shape: AbilityInstanceCreated(affectorId=303), resolve+CounterAdded(transaction_amount=1), Counter pAnn count=2.

### Annotations (from raw proto)

**Cast (gsId 100):**
- `ObjectIdChanged` — orig_id=167, new_id=303
- `ZoneTransfer` — zone_src=31 (Hand), zone_dest=27 (Stack), category=`CastSpell`
- 3× mana bracket: AbilityInstanceCreated / TappedUntappedPermanent / UserActionTaken(actionType=4, abilityGrpId=1003 or 1004) / ManaPaid(color=3 or 4) / AbilityInstanceDeleted

**Resolve to battlefield (gsId 102):**
- `ResolutionStart` — affectorId=303, affectedIds=[303], grpid=87283
- `ResolutionComplete` — affectorId=303, grpid=87283
- `ZoneTransfer` — affectorId=1, affectedIds=[303], zone_src=27, zone_dest=28, category=`Resolve`
- `AbilityInstanceCreated` — affectorId=303, affectedIds=[307], source_zone=28 (ETB trigger on stack)
- pAnn `TriggeringObject` — affectorId=307, affectedIds=[303], source_zone=28

**ETB ability resolution (gsId 104, Send frame):**
- `ResolutionStart` — affectorId=307, affectedIds=[307], grpid=169600

**ETB discard+draw (gsId 105):**
- `ObjectIdChanged` — orig_id=168, new_id=308; affectorId=307
- `ZoneTransfer` — affectorId=307, affectedIds=[308], zone_src=31, zone_dest=33, category=`Discard`
- `ObjectIdChanged` × 2 (drawn cards); `ZoneTransfer` × 2, category=`Draw`
- `ResolutionComplete` — affectorId=307, grpid=169600
- `AbilityInstanceDeleted` — affectorId=303, affectedIds=[307]
- pAnn `AbilityWordActive` — affectorId=1, affectedIds=[303], `{"AbilityWordName":"Descended"}`

**Bore counter trigger push (gsId 121):**
- `PhaseOrStepModified` — affectedIds=[1], phase=5 step=0
- `AbilityInstanceCreated` — affectorId=303, affectedIds=[311], source_zone=28

**Bore counter resolve (gsId 123):**
- `ResolutionStart` — affectorId=311, grpid=169601
- `CounterAdded` — affectorId=311, affectedIds=[303], counter_type=182, transaction_amount=1
- `ResolutionComplete` — affectorId=311, grpid=169601
- `AbilityInstanceDeleted` — affectorId=303, affectedIds=[311]
- pAnn `Counter` — affectorId=4002, affectedIds=[303], count=1, counter_type=182

### Key findings

- **grpId 169600** = ETB "discard any number, draw that many +1" triggered ability
- **grpId 169601** = end-step bore counter triggered ability
- **counter_type 182** = bore counter (card-specific, no `PowerToughnessModCreated` pairing)
- **ETB discard uses SelectNReq** with `context=Resolution_a163`, `optionContext=Resolution_a9d7`, `listType=Dynamic` — identical shape to other discard SelectNReq (not Static/Discard_a163 as leyline currently produces)
- **Descended AbilityWordActive:** affectorId=1 (seat), no threshold/value fields — simpler than Threshold wire shape
- **Bore counter trigger fires on end step if descended this turn** — the ability resolves with ResolutionStart/CounterAdded/ResolutionComplete pattern, identical to other triggered counter additions
- **Transform not observed** in this session (game ended at count=2). Based on prior DFC work (Concealing Curtains), expect silent grpId mutation in Diff object for the transformed permanent, same instanceId, no ZoneTransfer.
- **ETB trigger confirmation:** The wire splits across two GSMs: the ResolutionStart fires in a `Send` (not `HiFi`) diff at gsId=104, then the actual card movements (discard/draw) complete at gsId=105 as a `HiFi` diff.

## Gaps for leyline

1. **ETB "discard any number, draw N+1" trigger** — needs SelectNReq with correct context/optionContext keys, Dynamic listType, then Discard+Draw sequence from resolution.
2. **Descended condition tracking** — need to detect when a permanent card enters graveyard from anywhere during a turn, and emit `AbilityWordActive(Descended)` pAnn on the artifact's iid. Reset at end of turn.
3. **Bore counter trigger (end-step, conditional on Descended)** — triggered ability that checks descended state, pushes to stack, resolves with CounterAdded(counter_type=182).
4. **Bore counter threshold check + transform** — after 3 bore counters: remove all bore counters, then transform (grpId mutation from 87283 → back-face grpId, silent in Diff, no ZoneTransfer). Counter pAnn should disappear.
5. **Back face (Tecutlan):** mana ability ({T}: {R}) + triggered discover ability when permanent spell cast using mana from this land — both new mechanics.

## Supporting evidence needed

- [ ] Observe transform: need a session where Brass's Tunnel-Grinder reaches 3 bore counters — confirm grpId mutation in Diff and counter pAnn removal
- [ ] Confirm back-face grpId (Tecutlan, the Searing Rift) — need `just card-grp "Tecutlan"` or game with transform
- [ ] Validate `AbilityWordActive(Descended)` reset timing — should be absent in the next turn's opening diffs
