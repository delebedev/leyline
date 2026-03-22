# Explore Wire Spec — 2026-03-21

Session: `recordings/2026-03-21_22-05-00/`
Cards: Cenote Scout (87332, ETB explore), Seeker of Sunlight (87369, activated explore)

## Session Limitations

**Cenote Scout was never cast.** Four copies appear in seat 2's library (instanceIds 242, 243, 245, 253),
visible only in the full-state snapshot emitted during Bushwhack's search (gsId 52). They were never
played, so ETB explore is not in the recording.

**Seeker of Sunlight's activated explore was never triggered.** Instance 282 is on battlefield
(resolve at gsId 11), ability grpId 169690 appears in the actions list through turn 6, but there
is no C->S PerformActionResp activating it.

**Only explore-adjacent sequence captured:** Bushwhack (93928) land-search-and-reveal. The server
uses the same `RevealedCardCreated` annotation for both Bushwhack's explicit reveal and an explore
reveal — the annotation shape is identical. This gives a confirmed wire template for the reveal step.

## Confirmed: Bushwhack Land-Search Reveal Sequence (gsId 49→54)

This is the closest analog to explore in the recording. Explore's reveal step uses the same
annotation shape.

### Message Sequence Table

| # | gsId | prevGsId | Dir | Type | updateType | Key fields |
|---|------|----------|-----|------|------------|------------|
| 1 | 49 | 48 | S→C | GameStateMessage | Send | Bushwhack cast: ObjectIdChanged (288→290), ZoneTransfer (Hand→Stack, CastSpell) |
| 2 | 50 | 49 | S→C | GameStateMessage | SendHiFi | Mana tapped, ManaPaid, UserActionTaken |
| 3 | 51 | 50 | S→C | GameStateMessage | SendHiFi | (actions list refresh only) |
| 4 | 52 | 51 | S→C | GameStateMessage | Send | ResolutionStart (grpid 93928); full library objects revealed to viewer |
| 5 | 53 | 52 | S→C | GameStateMessage | SendHiFi | RevealedCardCreated for land; ZoneTransfer (Library→Hand, Put); ObjectIdChanged for found card |
| 6 | 54 | 53 | S→C | GameStateMessage | SendAndRecord | Shuffle; ResolutionComplete; ObjectIdChanged (290→346); ZoneTransfer (Stack→GY, Resolve) |

### Key Annotations — gsId 52 (ResolutionStart)

```json
{ "id": 219, "types": ["ResolutionStart"], "affectorId": 290, "affectedIds": [290],
  "details": { "grpid": 93928 } }
```

The full library contents become visible as objects (updateType `Send`). The Revealed zone (19) is
still empty at this point.

### Key Annotations — gsId 53 (Land found, reveal step)

```json
{ "id": 221, "types": ["ObjectIdChanged"], "affectorId": 290, "affectedIds": [228],
  "details": { "orig_id": 228, "new_id": 293 } }

{ "id": 222, "types": ["RevealedCardCreated"], "affectorId": 294, "affectedIds": [294] }

{ "id": 224, "types": ["ZoneTransfer"], "affectorId": 290, "affectedIds": [293],
  "details": { "zone_src": 36, "zone_dest": 35, "category": "Put" } }
```

**Persistent annotations in gsId 53:**
```json
{ "id": 14, "types": ["EnteredZoneThisTurn"], "affectorId": 35, "affectedIds": [293] }
{ "id": 223, "types": ["InstanceRevealedToOpponent"], "affectorId": 293, "affectedIds": [293] }
```

**Zone update in gsId 53:**
- Zone 19 (Revealed, owner 2): `objectIds: [294]`
- Hand (35): now includes 293 (the found Forest)
- Library (36): 228 removed

**Objects in gsId 53:**
- `{ instanceId: 292, grpId: 98595, zoneId: 36, type: "RevealedCard", visibility: "Public" }` — ghost copy in library zone
- `{ instanceId: 293, grpId: 98595, zoneId: 35, type: "Card", visibility: "Private" }` — real card now in hand
- `{ instanceId: 294, grpId: 98595, zoneId: 35, type: "RevealedCard", visibility: "Public" }` — revealed proxy in Revealed zone (zoneId listed as 35 but objectIds[294] appears in zone 19)

### Key Annotations — gsId 54 (ResolutionComplete + Shuffle)

```json
{ "id": 225, "types": ["Shuffle"], "affectorId": 290, "affectedIds": [2],
  "details": { "OldIds": [...51 ids...], "NewIds": [...51 new ids...] } }

{ "id": 226, "types": ["ResolutionComplete"], "affectorId": 290, "affectedIds": [290],
  "details": { "grpid": 93928 } }

{ "id": 227, "types": ["ObjectIdChanged"], "affectorId": 2, "affectedIds": [290],
  "details": { "orig_id": 290, "new_id": 346 } }

{ "id": 228, "types": ["ZoneTransfer"], "affectorId": 2, "affectedIds": [346],
  "details": { "zone_src": 27, "zone_dest": 37, "category": "Resolve" } }
```

`diffDeletedInstanceIds` at gsId 54 includes: all 51 old library instanceIds + 292 (the RevealedCard ghost).

The Revealed zone (19) is implicitly cleared: no explicit RevealedCardDeleted annotation is emitted.
Instead, instanceId 292 appears in `diffDeletedInstanceIds`. The RevealedCard proxy (instanceId 294)
disappears from the zone 19 objectIds list without an annotation.

## Inferred Explore Protocol (ETB and Activated)

