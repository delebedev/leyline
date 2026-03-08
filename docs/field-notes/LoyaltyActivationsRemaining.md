## LoyaltyActivationsRemaining — field note

**Status:** NOT IMPLEMENTED
**Instances:** 7 across 2 sessions
**Proto type:** AnnotationType.LoyaltyActivationsRemaining (type 97)
**Field:** persistentAnnotations

### What it means in gameplay

Tracks the remaining number of loyalty ability activations a planeswalker has available this turn. Drives the enable/disable state of the loyalty ability buttons on the planeswalker card UI. When the annotation exists with `ActivationsRemaining=1`, ability buttons are enabled. When deleted (after activation), all buttons grey out.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| ActivationsRemaining | Always | 1 | Always 1 in all observed instances — one activation allowed per turn per PW |

`ActivationsRemaining` has never been observed with a value other than 1. This is consistent with the standard MTG rule: each planeswalker may only activate one loyalty ability per turn. Values > 1 might exist for cards granting extra activations, but none have been observed yet.

### Cards observed

| Card name | grpId | Scenario | Session |
|-----------|-------|----------|---------|
| Liliana of the Veil | 82149 | Enters BF T8 (gsId=143); +1 activated T9 (gsId=186); annotation refreshes each beginning of turn | 2026-03-01_11-33-28 |
| Elspeth, Storm Slayer | 95526 | Enters BF T8 (gsId=136); annotation refreshes at T9 Beginning (gsId=153) and T11 Beginning (gsId=202) | 2026-03-06_22-37-41 |

Note: variance tool reported grp:93908 (Fanatical Firebrand) for the Elspeth session — this is a tool grpId resolution artifact. Raw frame data at gsId=136 confirms instanceId 300 = grp:95526 (Elspeth, Storm Slayer, Legendary Planeswalker — Elspeth subtype).

### Lifecycle

**Creation:** annotation appears in the same GSM as the PW entering the battlefield — together with `CounterAdded` (loyalty counter, e.g., amount=3) and the persistent `Counter` (count=3, type=7) annotation.

**Deletion:** deleted via `diffDeletedPersistentAnnotationIds` in the GSM where the PW activation occurs. The activation itself (and resulting effect) appears in the same GSM. The deletion fires before or alongside the ability effect.

**Refresh:** recreated at the beginning of each new turn (Beginning phase GSM), in the same GSM as `NewTurnStarted`. The annotation ID changes on recreation (not updated in-place — deleted and re-added).

**Turn it enters vs activation on same turn:** Liliana entered on T8 (gsId=143) — annotation created. On T8, the -2 was activated (gsId=144) — annotation deleted. On T9 (gsId=161), annotation recreated. This confirms the annotation is not gated by "summoning sickness equivalent" for PWs — it's created as soon as the PW enters, same turn.

**Both seat copies:** each GSM is sent to both seats (the 7 instances = 4 Liliana + 3 Elspeth, with Liliana having two per-turn copies for the two re-prompt GSMs). The annotation appears in both seats' views.

### Related annotations

- `Counter` (persistent) — loyalty counter annotation; appears alongside LoyaltyActivationsRemaining when PW enters BF
- `CounterAdded` (transient) — fires when loyalty changes (entry, activation, damage)
- `AbilityInstanceCreated` — fires when a loyalty ability goes on the stack

### Our code status

- Builder: missing
- GameEvent: missing
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Easy

Clear, simple lifecycle with three hooks:

1. **PW enters battlefield** → emit `LoyaltyActivationsRemaining(ActivationsRemaining=1)` on the PW instanceId
2. **PW loyalty ability activated** → delete the annotation (via `diffDeletedPersistentAnnotationIds`)
3. **New turn begins** → re-emit `LoyaltyActivationsRemaining(1)` for each PW on battlefield

All three events are detectable in Forge:
- BF entry: `GameEventZoneTransfer` or `GameEventCardStatsChanged` when a PW lands in Zone.Battlefield
- Activation: PW ability activation events (already wired for loyalty counter changes)
- Turn start: `GameEventTurnBegin` or existing turn-phase handling

No complex mapping needed. The annotation ID should be stable per PW instance (same ID for the lifecycle within one turn). On deletion + recreation at turn start, the ID increments (new annotation).

### Open questions

- Can `ActivationsRemaining` ever be > 1? Not observed; would require a card that grants extra PW activations (e.g., Doubling Season doesn't affect activations). Probably safe to hardcode 1 for now.
- Does the annotation appear on PWs that are controlled by the opponent (from the controller's perspective)? The sessions only show the controlling player's PWs. Opponent PWs would presumably also have this annotation from the opponent's seat view — not yet confirmed.
- Does deletion happen strictly before the ability resolves (so the button goes grey instantly on click), or can there be a GSM gap?
