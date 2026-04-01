# Mossborn Hydra — Card Spec

## Identity

- **Name:** Mossborn Hydra
- **grpId:** 93820 (primary), 95246 (variant art)
- **Set:** FDN
- **Type:** Creature — Elemental Hydra
- **Cost:** {2}{G}
- **P/T:** 0/0 base
- **Forge script:** `forge/forge-gui/res/cardsfolder/m/mossborn_hydra.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| ETB with +1/+1 counter | `K:etbCounter:P1P1:1` | `GameEventCardCounters` | wired |
| Trample | `K:Trample` | combat assignment | partial |
| Landfall trigger | `T:Mode$ ChangesZone \| Origin$ Any \| Destination$ Battlefield \| ValidCard$ Land.YouCtrl` | `GameEventZone` | wired |
| Double counters | `DB$ MultiplyCounter \| Defined$ Self \| CounterType$ P1P1` | `GameEventCardCounters` | wired |
| AssignDamageReq player slot | wire only | — | **partial (bug #235)** |

## What it does

1. **Enters with one +1/+1 counter** — 0/0 base means it dies without it; `K:etbCounter` fires at ETB resolution.
2. **Trample** — excess combat damage flows to the defending player/planeswalker.
3. **Landfall** — whenever any land you control enters, a triggered ability (grpId=175873) fires and doubles all +1/+1 counters via `MultiplyCounter`. Counter count = `previous × 2`, so each land drop is exponential: 1 → 2 → 4 → 8 → 16 → …
4. **Counter progression** — `transaction_amount` in `CounterAdded` is the delta added (not the new total); the persistent `Counter` pAnn tracks the running total via its `count` field.

## Trace (session 2026-03-29_16-18-08, seat 1)

Seat 1 (player, garnett#01186). Hydra cast T8, grew to 16/16 by gsId 434. Trample damage assignment observed at gsId 184.

### Zone transitions

| gsId | instanceId | Zone | Event |
|------|-----------|------|-------|
| 2    | 165        | Hand (31) | Opening hand |
| 114  | 165→303    | Hand→Stack (31→27) | Cast: ObjectIdChanged + ZoneTransfer category=`CastSpell` |
| 116  | 303        | Battlefield (28) | Resolve: ZoneTransfer category=`Resolve`, ETB counter |
| game end | 303  | Battlefield (28) | Persists at 16/16 |

### ETB counter resolution (gsId 116)

```
ResolutionStart    affectorId=303 grpid=93820
ResolutionComplete affectorId=303 grpid=93820
ZoneTransfer       affectorId=1 zone_src=27→zone_dest=28 category=Resolve
CounterAdded       affectorId=303 affectedIds=[303] counter_type=1 transaction_amount=1
PowerToughnessModCreated affectorId=303 power=1 toughness=1
LayeredEffectCreated     affectedIds=[7002]
```

Persistent annotations after ETB:
```
pAnn id=262  [ModifiedToughness, ModifiedPower, Counter]
             affectorId=4002 affectedIds=[303]
             details: count=1 counter_type=1
