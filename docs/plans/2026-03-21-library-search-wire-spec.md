# Library Search Wire Spec

Recording: `recordings/2026-03-21_22-05-00/`
Card: Bushwhack (grpId 93928) — modal sorcery, search mode played turn 3

---

## 1. Bushwhack instanceId lifecycle

| Phase | instanceId | Zone | Notes |
|-------|-----------|------|-------|
| Draw (gsId 45) | 288 | Hand (35) | Drew from library (ObjectIdChanged 226→288, ZoneTransfer Draw) |
| Cast (gsId 49) | 290 | Stack (27) | ObjectIdChanged 288→290, ZoneTransfer Hand→Stack category=CastSpell |
| Modal selection | 290 | Stack | CastingTimeOptionsReq sent (same gsId 49) |
| Mana payment (gsId 50) | 290 | Stack | Forest 281 tapped, ManaPaid color=5 |
| ResolutionStart (gsId 52) | 290 | Stack | ResolutionStart annotation on 290 |
| SearchReq (gsId 52) | — | — | Server requests search, same gsId |
| SearchResp (gsId 52) | — | — | Client responds, no itemsFound in decoded frame |
| Result (gsId 53) | 293 | Hand (35) | ObjectIdChanged 228→293, ZoneTransfer Library→Hand category=Put |
| Shuffle (gsId 54) | 346 | Graveyard (37) | OldIds/NewIds lists, ResolutionComplete, then Bushwhack→GY |

---

## 2. Modal spell selection — CastingTimeOptionsReq

Immediately after the Cast→Stack GSM (gsId 49), the server sends a separate `CastingTimeOptionsReq` in the same gsId:

```json
{
  "greType": "CastingTimeOptionsReq",
  "msgId": 72,
  "gsId": 49,
  "castingTimeOptions": [{
    "ctoId": 2,
    "type": "Modal",
    "affectedId": 290,
    "affectorId": 290,
    "grpId": 93928,
    "isRequired": true,
    "modal": {
      "abilityGrpId": 153364,
      "minSel": 1,
      "maxSel": 1,
      "options": [
        {"grpId": 27767},
        {"grpId": 99356}
      ]
    }
  }],
  "promptId": 23,
  "systemSeatIds": [2]
}
```

- `type=Modal`, `isRequired=true` — client must choose before cost payment proceeds
- `minSel=1, maxSel=1` — choose exactly one mode
- `options[0].grpId=27767` — fight mode
- `options[1].grpId=99356` — search mode (the one selected this game)
- `affectedId=affectorId=290` — the stack instanceId of Bushwhack
- `abilityGrpId=153364` — the "choose one" ability

Client response (`CastingTimeOptionsResp`, gsId 49) carries no payload in the decoded frame — the mode selection is implicit from the client's UI action. Mana payment proceeds immediately after, indicating the resp was accepted without the server needing the selected option grpId echoed back.