Based on the Bushwhack analog and Cenote Scout/Seeker card text, explore resolves as:

### Case A: Land revealed (top of library is a land)

1. Creature ETB / ability resolves on stack
2. GSM: `RevealedCardCreated` for top card (land)
   - Revealed zone (18 or 19, owner = creature controller) gains the RevealedCard proxy instanceId
   - RevealedCard proxy object: `type: "RevealedCard"`, `visibility: "Public"`, same grpId
   - `ObjectIdChanged` for original card (library → new instanceId)
   - `ZoneTransfer` (Library → Hand, category `Put`)
   - Persistent: `InstanceRevealedToOpponent` on new hand instanceId
3. GSM: No counter added (land case — no +1/+1)
4. Revealed zone cleared in next diff (no `RevealedCardDeleted`, just removed from zone objectIds + `diffDeletedInstanceIds`)

### Case B: Non-land revealed (+1/+1 counter added)

The card stays on top of library or goes to graveyard depending on player choice (for Seeker's
activated ability the prompt might be required). Expected annotations:

1. `RevealedCardCreated` for top card (non-land)
2. Revealed zone populated
3. Counter: `CounterAdded` (type `P1P1`, transaction_amount 1) on the exploring creature
4. If card goes to graveyard: `ZoneTransfer` (Library → Graveyard, category `Put` or `Destroy`)
5. If card stays on top: no zone transfer (stays in library at same position)
6. Revealed zone cleared in next diff

**Note:** Case B is unconfirmed from this recording. Counter annotations are implemented in leyline
(`counterAdded()`). The non-land path needs a separate recording.

## TransferCategories Required

| Step | Src Zone | Dest Zone | category | Status |
|------|----------|-----------|----------|--------|
| Cast creature | Hand (35) | Stack (27) | `CastSpell` (6) | Wired |
| Resolve creature | Stack (27) | Battlefield (28) | `Resolve` (13) | Wired |
| Land found — hand | Library (32/36) | Hand (31/35) | `Put` (19) | Partial |
| Non-land to GY | Library (32/36) | Graveyard (33/37) | `Put` (19) | Partial |

`Put` (19) is listed as "Partial" in rosetta — it needs a dedicated event, not zone-pair inference.

## InstanceId Lifecycle (Bushwhack analog)

```
228  [Library, hidden]          — original top card (Forest)
 ↓ ObjectIdChanged (orig=228, new=293)
293  [Hand, private]            — card after moving to hand
292  [Library, RevealedCard]    — ghost copy in library (deleted at ResolutionComplete)
294  [Revealed zone 19, RevealedCard] — public reveal proxy (cleared from zone list at ResolutionComplete)
```

For explore, the exploring creature keeps its instanceId (not reallocated). Only the revealed top
card gets ObjectIdChanged + a RevealedCard proxy.

## Gaps vs Our Code

- [ ] **Revealed zone (18/19) not populated.** AnnotationPipeline emits `RevealedCardCreated` but
  does not add the RevealedCard proxy object to zone 18/19 in the GSM. The zone appears empty in
  our output. The recording shows zone 19 objectIds = [294] with a `RevealedCard`-type object.

- [ ] **RevealedCard ghost object not emitted.** Two RevealedCard proxy objects appear in the
  recording: one in the library zone (type `RevealedCard`, same zone as original) and one in the
  Revealed zone. We emit `RevealedCardCreated` annotation but no corresponding proxy game objects.

- [ ] **RevealedCardDeleted not emitted.** `revealedCardDeleted()` method exists in AnnotationBuilder
  but has no callers. The recording does not emit an explicit annotation either — instead, the proxy
  instanceIds appear in `diffDeletedInstanceIds` on the next diff. Our code must add them to
  `diffDeletedInstanceIds`.

- [ ] **InstanceRevealedToOpponent persistent annotation missing.** The recording emits this as a
  persistent annotation on the card that moved to hand. AnnotationBuilder has
  `instanceRevealedToOpponent()` but it has no callers.

- [ ] **CardsRevealed → explore ETB not wired in Forge bridge.** Forge calls
  `PlayerController.reveal()` for search reveals (Bushwhack path works). Explore in Forge is
  implemented via `ExploreEffect` which calls `moveToTopOrGraveyard()` after reveal. Verify
  `WebPlayerController.reveal()` is called by ExploreEffect (not just search effects).

- [ ] **Non-land path (Case B) unconfirmed.** No recording of +1/+1 counter path. Need a new
  session with Cenote Scout or Seeker activated and a non-land top card.

- [ ] **`Put` category for Library→Hand/GY.** Currently `Put` (19) is "Partial" — falls back
  to generic ZoneTransfer for Library→Hand moves that are not Draw. Explore's land-to-hand
  move should use `Put`.

## What IS Working

- `RevealedCardCreated` annotation is emitted when `CardsRevealed` event fires
- `CardsRevealed` event is produced from `drainReveals()` via `WebPlayerController.reveal()`
- Revealed zones (18/19) exist in the zone list (always empty but declared)
- `RevealAnnotationTest` confirms annotation fires in isolation

## Flags for engine-bridge agent

1. Proxy RevealedCard objects need to be synthesized and added to zone list and the Revealed zone.
2. `instanceRevealedToOpponent()` needs to be wired as a persistent annotation whenever a card in
   a hidden zone is revealed (explore land, search result, etc.).
3. Cleanup: proxy instanceIds must appear in `diffDeletedInstanceIds` on the next diff frame.
4. Verify ExploreEffect calls `PlayerController.reveal()` on the Forge side before the +1/+1 path.
