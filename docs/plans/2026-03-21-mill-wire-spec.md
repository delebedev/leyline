# Mill Wire Spec — 2026-03-21

Recording: `recordings/2026-03-21_23-30-58/md-frames.jsonl`
Human: seat 1. First mill observations (x6 milled cards total).

---

## Cards Involved

| Card | grpId | Role |
|---|---|---|
| Crow of Dark Tidings | 93668 | In hand — **never entered battlefield** |
| Billowing Shriekmass | 93769 | ETB triggered mill (instanceId 360) |
| Unknown Sorcery | 93756 | Cast mill sorcery (instanceId 333) |

**Critical finding:** Crow never triggered mill in this recording. It was drawn (Library→Hand) and subsequently discarded (Hand→GY category `Discard`, not `Mill`) via end-of-turn hand-size enforcement (grpId 189406). All observed mill triggers came from the sorcery and Shriekmass.

---

## Mill Triggers Observed

### Trigger 1 — gsId 193, frame 245
- **Source:** Sorcery instanceId 333 (grpId 93756), cast spell resolving from stack
- **Affector:** instanceId 333 (the spell itself acts as affectorId, as it's the ResolutionStart entity)
- **Cards milled:** 3 (instanceIds 337, 338, 339 — Library top three)
- **Phase:** Main1, turn 9

### Trigger 2 — gsId 255, frame 337
- **Source:** Ability instanceId 365 (grpId 101371) — ETB triggered ability of Billowing Shriekmass (93769) resolving off stack
- **Affector:** instanceId 365 (the ability instance on stack)
- **Cards milled:** 3 (instanceIds 366, 367, 368 — Library top three)
- **Phase:** Main1, turn 11

---

## Annotation Shape — Per Mill Card

Pattern is identical for every milled card. For each card the server emits two annotations (interleaved, one pair per card):

```
ObjectIdChanged  — affectorId=<ability/spell instance>
                   details: { orig_id: <hidden library id>, new_id: <revealed id> }

ZoneTransfer     — affectorId=<same affector>
                   affectedIds: [<new revealed id>]
                   details: { zone_src: 32 (Library), zone_dest: 33 (GY), category: "Mill" }
```

Full sequence for trigger 1 (3 cards, affectorId=333):

```
ResolutionStart    affectorId=333  affectedIds=[333]  details={grpid: 93756}
ObjectIdChanged    affectorId=333  affectedIds=[172]  details={orig_id:172, new_id:337}
ZoneTransfer       affectorId=333  affectedIds=[337]  details={zone_src:32, zone_dest:33, category:"Mill"}
ObjectIdChanged    affectorId=333  affectedIds=[173]  details={orig_id:173, new_id:338}
ZoneTransfer       affectorId=333  affectedIds=[338]  details={zone_src:32, zone_dest:33, category:"Mill"}
ObjectIdChanged    affectorId=333  affectedIds=[174]  details={orig_id:174, new_id:339}
ZoneTransfer       affectorId=333  affectedIds=[339]  details={zone_src:32, zone_dest:33, category:"Mill"}
```

Full sequence for trigger 2 (3 cards, affectorId=365):

```
ResolutionStart    affectorId=365  affectedIds=[365]  details={grpid: 101371}
ObjectIdChanged    affectorId=365  affectedIds=[177]  details={orig_id:177, new_id:366}
ZoneTransfer       affectorId=365  affectedIds=[366]  details={zone_src:32, zone_dest:33, category:"Mill"}
ObjectIdChanged    affectorId=365  affectedIds=[178]  details={orig_id:178, new_id:367}
ZoneTransfer       affectorId=365  affectedIds=[367]  details={zone_src:32, zone_dest:33, category:"Mill"}
ObjectIdChanged    affectorId=365  affectedIds=[179]  details={orig_id:179, new_id:368}
ZoneTransfer       affectorId=365  affectedIds=[368]  details={zone_src:32, zone_dest:33, category:"Mill"}
ResolutionComplete affectorId=365  affectedIds=[365]  details={grpid: 101371}
AbilityInstanceDeleted affectorId=360 affectedIds=[365]
```

Key differences between sorcery and ability triggers:
- Sorcery (trigger 1): no `ResolutionComplete` in the mill frame — it must fire later or may be omitted for spell resolution
- Ability ETB (trigger 2): has both `ResolutionComplete` and `AbilityInstanceDeleted` in the same frame

---

## Batching

Cards are **not batched** into a single annotation. Each milled card gets its own `ObjectIdChanged` + `ZoneTransfer` pair. All pairs for one trigger share the same `affectorId`. The 3 pairs are emitted in a single `GameStateMessage` diff.

---

## ObjectIdChanged Semantics

Library cards are hidden (anonymous integer IDs). When they mill, they are revealed:
- `orig_id` = the hidden library slot ID (sequential, e.g. 172, 173, 174)
- `new_id` = the new public instanceId (e.g. 337, 338, 339)
- Both `ObjectIdChanged` and `ZoneTransfer` reference the `new_id` in `affectedIds`
- The old hidden ID is retired to Limbo

The `EnteredZoneThisTurn` persistent annotation fires for all milled cards: `affectorId=33` (GY zone), `affectedIds=[...new ids...]`

---

## affectorId Chain

- **Sorcery mill:** affectorId = instanceId of the spell on stack (which is the card itself)
- **ETB ability mill:** affectorId = instanceId of the ability instance (type=Ability, on Stack zone 27), not the permanent that hosted the ability

The ability's host permanent (Shriekmass, instanceId 360) appears only in `AbilityInstanceDeleted.affectorId` after resolution.

---

## Crow of Dark Tidings — Not Triggered

In this recording, Crow was drawn twice (turns 11, 13) and discarded both times by end-of-turn effect before it could be cast. No ETB or attack mill triggers from Crow were observed.

Crow zone transfers seen:
- Library→Hand: category `Draw`
- Hand→GY: category `Discard` (via grpId 189406 = discard to hand size)

Crow ETB mill wire shape remains **unconfirmed from recording**. Based on the Shriekmass ETB pattern, the expected shape when Crow ETBs would be:
- An ability instance (type=Ability) for Crow's ETB ability put on stack
- `ResolutionStart` with Crow's ETB ability grpId
- `ObjectIdChanged` + `ZoneTransfer(category:"Mill")` for each milled card, affectorId = ETB ability instanceId
- 2 cards milled per ETB (Crow's text)

---

## Code Status

`TransferCategory.Mill` is defined (`TransferCategory.kt`) and handled in `AnnotationPipeline.kt` (line 327). The `CardMilled` event is emitted from `GameEventCollector` for Library→GY zone changes (`from == ZoneType.Library && to == ZoneType.Graveyard`).

**Gap: affectorId not propagated for Mill.**
`CardMilled` event (`GameEvent.kt:174`) carries only `forgeCardId` and `seatId` — no `sourceForgeCardId`. `affectorSourceFromEvents` (`AnnotationBuilder.kt:123`) only handles `CardSurveiled` and `CardDestroyed`. Mill ZoneTransfer annotations will emit with `affectorId=0` instead of the triggering ability instance.

Wire expectation: `affectorId` should be the ability instance ID (for ETB/triggered abilities) or the spell instanceId (for sorcery/instant). This requires:
1. `CardMilled` to carry `sourceForgeCardId` (the mill ability's host card)
2. `affectorSourceFromEvents` to handle `CardMilled`

Flag for engine-bridge agent: `CardMilled` needs `sourceForgeCardId` field analogous to `CardSurveiled` and `CardDestroyed`.

---

## catalog.yaml Status

`mill` is currently marked `status: wired`. That is accurate for category string and annotation structure. The affectorId gap is a fidelity issue, not a missing mechanic — category and zone transfer fire correctly. No status change needed; the gap is documented above.
