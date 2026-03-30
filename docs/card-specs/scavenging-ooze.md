# Scavenging Ooze — Card Spec

## Identity

- **Name:** Scavenging Ooze
- **grpId:** 93945 (FDN), 71986, 74650 (other printings)
- **Set:** FDN
- **Type:** Creature — Ooze
- **Cost:** {1}{G}
- **P/T:** 2/2
- **Forge script:** `forge/forge-gui/res/cardsfolder/s/scavenging_ooze.txt`
- **Ability grpId:** 90106 (activated ability)

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Activated ability (mana cost) | `A:AB$ ChangeZone \| Cost$ G` | `GameEventCardChangeZone` | wired |
| Targeted exile from any graveyard | `Origin$ Graveyard \| ValidTgts$ Card` | `GameEventCardChangeZone` | wired |
| Conditional +1/+1 counter | `DB$ PutCounter \| ConditionPresent$ Creature` | `GameEventCardCounters` | wired |
| Conditional life gain | `DB$ GainLife \| ConditionPresent$ Creature` | `GameEventPlayerLivesChanged` | wired |

## What it does

1. **Activated ability** ({G}): Exile target card from a graveyard (any player's).
2. **Conditional bonus**: If the exiled card was a creature card, put a +1/+1 counter on Scavenging Ooze and you gain 1 life.
3. Can be activated at instant speed, multiple times per turn if you have the mana.

## Trace (session 2026-03-30_20-33, seat 1)

Two copies cast across the game. First copy (iid 296) died in combat T7. Second copy (iid 321) survived the whole game and activated 5 times, targeting cards from both own and opponent graveyards.

### Cast #1 — T4 Main1

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 72 | 163→296 | Hand→Stack | CastSpell, paid {1}{G} |
| 74 | 296 | Stack→Battlefield | Resolve |

### Death — T7 Combat

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 154 | 296 | Battlefield | Took 5 damage in combat (blocked 5/5) |
| 154 | 296→319 | Battlefield→Graveyard | SBA_Damage — died with 2 toughness |

### Cast #2 — T8 Main1

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 167 | 305→321 | Hand→Stack | CastSpell, paid {1}{G} |
| 169 | 321 | Stack→Battlefield | Resolve |

### Activation 1 — T8 Main1 (non-creature, opponent GY)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 170 | 321→324 | Battlefield→Stack | AbilityInstanceCreated (grp 90106), PlayerSelectingTargets |
| 174 | 318→326 | Opponent GY (zone 37)→Exile | Exiled Tactical Advantage (instant) — **no counter, no life gain** |

### Activation 2 — T10 Main1 (creature, own GY)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 216 | 321→330 | Battlefield→Stack | AbilityInstanceCreated, PlayerSelectingTargets |
| 220 | 319→332 | Own GY (zone 33)→Exile | Exiled Scavenging Ooze #1 (creature) |
| 220 | — | — | **CounterAdded** (type=1, +1/+1) on iid 321. P/T → 3/3 |
| 220 | — | — | **ModifiedLife** (+1) on seat 1. LayeredEffectCreated |

### Activation 3 — T16 Main1 (non-creature, own GY)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 369 | 321→368 | Battlefield→Stack | AbilityInstanceCreated |
| 373 | 338→370 | Own GY (zone 33)→Exile | Exiled Bulk Up (instant) — **no counter, no life gain** |

### Activation 4 — T22 Main2 (creature, opponent GY)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 530 | 321→441 | Battlefield→Stack | AbilityInstanceCreated, PlayerSelectingTargets |
| 534 | 385→443 | Opponent GY (zone 37)→Exile | Exiled Charmed Stray (creature, opponent-owned) |
| 534 | — | — | **CounterAdded** (+1/+1) on iid 321. LayeredEffectDestroyed + Created (counter update) |
| 534 | — | — | **ModifiedLife** (+1) on seat 1 |

### Activation 5 — T26 Main1 (creature, own GY)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 635 | 321→497 | Battlefield→Stack | AbilityInstanceCreated, PlayerSelectingTargets |
| 639 | 386→499 | Own GY (zone 33)→Exile | Exiled Spinner of Souls (creature) |
| 639 | — | — | **CounterAdded** (+1/+1) on iid 321. LayeredEffectDestroyed + Created |
| 639 | — | — | **ModifiedLife** (+1) on seat 1 |

### Annotations

**Activation resolution (gsId 220) — creature target, full sequence:**
- `ResolutionStart` affectorId=330 (ability), grpid=90106
- `ObjectIdChanged` 319→332 + `ZoneTransfer` zone_src=33 (own GY), zone_dest=29 (Exile), category=`Exile`
- `CounterAdded` affectorId=330, affectedIds=[321], counter_type=1 (+1/+1), transaction_amount=1
- `PowerToughnessModCreated` affectedIds=[321], power=1, toughness=1
- `LayeredEffectCreated` affectedIds=[7003]
- `ModifiedLife` affectorId=330, affectedIds=[1] (seat 1), life=+1
- `ResolutionComplete` grpid=90106

**Activation resolution (gsId 174) — non-creature target:**
- `ResolutionStart` + `ObjectIdChanged` + `ZoneTransfer` (zone 37→29, category=`Exile`)
- `ResolutionComplete` — **no CounterAdded, no ModifiedLife** (condition check: not a creature)

### Key findings

- **Zone 37 = opponent's graveyard (seat 2).** Own graveyard = zone 33. The ability correctly targets cards in either graveyard.
- **Creature check is server-side.** When a non-creature is exiled, the resolution sequence simply omits CounterAdded and ModifiedLife. No separate annotation signals "condition not met."
- **LayeredEffectDestroyed + Created pairs** appear on subsequent counter additions (gs 534, 639) — the counter's layered effect gets replaced each time the counter total changes.
- **No PlayerSelectingTargets for activation 3** (T16, gs 369) — the targeting annotation was absent from that GSM, though the activation still worked normally. Possibly omitted when there's only one legal target.
- **P/T tracks correctly.** After 3 creature exiles, the Ooze was 5/5 (2/2 base + 3 counters), confirmed by the cumulative PowerToughnessModCreated annotations.
- **ActionType_Activate with abilityGrpId=90106** is how the client offers the activated ability. manaCost contains a single {G} entry.

## Gaps for leyline

1. **Activated ability targeting any graveyard.** ActionMapper must present the ability when any graveyard has a legal target (any card). The `ValidTgts$ Card` in Forge means any card type in any graveyard, not just creatures.
2. **Cross-graveyard targeting.** Target selection must span both players' graveyards. Zone 33 (own) and 37 (opponent) are distinct zone IDs. The ability's target pool is the union.
3. **Conditional counter + life gain.** After the exile resolves, leyline must check if the exiled card was a creature. If yes: emit CounterAdded + PowerToughnessModCreated + ModifiedLife. If no: skip all three. The Forge engine handles this via `ConditionPresent$ Creature` on the chained sub-abilities.
4. **LayeredEffect churn on counter updates.** Each +1/+1 counter addition triggers LayeredEffectDestroyed (old total) + LayeredEffectCreated (new total). Need to verify EffectTracker handles this correctly for multiple counter additions to the same permanent.

## Supporting evidence needed

- [ ] Other activated-ability-from-graveyard cards (e.g., Deathrite Shaman) for targeting pattern variance
- [ ] Verify Forge's `ConditionPresent$ Creature` fires correctly for opponent-owned creatures exiled from opponent GY
- [ ] Puzzle: Scavenging Ooze activation targeting both creature and non-creature to verify counter/life conditional path

---

## Tooling Feedback

### What worked well

- **`just scry-ts trace "Scavenging Ooze" --game`** — excellent. Clear timeline, auto-grouped by turn/phase, shows all annotation types with key details. This is the single most useful command for card investigation.
- **`just scry-ts gsm show N --game --json`** — essential for drilling into specific GSMs. Piping to jq/python works perfectly.
- **`just scry-ts board --game --gsid`** — great for getting graveyard/battlefield context at a point in time. Made it easy to identify what cards were legal targets.
- **`just scry-ts game cards`** — good overview of what cards appeared in a game.
- **`--game` with substring matching** — typing `2026-03-30_20-33` instead of the full timestamp is convenient.

### What was missing or awkward

- **No card identity in trace exile lines.** The trace shows instanceIds being exiled but doesn't name the card. Had to cross-reference `gsm show --json` for each one to find out what was being exiled. Adding the grpId/name of affected cards in the trace output would save many round-trips.
- **No way to filter trace by annotation type.** Something like `trace "Scavenging Ooze" --game X --annotations CounterAdded,ModifiedLife` would let you quickly find only the "interesting" activations.
- **`gsm list --has` not tested** — didn't need it for this investigation since `trace` covered the card's journey. Would be useful for finding GSMs with specific annotation types across a whole game.
- **Zone 37 not labeled in trace.** The trace output says `zone_src=Graveyard (seat 1)` for zone 33 but the opponent's graveyard zone isn't labeled in the card-level trace (it shows in the ZoneTransfer annotations as raw zone 37). The `board` command correctly shows "Graveyard (seat 2)" but the trace doesn't resolve opponent zone IDs to seat numbers.

### What would have made it easier

- **Card name resolution in annotations.** When a ZoneTransfer affects iid X, showing `[X = Charmed Stray]` inline would eliminate the need to separately look up each game object.
- **Diff between two GSMs.** `gsm diff 220 174` to see exactly what annotations differ between a creature-target and non-creature-target resolution would be extremely useful for conditional abilities like this.
- **`trace --json` for machine-readable output.** The trace output is human-readable but hard to pipe. A JSON mode for the trace itself (not just gsm show) would enable scripted analysis.