**This is the same CastingTimeOptionsReq mechanism already implemented for modal-etb (PR #84).** No new GRE message type needed. The `type=Modal` + `isRequired=true` path is already wired.

---

## 3. The search sequence — SearchReq / SearchResp

### 3a. ResolutionStart → SearchReq ordering

After the Forge engine begins resolving Bushwhack, the server sends (all at gsId 52):

1. **GameStateMessage** (msgId 75): `ResolutionStart` annotation on instance 290. Library contents revealed as private objects (51 cards with instanceIds 227–278).
2. **SearchReq** (msgId 76): dedicated GRE message type 44. Full frame:

```json
{
  "greType": "SearchReq",
  "msgId": 76,
  "gsId": 52,
  "promptId": 1065,
  "systemSeatIds": [2]
}
```

The decoded `SearchReq` proto shows **no payload fields** — `minFind`, `maxFind`, `zonesToSearch`, `itemsToSearch`, `itemsSought`, `sourceId` are all absent (zero/empty). The arena client apparently opens the library search UI purely on receiving `SearchReq` and presents the player's own library contents (which were just sent as the library objectIds in the preceding GSM).

3. **SearchResp** (C→S, gsId 52): also no payload fields in decoded frame:

```json
{
  "greType": "SearchResp",
  "gsId": 52,
  "clientType": "SearchResp"
}
```

`itemsFound` is empty — the selected card is communicated implicitly by the zone state change in the next GSM, not by the SearchResp.

**Key finding:** SearchReq/SearchResp are pure handshake messages for basic-land-to-hand searches. The actual choice is conveyed by engine state (the Forge engine already knows which card to fetch via `chooseSingleCard`/`fetchFromLibrary`). The protocol does not require the client to echo back a card instanceId — it just signals "search UI opened / player confirmed."

### 3b. TimerStateMessage between SearchReq and result GSM

After SearchResp (index 106) and before the result GSM (index 108), a `TimerStateMessage` (msgId 77, gsId 52) fires. This is the timer being cleared (player made their choice). Standard pattern.

---

## 4. Land-to-hand transfer annotations (gsId 53)

```json
{
  "annotations": [
    {
      "id": 221,
      "types": ["ObjectIdChanged"],
      "affectorId": 290,
      "affectedIds": [228],
      "details": {"orig_id": 228, "new_id": 293}
    },
    {
      "id": 222,
      "types": ["RevealedCardCreated"],
      "affectorId": 294,
      "affectedIds": [294]
    },
    {
      "id": 224,
      "types": ["ZoneTransfer"],
      "affectorId": 290,
      "affectedIds": [293],
      "details": {"zone_src": 36, "zone_dest": 35, "category": "Put"}
    }
  ]
}
```

### Transfer category: Put (19), not Search (14) or Draw (10)

The zone transfer for Library (36) → Hand (35) uses **category=Put**, not Search or Draw. This is significant:

- `Search` (14) in the catalog/rosetta refers to Library → **Battlefield** (e.g. fetch lands, Rampant Growth). Wired via zone-pair heuristic `Library→BF`.
- `Draw` (10) is Library → Hand via draw step.
- For tutors/search-to-hand: **Put (19)** is the correct category.

The current `AnnotationBuilder` zone-pair heuristic in `AnnotationPipeline.kt:596` maps `Library→Battlefield` to `Search`. Library→Hand is currently mapped to `Draw` (line 328). This would be **wrong** for search-to-hand — it needs `Put` to match the wire.

### RevealedCard objects

Two objects appear at gsId 53:
- `instanceId=292, type=RevealedCard, zoneId=36` (library position — a shadow copy)
- `instanceId=293, type=Card, zoneId=35` (actual card now in hand)
- `instanceId=294, type=RevealedCard, zoneId=35` (the public reveal proxy)

The `RevealedCard` at zoneId=35 is the "revealed to all" public object. Zone 19 (`Revealed`, owner=2) contains `[294]`. The real card instance 293 moves to hand.

### Persistent annotations at gsId 53

```json
{
  "id": 14,
  "types": ["EnteredZoneThisTurn"],
  "affectorId": 35,
  "affectedIds": [293]
},
{
  "id": 223,
  "types": ["InstanceRevealedToOpponent"],
  "affectorId": 293,
  "affectedIds": [293]
}
```

`InstanceRevealedToOpponent` (type 75) is a **persistent annotation** that marks a hand card as "opponent can see it." It fires here because Bushwhack's search mode says "reveal it." This is currently MISSING in leyline (rosetta type 75). Without it, the opponent client won't know to display the card face-up in the opponent's hand.

### ObjectIdChanged affectorId

Annotation 221 (`ObjectIdChanged`) has `affectorId=290` (Bushwhack on stack) — the spell is the cause of the reallocation. `affectedIds=[228]` is the old instanceId. This is the standard ObjectIdChanged pattern but with explicit affectorId pointing to the resolving spell.

---

## 5. Shuffle annotation (gsId 54) — full JSON

```json
{
  "id": 225,
  "types": ["Shuffle"],
  "affectorId": 290,
  "affectedIds": [2],
  "details": {
    "OldIds": [227,229,230,231,232,233,234,235,236,237,238,239,240,241,242,243,244,245,246,247,248,249,250,251,252,253,254,255,256,257,258,259,260,261,262,263,264,265,266,267,268,269,270,271,272,273,274,275,276,277,278],
    "NewIds": [295,296,297,298,299,300,301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318,319,320,321,322,323,324,325,326,327,328,329,330,331,332,333,334,335,336,337,338,339,340,341,342,343,344,345]
  }
}
```

- `affectorId=290` — Bushwhack (the cause)
- `affectedIds=[2]` — seatId 2 (player whose library shuffled)
- `OldIds`: 51 instanceIds before shuffle (note: 228 already removed — searched card is gone from library)
- `NewIds`: 51 new instanceIds (all cards reallocated on shuffle — complete identity reassignment)

`OldIds` count = 51, `NewIds` count = 51. Confirms the searched card (228→293) was removed before shuffle. The shuffle reallocates every remaining library card with fresh instanceIds.

**Current catalog status:** `visual-fidelity.shuffle-animation` is `suppressed` — shuffle annotations were suppressed because we didn't have OldIds/NewIds. The wire shows we DO need to provide them. The engine does fire `GameEventShuffle` → `LibraryShuffled`. The missing piece is capturing the pre/post library instanceId lists at shuffle time and emitting them in the `Shuffle` annotation.

This happens in gsId 54 alongside `ResolutionComplete` and the Bushwhack→GY transfer.

---

## 6. Resolution complete (gsId 54)

```json
{"id": 226, "types": ["ResolutionComplete"], "affectorId": 290, "affectedIds": [290], "details": {"grpid": 93928}},
{"id": 227, "types": ["ObjectIdChanged"], "affectorId": 2, "affectedIds": [290], "details": {"orig_id": 290, "new_id": 346}},
{"id": 228, "types": ["ZoneTransfer"], "affectorId": 2, "affectedIds": [346], "details": {"zone_src": 27, "zone_dest": 37, "category": "Resolve"}}
```

Standard pattern: ResolutionComplete, then Bushwhack moves Stack→GY with category=Resolve. The `affectorId=2` on ObjectIdChanged and ZoneTransfer is seatId (controller), same as other spell resolutions.

---

## 7. Code comparison — what we have vs what's needed

### What exists

```
grep results in matchdoor/src/main/kotlin/leyline/:
- TransferCategory.Search("Search") — wired for Library→Battlefield
- AnnotationPipeline: Library→BF → Search (correct for Rampant Growth type)
- AnnotationPipeline: Library→Hand → Draw (WRONG for search-to-hand)
- No SearchReq / SearchResp message types in MatchHandler or MatchSession
- GameEventShuffle → LibraryShuffled → shuffle() annotation (emits without OldIds/NewIds)
```

### Gaps

1. **SearchReq not sent.** No code in MatchSession dispatches a `SearchReq` to the client during spell resolution. The engine calls `chooseSingleCard` (or similar) on `WebPlayerController` but leyline doesn't intercept that to send SearchReq and await SearchResp before completing the choice.

2. **SearchResp not handled.** No handler in MatchHandler for `SearchResp` client message type (proto client type 44).

3. **Transfer category for Library→Hand search is wrong.** The zone-pair heuristic maps Library→Hand to `Draw` (category 10). For search-to-hand it should be `Put` (category 19). Needs a separate event type or flag to distinguish "searched to hand" from "drew a card".

4. **RevealedCardCreated not fired for the search reveal.** leyline has `revealedCardCreated()` wired for some paths but the search-reveal path (where a RevealedCard proxy appears alongside the real card in hand) needs explicit handling.

5. **InstanceRevealedToOpponent (type 75) not implemented.** Persistent annotation marking the found card as revealed to opponent. Currently MISSING. Without it, opponent doesn't see the searched card.

6. **Shuffle OldIds/NewIds not populated.** The `shuffle()` annotation builder emits the Shuffle annotation but without the `OldIds`/`NewIds` detail keys. The wire requires a complete pre/post instanceId list. This was deliberately suppressed (catalog note) — but the data is needed.

7. **RevealedCard objects and zone 19.** The search reveal creates a `RevealedCard` object (type 8) in zone 19 (Revealed). leyline needs to emit this object + the zone-19 update in the GSM diff for the opponent to see the reveal animation.

---

## 8. Mechanic status assessment

### library-search (new entry needed in catalog.yaml)

**Current effective status: missing**

The engine (`WebPlayerController`) handles `chooseSingleCard` calls from Forge internally and the card does move from library to hand. However:
- Client receives no `SearchReq` so no search UI opens
- Client receives no `SearchResp` path — if the engine blocks waiting for player input, it would stall
- Transfer category for the zone transfer will be `Draw` not `Put`
- No reveal animation (RevealedCard objects, InstanceRevealedToOpponent)
- Shuffle fires but without OldIds/NewIds (suppressed)

For basic lands in single-player vs AI (Forge auto-selects): the card may move silently with a wrong animation. For human player searching: the client would have no UI to pick the card — the game would stall.

### modal-spell

**Status: wired (existing)** — CastingTimeOptionsReq with `type=Modal` + `isRequired=true` is the mechanism, already implemented per PR #84.

### shuffle-animation

**Status: suppressed** — now confirmed we need OldIds/NewIds. The data must come from pre/post library state snapshots around the `GameEventShuffle` event.

---

## 9. Implementation notes for engine-bridge agent

Flag these gaps:

### A. SearchReq/SearchResp handshake

When `WebPlayerController.chooseSingleCard()` is called during spell resolution (library search):
- Send `SearchReq` (GREMessageType 44) to the human player's client before blocking
- Await `SearchResp` from client (GREMessageType 44 client-side)
- The Forge engine already knows which card to fetch (AI choice or deterministic for basic lands) — SearchReq/Resp is a pure UI handshake, not a choice protocol

The proto `SearchReq` has fields for `zonesToSearch`, `itemsToSearch`, etc. — check if the real server populates these or leaves them empty (wire shows empty for basic-land search).

### B. Library→Hand transfer category

In `AnnotationPipeline.kt` (line 328 area), Library→Hand is mapped to `Draw`. Add a flag/event type `CardSearchedToHand` that overrides this to `Put`. The trigger is a search effect resolving, not a draw effect.

### C. OldIds/NewIds for Shuffle

In `GameEventCollector`, when `GameEventShuffle` fires, capture the library instanceId list from `bridge.seat(seatId).library.instanceIds` before the engine shuffles (OldIds) and after (NewIds). Pass both lists through `LibraryShuffled` event → `shuffle()` annotation builder.

### D. InstanceRevealedToOpponent (type 75)

Persistent annotation on the searched card (instanceId in hand). Should fire when a search-to-hand resolves where the card is revealed. This marks the hand card as visible to the opponent for the duration of reveal.

### E. RevealedCard proxy object + zone 19

The server creates a `RevealedCard` (type 8) object at the same position (zoneId=35 hand or zoneId=36 library) to represent the public reveal. Zone 19 (`Revealed`, per-player) gets updated to include this proxy's instanceId. `RevealedCardCreated` annotation fires on the proxy.

---

## 10. Related catalog entries to update

- `zones.library` — add note: draw works but search-to-hand not implemented
- `visual-fidelity.shuffle-animation` — remains suppressed; note that OldIds/NewIds are required and available from pre/post library snapshots
- Add new entry `spells.cast-modal` or note under `spells.cast-sorcery` — modal sorcery CastingTimeOptionsReq path is wired (confirmed same mechanism as modal-etb)
- Add new entry `mechanics.library-search` — status: missing, with this doc as reference
