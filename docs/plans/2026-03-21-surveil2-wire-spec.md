# Surveil 2 Wire Spec — 2026-03-21

Card under analysis: **Rubblebelt Maverick** (grpId=89088, "When this enters, surveil 2.")

## Recording status

Recording `2026-03-21_22-05-00` **does not contain a surveil 2 trigger.** instanceId=356 (grpId=89088, drawn as seat 2's turn-5 draw) stayed in hand through turn 6. The game ended without the card being cast.

Confirmed: zero `GroupReq`, `GroupResp`, or `ZoneTransfer{category:"Surveil"}` frames in `md-frames.jsonl`. Searched explicitly for `greType:"GroupReq"`, `greType:"GroupResp"`, and `"Surveil"` string — all empty.

**A new recording is needed to observe surveil 2 live wire behavior.** This document captures protocol structure from code analysis + existing surveil-1 knowledge from the 2026-03-08 retro.

---

## Surveil N>1 protocol: expected behavior (from code)

### GroupReq shape

`GsmBuilder.buildSurveilScryGroupReq` is called with all N card instanceIds:

```
GroupReq {
  instanceIds: [iid_card_1, iid_card_2]   // all N cards
  groupSpecs: [
    GroupSpecification {
      lowerBound: 0
      upperBound: N                          // max = total card count
      zoneType: Library
      subZoneType: Top
    },
    GroupSpecification {
      lowerBound: 0
      upperBound: N
      zoneType: Graveyard
      subZoneType: None
    }
  ]
  groupType: Ordered
  context: Surveil
  sourceId: <triggering ability instanceId>
}
```

Both group specs share `upperBound=N` (not 1). This correctly allows "put both on top", "put both to graveyard", or any split.

### Reveal diff before GroupReq

Before the GroupReq, a GSM diff reveals all N cards:
- Each card object is sent with `visibility=Private, viewers=[seatId]`
- This makes them face-up in the client's surveil modal
- Same pattern as surveil 1 — just N objects instead of 1

### GroupResp handling (multi-card path)

`TargetingHandler.onGroupResp` dispatches to the multi-card branch when `req.max > 1 || req.options.size > 2`:

```kotlin
val awayIds = if (groups.size >= 2) groups[1].idsList else emptyList()
awayIds.mapNotNull { iid ->
    val forgeCardId = bridge.getForgeCardId(InstanceId(iid)) ?: return@mapNotNull null
    val player = bridge.getPlayer(SeatId(ops.seatId)) ?: return@mapNotNull null
    val card = player.allCards.firstOrNull { it.id == forgeCardId.value }   // BUG
    card?.let { req.options.indexOf(it.name) }
}.filter { it >= 0 }
```

**This branch has the `player.allCards` bug** documented in reflections (2026-03-08). Cards removed from zones mid-surveil (while engine blocks in `requestChoice`) are not in any zone — invisible to `player.allCards`. Must use `Game.findById()`.

### ZoneTransfer annotations per card

Each surveiled card gets its own transfer independently — no batching:
- One `ObjectIdChanged` per card (origId → newId)
- One `ZoneTransfer` per card with `category:"Surveil"` and `affectorId=<ability instanceId>`

Category is `Surveil` not `Mill` — `CardSurveiled` event drives `AnnotationBuilder.categoryFromEvents()` which returns `TransferCategory.Surveil`.

### Ordering prompt (surveil N>1, both stay on top)

If the player puts ≥2 cards on top, `arrangeTopNCards` issues a **third prompt** to order them:

```kotlin
if (toTop.size > 1) {
    val orderReq = PromptRequest(
        promptType = "order",
        message = "Order cards for top of library (first = top)",
        options = topLabels,
        min = toTop.size,
        max = toTop.size,
        defaultIndex = 0,
    )
    val ordering = bridge.requestChoice(orderReq)
    // ... builds ordered CardCollection
}
```

This is a second GroupResp from the client (or an additional interaction). **The real server likely handles ordering within the same GroupResp** using the ordered nature of `group[0].idsList`. We have no recording to confirm, but this is a likely source of variance.

---

## Comparison with surveil 1

| Aspect | Surveil 1 | Surveil 2 |
|---|---|---|
| GroupReq instanceIds count | 1 | 2 |
| groupSpecs upperBound | 1 | 2 |
| GroupResp path | single-card (max==1) | multi-card (max>1) |
| Reveal diff objects | 1 | 2 |
| ZoneTransfer annotations | 1 | 1 per card that moves |
| ObjectIdChanged annotations | 1 (if moved) | 1 per card that moves |
| Ordering prompt | never needed | if ≥2 stay on top |

---

## Known bugs affecting surveil N>1

### Bug 1: `player.allCards` in multi-card GroupResp (CRITICAL)

**Location:** `TargetingHandler.onGroupResp` line ~308

```kotlin
val card = player.allCards.firstOrNull { it.id == forgeCardId.value }
```

Cards are in a zoneless limbo state while the engine blocks. `player.allCards` visits zones only — these cards are invisible. Result: `card` is null for every ID, `awayIds.mapNotNull` returns empty, all cards are treated as "keep on top" regardless of user choice.

**Fix:** use `game.findById(forgeCardId.value)` instead. This is already documented in reflections.md and the matchdoor/CLAUDE.md cookbook.

### Bug 2: ordering via extra prompt vs GroupResp ordering

When ≥2 cards go to library top, we issue a separate `promptType="order"` request. It's unknown if the real server uses this pattern or embeds ordering in group 0's ID list order. No recording evidence yet.

### Bug 3: premature GroupResp (pre-existing, from surveil 1)

Also affects surveil 2. Client sends two GroupResps — we consume the first (default/premature). Tracked in surveil-status.md. Root cause still open.

---

## ZoneTransfer categories — confirmed correct

- Surveil → library top: ZoneTransfer with `category:"Surveil"` (library stays hidden, no visible transfer unless client counts library size)
- Surveil → graveyard: `ObjectIdChanged` (origId → newId) + `ZoneTransfer{zone_src:library, zone_dest:graveyard, category:"Surveil"}`
- NOT `Mill` — the `CardSurveiled` per-card event is specifically to distinguish surveil from mill (both are library→GY moves)

---

## ObjectIdChanged — one per card, not batched

Based on the `annotationsForTransfer` loop in `AnnotationPipeline`: one `AppliedTransfer` per card, one annotation call per transfer. No batching. Confirmed by surveil-1 real server recording (2026-03-08 retro).

---

## Action required

1. **Get a surveil 2 recording.** Build a puzzle or wait for a live game with Rubblebelt Maverick to actually fire. Target: capture all three GroupResp scenarios (both top / both GY / split).

2. **Fix `player.allCards` bug.** `TargetingHandler.onGroupResp` line ~308: swap `player.allCards.firstOrNull { it.id == forgeCardId.value }` for `game.findById(forgeCardId.value)`. This is the highest-priority code fix — surveil 2 is fully broken without it.

3. **Verify ordering behavior.** Once a surveil-2-keep-both recording exists, check if real server sends ordering in group[0].idsList ordering or via a separate prompt. Our current approach (extra prompt) may diverge.

4. **Flag premature GroupResp.** Still open from surveil 1 work. Surveil 2 multiplies the symptom (two resps for the initial choice, potentially a third for ordering).

---

## Files changed by surveil work (reference)

- `matchdoor/…/bridge/WebPlayerController.kt` — `arrangeForSurveil`, `arrangeTopNCards`
- `matchdoor/…/game/GsmBuilder.kt` — `buildSurveilScryGroupReq`
- `matchdoor/…/match/TargetingHandler.kt` — `sendGroupReqForSurveilScry`, `onGroupResp`
- `matchdoor/…/game/AnnotationPipeline.kt` — `annotationsForTransfer` (Surveil branch), `mechanicAnnotations` (Surveil event)
- `matchdoor/…/game/TransferCategory.kt` — `Surveil` variant
- `matchdoor/…/game/GameEvent.kt` — `CardSurveiled`, `Surveil`
- `matchdoor/…/game/GameEventCollector.kt` — `visit(GameEventCardSurveiled)`
