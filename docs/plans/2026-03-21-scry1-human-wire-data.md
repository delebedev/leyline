# Scry 1 + Draw (Opt) — Human Wire Data — 2026-03-21

## Recording status

**Target recording: `recordings/2026-03-21_23-30-58/`** — Opt (grpId 93661) present but never cast.

The Opt card (iid 174 → assigned iid 339 on graveyard arrival) was **milled** by a mill spell
(Inspiration from Beyond, grpId 93756) at gsId 193. ZoneTransfer category "Mill", zone_src=32
(Library) → zone_dest=33 (Graveyard). No scry, no draw, no GroupReq in this session.

`analysis.json` confirms: no `scry` mechanic in `mechanicsExercised`. No GroupReq or Scry
annotation type anywhere in `md-frames.jsonl`.

**No Opt-cast recording exists.** Grepping all 2026-03-21 sessions for grpId 93661 finds only this
session (2 hits, both the milled card). A playtest session with Opt in the deck and cast is needed
to capture the full scry-1-then-draw sequence.

---

## Scry 1 + draw wire prediction (Opt, grpId 93661)

Based on Temple of Malady scry 1 wire data (session `2026-03-21_22-31-46-game3`, documented in
`docs/plans/2026-03-21-scry2-wire-spec.md`) and the established scry wire protocol, the expected
sequence for Opt resolving:

```
S-C  GameStateMessage  gsId=N    ResolutionStart(affectorId=<Opt-iid>, grpid=93661)
                                  MultistepEffectStarted(affectorId=<Opt-iid>, SubCategory=15)
                                  [Opt is visible: grpId exposed on stack iid]
S-C  GroupReq          gsId=N    instanceIds=[<top-lib-iid>]
                                  groupSpecs[0]: Library/Top, upperBound=1
                                  groupSpecs[1]: Library/Bottom, upperBound=1
                                  groupType=Ordered, context=Scry, sourceId=<Opt-iid>
C-S  GroupResp         gsId=N    groups[0]={Top, ids=[<iid>]} OR groups[1]={Bottom, ids=[<iid>]}
S-C  GameStateMessage  gsId=N+1  Scry annotation (affectorId=<Opt-iid>, affectedIds=[<scryed-iid>])
                                  details: {topIds: "?", bottomIds: <iid-if-kept-bottom>}
                                  MultistepEffectComplete(SubCategory=15)
                                  [possibly in same diff or separate diff:]
                                  ZoneTransfer(category=Draw) for the drawn card
                                  ResolutionComplete(affectorId=<Opt-iid>, grpid=93661)
                                  AbilityInstanceDeleted
```

**Key open question: Scry and Draw bundled or split?**

For Opt specifically, the card has two effects in sequence: Scry 1, *then* draw a card. The server
may emit them in the same gsId diff or split across two diffs. The Temple of Malady scry 1 (only
scry, no draw) fits in a single diff. For Opt, the draw step is a second effect — expect either:

- **Option A (single diff):** Both Scry and ZoneTransfer(Draw) appear in gsId N+1 (resolution diff).
- **Option B (split diffs):** Scry annotation in gsId N+1, then separate gsId N+2 with MultistepEffectComplete + ZoneTransfer(Draw) + ResolutionComplete.

The `MultistepEffectStarted`/`MultistepEffectComplete` SubCategory=15 bracket the entire
multistep resolution. With two effects, the Complete likely comes after both — supporting Option A
or Option B depending on whether the engine batches them.

A recording is required to confirm.

---

## Scry 1 confirmed wire data (Temple of Malady ETB, game3)

Source: `recordings/2026-03-21_22-31-46-game3/` (already in scry2-wire-spec.md — duplicated here
for self-contained Opt reference).

### GroupReq (idx=571, gsId=9, seat=[1])

```json
{
  "instanceIds": [166],
  "groupSpecs": [
    { "upperBound": 1, "zoneType": "Library", "subZoneType": "Top" },
    { "upperBound": 1, "zoneType": "Library", "subZoneType": "Bottom" }
  ],
  "groupType": "Ordered",
  "context": "Scry",
  "sourceId": 280
}
```

- `instanceIds`: exactly 1 entry (= scry count). Card was iid 166, grpId 94043.
- `sourceId` 280 = the ability instanceId on the stack (land ETB trigger).
- The outer message also has `promptId: 92` distinct from `sourceId`.