```

Object in diff: `power=1 toughness=1 uniqueAbilityCount=3`

### Landfall trigger (gsId 170 → 172)

Land ETB (gsId 170) fires the trigger immediately in the same diff:

```
ZoneTransfer       affectedIds=[320] zone_src=31→zone_dest=28 category=PlayLand
AbilityInstanceCreated affectorId=303 affectedIds=[321] source_zone=28
```

Ability object created: `instanceId=321 grpId=175873 zoneId=27 type=Ability`

Resolution (gsId 172):

```
ResolutionStart    affectorId=321 affectedIds=[321] grpid=175873
CounterAdded       affectorId=321 affectedIds=[303] counter_type=1 transaction_amount=1
PowerToughnessModCreated affectorId=303 power=1 toughness=1
LayeredEffectDestroyed   affectedIds=[7002]
LayeredEffectCreated     affectedIds=[7003]
ResolutionComplete affectorId=321 grpid=175873
AbilityInstanceDeleted   affectorId=303 affectedIds=[321]
```

Persistent Counter pAnn updated: `count=2`.

### Counter progression (all landfall resolutions)

| gsId | transaction_amount | Running count (pAnn) | LayeredEffect cycle |
|------|--------------------|----------------------|---------------------|
| 116  | +1 (ETB)           | 1                    | created 7002        |
| 172  | +1 (×2 from 1)     | 2                    | 7002→7003           |
| 289  | +2 (×2 from 2)     | 4                    | 7003→7005           |
| 365  | +4 (×2 from 4)     | 8                    | 7005→7008           |
| 434  | +8 (×2 from 8)     | 16                   | 7008→7012           |

**Key:** `transaction_amount` = delta only (not new total). `MultiplyCounter` adds `count` counters (equal to current count), so delta = current count at time of trigger.

The affectorId on `CounterAdded` is the **trigger ability instance** (e.g. iid 321), not the Hydra iid — same pattern as other triggered counters.

### LayeredEffect lifecycle

Each counter change destroys the existing P/T LayeredEffect and creates a new one with a fresh monotonically-increasing effect_id. Pattern: `LayeredEffectDestroyed [old_id]` → `LayeredEffectCreated [new_id]` in the same diff. The effect_ids are not sequential (+1) — they appear to skip values (7002, 7003, 7005, 7008, 7012).

ETB is special: `LayeredEffectCreated` fires without a preceding `LayeredEffectDestroyed` (no prior effect exists).

### Trample damage assignment (gsId 184)

AssignDamageReq raw proto (file `000000191_MD_S-C_MATCH_DATA.bin`):

```
assignDamageReq {
  damageAssigners {
    instanceId: 303          # Hydra
    totalDamage: 2
    assignments {
      instanceId: 316        # blocker creature iid
      minDamage: 1
      assignedDamage: 1
    }
    assignments {
      instanceId: 2          # defending player — instanceId = seatId (2)
      maxDamage: 1
      assignedDamage: 1
    }
    decisionPrompt {
      promptId: 8
      parameters { parameterName: "CardId" type: Number numberValue: 303 }
    }
  }
}
```

Combat phase context (gsId 183–184): T8, CombatDamage step, Hydra = 2/2 at this point (first landfall), blocked by one creature. Total damage = 2 (lethal to blocker = 1, overflow = 1 to player).

**Player slot:** `instanceId: 2` = `seatId` of the defending player. This is the fix needed for issue #235.

DamageDealt (gsId 185):

```
DamageDealt affectorId=303 affectedIds=[316] damage=1 type=1  # blocker
DamageDealt affectorId=316 affectedIds=[303] damage=1 type=1  # blocker hits back
DamageDealt affectorId=303 affectedIds=[2]   damage=1 type=1  # trample overflow to player
```

`affectedIds=[2]` for player damage — `seatId` used as the target instanceId.

## Key findings

1. **Player slot in AssignDamageReq uses seatId directly** — `instanceId = seatId` (value 2 for seat 2). Not a synthetic ID. Fixes issue #235: wire `seatId` as the player assignment slot in `CombatHandler.sendAssignDamageReq`.

2. **CounterAdded transaction_amount = delta, not total** — when doubling from 4→8, `transaction_amount=4`. The persistent Counter pAnn `count` field always reflects the running total. Leyline must not emit the total as `transaction_amount`.

3. **LayeredEffect is replaced on every counter change** — `LayeredEffectDestroyed` + `LayeredEffectCreated` every time P/T changes from a counter event. Effect IDs are monotonically increasing but not sequential (gaps observed). This is the same pattern seen on other P/T buff sources.

4. **Landfall abilityGrpId = 175873** — the trigger ability object on the stack uses this grpId. Distinct from the Hydra card grpId (93820). AbilityInstanceCreated fires in the same diff as the land's PlayLand ZoneTransfer.

5. **affectorId on CounterAdded = trigger ability iid** — for landfall triggers (not ETB). ETB: `affectorId=303` (Hydra iid, self-referential). Landfall: `affectorId=321` (trigger ability instance iid).

6. **Counter pAnn affectorId = 4002** — the persistent `[ModifiedToughness, ModifiedPower, Counter]` pAnn always uses `affectorId=4002`. This appears to be a server-side constant for the "counter-based P/T modification" effect source. Consistent across all counter updates.

## Gaps for leyline

1. **Issue #235 — AssignDamageReq defender slot** — add `instanceId=seatId` assignment entry for the defending player in `CombatHandler.sendAssignDamageReq`. Blocker gets `minDamage=lethalDamage`, player gets `maxDamage=overflow`. Pre-condition: trample + blocked.

2. **Landfall as a catalog mechanic** — not listed in `docs/catalog.yaml`. Functionally works (it's a triggered ability on land ETB, standard trigger wire), but not documented with status. Should add entry under `creatures:` or `triggered-abilities:`.

3. **ETB counter grpId** — the ETB counter fires with `affectorId=303` (self), no separate ability grpId in the annotation. If leyline emits a different affectorId here, the client may misattribute the counter source. Verify `GameEventCardCounters` → `CounterAdded` affectorId assignment in `AnnotationPipeline`.

## Supporting evidence

- Session `2026-03-29_16-18-08` (seat 1): cast T8, 4 landfall triggers, grew 1→2→4→8→16, trample combat at gsId 184
- Session `2026-03-29_16-32-23`: grew to 20/20 over 26 turns (corroborates counter progression; not traced in detail here)
- Issue #235 confirmed closed by finding: player `instanceId = seatId`

## Agent Feedback

### Tooling/commands that were slow or missing

- **No gsId filter on `tape proto decode-recording`** — the JSONL decoder produces all 656 messages. Filtering by gsId required a separate Python one-liner every time. A `--gsid-range 110:125` flag would eliminate most intermediate scripts.
- **`tape proto zone-history <iid>`** — doesn't exist but would be the single most useful command for card specs. The current trace command shows every frame that mentions an iid (including actions), making it noisy to extract zone transitions.
- **`tape proto inspect` only accepts a file path**, not a gsId or session+gsId pair. Every raw proto inspection requires knowing the exact filename from the JSONL output first. A `--session / --gsid` shortcut would save two lookup steps.
- **`just tape proto find-card` only shows first/last zone per transition** — doesn't show which gsId each transition happened at, forcing a manual scan to find the right frame.

### Information that was hard to find

- **Landfall grpId (175873)** — not in any index. Required decoding the JSONL, searching for `AbilityInstanceCreated` on iid 303, then looking up the created ability object. No shortcut.
- **Counter pAnn affectorId=4002** — opaque constant. No documentation anywhere. Had to observe empirically that it's always 4002 across multiple counter updates.
- **LayeredEffect ID gaps (7002→7003→7005)** — the non-sequential progression is confusing without context. It implies other effects on the board are consuming IDs in the same counter space. No way to know this without cross-referencing all LayeredEffect events in each diff.
- **Catalog status for "landfall"** — `landfall` doesn't appear anywhere in `catalog.yaml`. Had to infer it's wired because triggered-abilities in general are wired, not because there's a specific entry.

### What would have saved time

- **Pre-decoded JSONL in the recording directory** (e.g. `seat-1-decoded.jsonl` alongside `md-payloads/`) — the decode step is deterministic and could run automatically after capture.
- **A `tape proto counter-history <iid> -s <session>` sub-command** — prints all CounterAdded/CounterRemoved events for a given iid with gsId, affectorId, transaction_amount, and running total. This is the most common counter spec pattern.
- **A `tape proto assign-damage -s <session>` command** — finds all AssignDamageReq frames and prints the full proto text. Currently requires knowing the file name from the JSONL index, then calling `tape proto inspect` on the raw bin.
- **Ability grpId index** — a lookup table mapping grpId → card name + ability description. Currently `just card-grp` maps card names → grpIds but not the reverse (ability grpId → human label). When 175873 shows up as a trigger, there's no fast way to confirm it's the Mossborn Hydra landfall without cross-referencing the recording.

### Suggestions for the card-spec skill

- **Add a "Counter progression" section template** — a table with columns `[gsId, event, delta, running_total, effect_cycle]` is the right structure for any counter-growth card. Having it as a template section would speed up specs for +1/+1 counter creatures significantly.
- **Explicitly request the raw proto for AssignDamageReq** in the trample/combat mechanics checklist — the decoded JSONL drops the inner proto fields, so the raw inspect is always needed for damage assignment specs.
- **Note in the template** that `tape proto find-card` output is the starting point for zone transitions but requires follow-up decoded JSONL work to get annotation details.
- **Template should prompt for "affectorId patterns"** as a distinct subsection — many bugs come from wrong affectorId on CounterAdded, DamageDealt, or AbilityInstanceCreated. Making it explicit in the spec format surfaces these for every card.
