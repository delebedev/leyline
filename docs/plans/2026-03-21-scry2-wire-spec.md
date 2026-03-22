# Scry 2 Wire Spec — 2026-03-21

Cards analyzed:
- **Splinter, the Mentor** (grpId=100685) — scry 2 trigger, session `2026-03-21_21-22-14`
- **Temple of Malady** (grpId=94128) — scry 1 trigger, session `2026-03-21_22-31-46-game3`

---

## Key finding: scry with AI controller emits no GroupReq

Session `2026-03-21_21-22-14`: `systemSeatIds=[2]` throughout → seat 2 is the human, seat 1 is the Forge AI. The scry 2 ability (instanceId=324, grpId=100685) was controlled by seat 1. **No GroupReq was sent.** The AI resolved the scry internally and the server emitted the Scry annotation directly.

Session `2026-03-21_22-31-46-game3`: `systemSeatIds=[1]` on the GroupReq → seat 1 is the human who played Temple of Malady. **GroupReq was sent to seat 1.**

**Protocol rule: GroupReq is only sent when the scrying player is the human (systemSeatId) in the current connection. AI scry is handled entirely server-side; only the Scry annotation appears.**

This is architecturally correct for leyline: Forge resolves AI choices internally, so we would only send GroupReq for the human player. No code change needed for AI scry.

---

## Scry 1 full sequence (Temple of Malady, game3)

```
[568] S-C  GameStateMessage  gsId=7   ZoneTransfer (land ETB), AbilityInstanceCreated, ObjectIdChanged
[569] S-C  GameStateMessage  gsId=8   (priority pass)
[570] S-C  GameStateMessage  gsId=9   ResolutionStart(affectorId=280, grpid=176406)
                                      MultistepEffectStarted(affectorId=280, SubCategory=15)
[571] S-C  GroupReq          gsId=9   <-- scry decision prompt
[572..574] S-C GameStateMessage       (opponent/other-seat diffs, gsId=7,8,9)
[575..577] S-C Uimessage              (UI animation frames, seat 2)
[578] C-S  GroupResp         gsId=9   player chose bottom
[579] S-C  GameStateMessage  gsId=10  Scry annotation
                                      MultistepEffectComplete(affectorId=280, SubCategory=15)
                                      ResolutionComplete(affectorId=280, grpid=176406)
                                      AbilityInstanceDeleted(affectorId=279, affectedIds=[280])
```

### GroupReq shape (scry 1)

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