### GroupResp (idx=578, gsId=9, C-S)

```json
{
  "groups": [
    { "zoneType": "Library", "subZoneType": "Top" },
    { "ids": [166], "zoneType": "Library", "subZoneType": "Bottom" }
  ],
  "groupType": "Ordered"
}
```

Player sent iid 166 to bottom. Group 0 (Top) has no `ids` field. Group 1 (Bottom) has `ids=[166]`.

### Scry annotation (gsId=10, seat=[1])

```json
{
  "id": 65,
  "types": ["Scry"],
  "affectorId": 280,
  "affectedIds": [166],
  "details": {
    "topIds": "?",
    "bottomIds": 166
  }
}
```

- `affectorId`: ability instanceId (280), not seatId.
- `affectedIds`: list of all scryed card instanceIds — `[166]` for scry 1.
- `details.topIds`: always the string sentinel `"?"`.
- `details.bottomIds`: scalar `166` (single int, not array) when only 1 card goes to bottom.
  Proto repeated field: server serializes a length-1 repeated as a scalar JSON int. Our builder
  must use the repeated-int32 detail method regardless of count.

### Library zone after scry-to-bottom (gsId=10)

```
Library objectIds: [167, 168, ..., 218, 166]
```

iid 166 moved from position 0 (top) to the end (bottom). For scry-to-top it stays at position 0.
No ZoneTransfer annotation — only the library zone objectIds update.

### Resolution sequence in gsId=10 diff

```
Scry annotation (id=65)
MultistepEffectComplete (id=66, SubCategory=15)
ResolutionComplete (id=67, affectorId=280, grpid=176406)
AbilityInstanceDeleted (id=68, affectorId=279, affectedIds=[280])
```

All four in the same diff. For Opt (two effects), the draw step would follow Scry before
MultistepEffectComplete.

---

## Differences between Opt scry+draw vs land-ETB scry-only

| Aspect | Temple of Malady ETB | Opt (predicted) |
|---|---|---|
| affectorId type | land ETB trigger iid | Opt spell iid on stack |
| grpId in ResolutionStart | 176406 (ability) | 93661 (spell) |
| effects in resolution | scry 1 only | scry 1 + draw 1 |
| Draw annotation in same diff | n/a | expected ZoneTransfer(Draw) |
| GroupReq | yes (human is scrying) | yes (human is scrying) |
| GroupReq context | "Scry" | "Scry" |
| GroupReq sourceId | ability instanceId | Opt stack instanceId |

---

## Bug cross-reference

Bugs in current leyline implementation confirmed against scry-2-wire-spec.md:

1. **Wrong detail keys** — `topCount`/`bottomCount` should be `topIds` (string `"?"`) /
   `bottomIds` (repeated int32 instanceIds). Applies to Opt identically.
2. **Wrong affectedIds** — currently `seatId`, must be the scryed card instanceId(s).
3. **Wrong affectorId plumbing** — `GameEvent.Scry` must carry the triggering ability instanceId.
4. **Missing draw coupling** — for Opt specifically, `GameEvent.Scry` must be followed by
   `GameEvent.Draw`. The annotation pipeline emits them independently but they must appear in the
   correct order within the same diff (or consecutive diffs — to be confirmed by recording).

Bug details and fix locations: `docs/plans/2026-03-21-scry2-wire-spec.md` §Bugs.

---

## Action required

- **Capture an Opt playtest session.** Run `just serve-proxy`, build a deck with Opt (grpId 93661),
  cast it while the human is seat 1, scry a card (both top and bottom, ideally two separate casts).
  The session will capture the GroupReq/GroupResp/Scry+Draw sequence.
- Determine whether Scry annotation and Draw ZoneTransfer appear in the same gsId diff (Option A)
  or split diffs (Option B). This affects how `AnnotationPipeline` must order its outputs.
- Temple of Deceit (grpId 94125, scry 1 ETB) also appeared in session 2026-03-21_23-30-58 as a
  drawn card that was discarded (hand→graveyard at gsId 203) — never played, no ETB scry to observe.

---

## Related

- `docs/plans/2026-03-21-scry2-wire-spec.md` — full protocol for scry 1 (confirmed) and scry 2
- `docs/annotation-field-notes.md` — annotation patterns reference
- Recording `2026-03-21_22-31-46-game3` — only confirmed human scry 1 wire data
- Recording `2026-03-21_23-30-58` — Opt present (milled), no scry
