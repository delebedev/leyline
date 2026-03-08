## CardRevealed — field note

**Status:** NOT IMPLEMENTED
**Instances:** 1 across 1 session
**Proto type:** `AnnotationType.CardRevealed` (enum 47)
**Field:** `persistentAnnotations`

### What it means in gameplay

A specific card in a hidden/private zone has been publicly revealed to all players as part of an effect (tutor, impulse draw, "reveal the top N" effects). The annotation persists while the revealed state is visible, marking the library shadow copy of the revealed card.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| `source_zone` | Always | `31` | Zone ID. Observed value = 31 (Hand, owner 1). Meaning is ambiguous — see Open Questions. |

### Cards observed

| Card name | grpId | Scenario | Session |
|-----------|-------|----------|---------|
| Badgermole Cub | 97444 | Searched from library via Formidable Speaker ETB trigger, revealed to all players before being put into hand | `2026-03-06_22-37-41` |

### Scenario reconstruction

Formidable Speaker (grpId=98497, iid=310) enters battlefield at gsId=132. Its ETB trigger (ability 194004: "you may discard a card; if you do, search your library for a creature card, reveal it, put it into your hand, then shuffle") creates ability instance 314 on stack.

- **gsId=135:** Player discards a card (iid=165→315, Hand→Graveyard). `ObjectsSelected` fires marking choice locked in.
- **gsId=136:** Library search completes.
  - Badgermole Cub iid=214 gets new id 317 (ObjectIdChanged) and moves Library(32)→Hand(31) via ZoneTransfer category=Put.
  - `RevealedCard` iid=318 created in Hand zone (31) — the public-facing reveal overlay on the card being put in hand.
  - `RevealedCard` iid=316 stays in Library zone (32) — the shadow copy remaining in library display.
  - **Persistent `CardRevealed`**: `affectorId=314` (ability), `affectedIds=[316]` (library shadow copy), `source_zone=31`.

The annotation persists marking instance 316 (library RevealedCard) as a revealed card. The parallel `RevealedCardCreated` (transient, type 59) fires for instance 318 (the hand copy).

### Distinction from RevealedCardCreated

Two related annotation types participate in a reveal:

| Type | Kind | Affected | Purpose |
|------|------|----------|---------|
| `CardRevealed` (47) | Persistent | Library shadow copy (iid=316, zone=Library) | Marks a hidden-zone card as currently revealed; persists until un-revealed |
| `RevealedCardCreated` (59) | Transient | Public overlay copy (iid=318, zone=Hand/BF) | Event fired when the visual reveal overlay object is created |

Our code already handles `RevealedCardCreated` (via `GameEvent.CardsRevealed` → `AnnotationBuilder.revealedCardCreated`). `CardRevealed` persistent is the missing counterpart.

The `docs/catalog.yaml` entry for `revealed:` notes: "CardRevealed annotation (47) missing."

### Lifecycle

Persistent annotation. Created when the card becomes publicly revealed (gsId=136). Cleared when the reveal ends — presumably in the same GSM as `RevealedCardDeleted` (type 60) or when the ability fully resolves and the search/shuffle concludes.

This session ends with the annotation still active (the card is moving into hand so the reveal is transient — likely cleared in the next GSM). Not enough data to confirm the exact deletion trigger.

### Related annotations

- `RevealedCardCreated` (59) — transient, fires simultaneously for the public overlay copy
- `RevealedCardDeleted` (60) — expected counterpart for cleanup; not observed in this instance
- `ObjectsSelected` — fires for the discard choice in the same scenario
- `ZoneTransfer` (category=Put) — the card entering hand

### Our code status

- Builder: missing — no `CardRevealed` (persistent annotation 47) in `AnnotationBuilder.kt`
- GameEvent: `GameEvent.CardsRevealed` exists (wires `RevealedCardCreated`), but does not emit `CardRevealed` persistent
- Emitted in pipeline: no — `AnnotationPipeline.kt` L362-368 handles `CardsRevealed` but only calls `revealedCardCreated`; no persistent `CardRevealed` emission

### Wiring assessment

**Difficulty:** Medium

The `GameEvent.CardsRevealed` event already fires. The missing piece is emitting the `CardRevealed` persistent annotation alongside `RevealedCardCreated`.

The main challenge: the `source_zone` value. Its semantics are ambiguous (see Open Questions). If it always equals the destination zone (hand), it may be derivable from context. If it's the zone of the overlay copy, that requires knowing the specific RevealedCard instanceId.

The Forge model gap: `InteractivePromptBridge.drainReveals()` (noted in catalog) captures library reveals. The bridge needs to emit both the transient `RevealedCardCreated` and the persistent `CardRevealed` for each revealed card.

### Open questions

1. **`source_zone` semantics:** In this instance, `source_zone=31` (Hand, owner 1) but the revealed card was searched from Library (32) and placed into Hand (31). Three possible interpretations:
   - `source_zone` = destination zone (where the card is going) — value 31 = Hand fits
   - `source_zone` = zone of the RevealedCard overlay (iid=318 is in zone 31)
   - `source_zone` = zone from which the *revealing effect* originated (Formidable Speaker is in BF=28, not 31 — rules this out)
   Need more recordings with reveals from different zones (e.g., reveal top of library to zone 32) to disambiguate.

2. **Zone 18 (Revealed zone):** The frame shows a `Revealed` zone (zoneId=18, owner=1) containing iid=318. If reveals to the public Revealed zone use `source_zone=18` or a different value, that would clarify the semantics.