Notes:
- `lowerBound` absent (defaults to 0) for both specs — our builder sets `lowerBound=0` explicitly which is fine (zero-default, won't serialize differently)
- `upperBound` = 1 = number of cards in that spec group (= total scry count)
- `sourceId` = the ability instanceId on the stack (280)
- `promptId` present in the outer GroupReq message (separate from `groupReq` field)

### GroupResp shape (player chose bottom)

```json
{
  "groups": [
    { "zoneType": "Library", "subZoneType": "Top" },
    { "ids": [166], "zoneType": "Library", "subZoneType": "Bottom" }
  ],
  "groupType": "Ordered"
}
```

Group 0 (Top) has empty `ids`. Group 1 (Bottom) has `ids=[166]`.

### Scry annotation (scry 1, all to bottom)

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

---

## Scry 2 full sequence (Splinter, session 1)

```
[394] S-C  GameStateMessage  gsId=198  ResolutionStart(322), ResolutionComplete(322), ZoneTransfer, AbilityInstanceCreated(324)
           (Oteclan Landmark ETB resolves, creates scry-2 ability 324)
[395] S-C  GameStateMessage  gsId=199  (priority window)
[396] S-C  ActionsAvailableReq gsId=199
[397..398] S-C Uimessage
[399] C-S  PerformActionResp gsId=199  Pass
[400] S-C  GameStateMessage  gsId=200  ResolutionStart(324, grpid=100685)
                                       MultistepEffectStarted(affectorId=324, SubCategory=15)
[401..404] S-C Uimessage               (4 UI frames — no GroupReq, AI resolves internally)
[405] S-C  GameStateMessage  gsId=201  Scry annotation
                                       MultistepEffectComplete(affectorId=324, SubCategory=15)
                                       ResolutionComplete(affectorId=324, grpid=100685)
                                       AbilityInstanceDeleted(affectorId=322, affectedIds=[324])
```

No GroupReq, no GroupResp, no C-S message between MultistepEffectStarted and the Scry annotation.

### Scry annotation (scry 2, both to bottom)

```json
{
  "id": 403,
  "types": ["Scry"],
  "affectorId": 324,
  "affectedIds": [171, 172],
  "details": {
    "topIds": "?",
    "bottomIds": [171, 172]
  }
}
```

---

## Wire-format delta table: scry 1 vs scry 2

| Aspect | Scry 1 (Temple of Malady) | Scry 2 (Splinter AI) |
|---|---|---|
| GroupReq sent | Yes (human player) | No (AI resolves internally) |
| GroupReq instanceIds count | 1 | — |
| groupSpecs upperBound | 1 | — |
| Scry annotation affectorId | ability instanceId (280) | ability instanceId (324) |
| Scry annotation affectedIds | [card instanceId] (scalar in JSON) | [card1, card2] (array) |
| details.topIds | `"?"` (always) | `"?"` (always) |
| details.bottomIds | scalar int (166) | array [171, 172] |
| MultistepEffectStarted SubCategory | 15 | 15 |
| MultistepEffectComplete SubCategory | 15 | 15 |
| Library zone update | Cards moved to end of objectIds list | Cards moved to end |

Note on `bottomIds` scalar vs array: proto `KeyValuePairInfo.valueInt32` is `repeated int32`. The JSON decoder renders a single repeated value as a scalar integer rather than a single-element array. Our builder must always use `int32ListDetail("bottomIds", ...)` (repeated field) for consistency.

---

## Bugs in current leyline implementation

### Bug 1: Wrong Scry annotation detail keys (CRITICAL)

**Location:** `AnnotationBuilder.scry()` (~line 492)

Current:
```kotlin
.addDetails(int32Detail("topCount", topCount))
.addDetails(int32Detail("bottomCount", bottomCount))
```

Required by wire:
```
details.topIds = "?" (string sentinel, always)
details.bottomIds = [instanceId, ...] (list of card instanceIds sent to bottom)
```

The annotation currently emits wrong key names (`topCount`/`bottomCount` instead of `topIds`/`bottomIds`) and wrong value types (int counts instead of sentinel string / instanceId list).

**Fix:** Change the Scry annotation builder signature from `(seatId, topCount, bottomCount)` to `(affectorId, topInstanceIds, bottomInstanceIds)`. Emit:
- `topIds` as a `String` detail with value `"?"` (always)
- `bottomIds` as an `Int32List` detail with the actual instanceIds sent to bottom

The `affectedIds` field must also change (see Bug 2).

### Bug 2: Wrong affectedIds in Scry annotation (CRITICAL)

Current:
```kotlin
.addAffectedIds(seatId)
```

Required by wire: `affectedIds` = all card instanceIds being scried (not the seatId).
- Scry 1: `affectedIds=[166]` (the one card)
- Scry 2: `affectedIds=[171, 172]` (both cards)

### Bug 3: Wrong affectorId plumbing

The `AnnotationBuilder.scry()` currently receives `seatId` and topCount/bottomCount. The `affectorId` must be the triggering ability's instanceId (the thing on the stack that performed the scry). Currently it isn't even wired as a parameter.

**Fix needed in `AnnotationPipeline.mechanicAnnotations`**: pass the ability instanceId as affectorId. The `GameEvent.Scry` must carry the affector instanceId or the pipeline must look it up from the stack.

### Bug 4: GameEvent.Scry lacks required fields

Current `GameEvent.Scry`:
```kotlin
data class Scry(val seatId: Int, val topCount: Int, val bottomCount: Int)
```

Needs to carry:
- `affectorInstanceId: Int` — the ability instanceId (for annotation affectorId)
- `bottomCardIds: List<Int>` — forge card IDs of cards sent to bottom (for bottomIds detail)
- `topCardIds: List<Int>` — forge card IDs of cards kept on top (for affectedIds; topIds is always "?")

All card IDs must be resolved to instanceIds in the annotation pipeline via `idResolver`.

---

## GroupReq conformance for human scry

Our `GsmBuilder.buildSurveilScryGroupReq` is structurally correct for scry N:
- `instanceIds` = all N card instanceIds ✓
- `groupSpecs[0]`: Library/Top, upperBound=N ✓
- `groupSpecs[1]`: Library/Bottom, upperBound=N ✓
- `groupType` = Ordered ✓
- `context` = Scry ✓
- `sourceId` = from `game.stack.firstOrNull()` — verify this resolves to the correct ability instanceId

The lowerBound=0 explicit set is fine; proto wire-encodes it the same as absent.

---

## Action required

| Priority | Fix | Location |
|---|---|---|
| P0 | Fix Scry annotation detail keys: topIds/bottomIds + affectedIds = card instanceIds | `AnnotationBuilder.scry()`, `AnnotationPipeline.mechanicAnnotations()` |
| P0 | Add affectorInstanceId + cardId lists to GameEvent.Scry | `GameEvent.kt`, `GameEventCollector.kt`, `AnnotationPipeline.kt` |
| P1 | Update catalog.yaml scry notes to reflect correct wire format | `docs/catalog.yaml` |
| P1 | Get a human-scry-2 recording to confirm GroupReq with N=2 | playtest session needed |
| P2 | Verify sourceId in GroupReq resolves to correct ability instanceId | `TargetingHandler.sendGroupReqForSurveilScry` |

---

## Conformance note: no ZoneTransfer annotations for scry bottom

Unlike surveil (which emits `ObjectIdChanged` + `ZoneTransfer{category:Surveil}` per card), scry emits **no ZoneTransfer annotations**. The library zone objectIds update is visible in the GSM zone diff (cards move to end of library objectIds list), but there are no per-card transfer annotations. Only the single `Scry` annotation covers the entire effect.

This matches our current implementation which does not emit ZoneTransfer for scry. Confirmed correct.

---

## Related docs

- `docs/plans/2026-03-21-surveil2-wire-spec.md` — surveil N>1 analysis (parallel mechanic)
- `docs/catalog.yaml` entry: `scry` (needs update)
- Recording: `recordings/2026-03-21_21-22-14/` — scry 2, AI seat
- Recording: `recordings/2026-03-21_22-31-46-game3/` — scry 1, human seat
