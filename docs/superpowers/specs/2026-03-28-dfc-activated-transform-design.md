# DFC Activated Transform — Phase 1 Design

**Date:** 2026-03-28
**Issue:** #191 (DFC wire), #256 (reveal hand — Phase 2, deferred)
**Card spec:** `docs/card-specs/concealing-curtains.md`
**Approach:** B — explicit `CardTransformed` event + snapshot-compare for grpId mutation

## Summary

Implement in-place DFC transform for activated-ability DFCs (Concealing Curtains → Revealing Eye). The permanent stays on the battlefield with the same instanceId — grpId, P/T, subtypes, abilities all mutate in the Diff. No ZoneTransfer. This is distinct from saga DFCs which use a ZoneTransfer pair (exile front → resolve back).

The on-transform trigger (reveal opponent hand / choose / discard / draw) is **autopassed** — Forge resolves it internally via AI. Phase 2 (#256) adds the interactive reveal wire.

## Architecture

### 1. New GameEvent: `CardTransformed`

**File:** `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt`

```kotlin
data class CardTransformed(
    val cardId: ForgeCardId,
    val isBackSide: Boolean,
) : GameEvent
```

**Detection in `GameEventCollector`:** Track `lastBackSide: Map<ForgeCardId, Boolean>` alongside existing `lastPT`. In `visit(GameEventCardStatsChanged)`, check `card.isBackSide()` against stored value. Emit `CardTransformed` on flip. The existing `PowerToughnessChanged` event still fires independently (0/4 → 3/4).

### 2. othersideGrpId on DFC gameObjects

**File:** `matchdoor/src/main/kotlin/leyline/game/CardProtoBuilder.kt`

Currently `othersideGrpId` (proto field 40) is never set. Real server sends it on all DFC gameObjects — front face carries back-face grpId, back face carries front-face grpId.

**Change:** Add a method or parameter to `buildObjectInfo()` that accepts an optional `othersideGrpId`. `ObjectMapper` detects DFC cards via `card.alternateState != null` (Forge Card API), resolves the alternate face's grpId via `cards.findGrpIdByName(card.getState(alternateStateName).name)`, and passes it through.

This applies to **all DFC objects on any zone**, not just on transform. A front-face Concealing Curtains on battlefield already carries `othersideGrpId = 78896`.

### 3. grpId mutation via snapshot-compare

**No new code needed.** `ObjectMapper.resolveGrpId()` uses `card.name` → when Forge transforms a card, `card.currentState` switches to `Backside`, `card.name` becomes "Revealing Eye", and `resolveGrpId()` returns 78896. The snapshot diff detects the gameObject changed (proto inequality) and includes it in the Diff.

Fields that change automatically via the existing `CardProtoBuilder.buildObjectInfo()` path:
- `grpId` (78895 → 78896)
- `overlayGrpId` (follows grpId)
- `othersideGrpId` (78896 → 78895, once wired)
- `name` / `titleId`
- `cardTypes` / `subtypes` (Wall → Eye Horror)
- `power` / `toughness` (0/4 → 3/4)
- `uniqueAbilities` (gains Menace ability, loses Defender)

### 4. Qualification persistent annotation (keyword badge)

**File:** `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt`

New builder method for Qualification (Arena annotation type 42):
```
qualification(affectorId, instanceId, grpId, qualificationType, qualificationSubtype, sourceParent)
```

From the trace: `affectorId=287, affectedIds=[287], grpId=142, QualificationType=40, QualificationSubtype=0, SourceParent=287`. This is the Menace keyword grant on the back face.

**Keyword → Qualification mapping table** (Phase 1 — common combat keywords):

| Keyword | grpId | QualificationType |
|---------|-------|-------------------|
| Menace | 142 | 40 |

Other keywords (Flying, Trample, etc.) will be added as DFC cards exercise them. The table lives in a companion object or small lookup, not a full registry.

**Emission:** In the annotation pipeline, when a `CardTransformed` event is present, inspect the back-face card data for keywords and emit Qualification pAnns for each. This is a persistent annotation — stays until the card transforms back or leaves the battlefield.

### 5. TriggeringObject persistent annotation

**File:** `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt`

New builder for TriggeringObject:
```
triggeringObject(affectorId = triggerAbilityInstanceId, affectedIds = [sourceInstanceId], sourceZone)
```

From the trace: `affectorId=321, affectedIds=[287], source_zone=28`. Links the on-transform trigger ability to the card that transformed.

**Emission:** When a trigger ability hits the stack and has a `TriggeringObject` relationship, emit this pAnn. For Phase 1, this fires when the back-face trigger (grpId 146663) is created by Forge's SetState resolution chain.

### 6. Trigger autopass

The on-transform trigger (reveal → choose → discard → draw) resolves via Forge AI. Leyline observes:
- Zone transfers (opponent hand → graveyard, library → hand) — captured by snapshot diff, emitted as normal ZoneTransfer annotations
- Reveal via `WebPlayerController.reveal()` → `drainReveals()` → `RevealedCardCreated` annotations (already wired)

No new code. The game state is correct; the client just doesn't get the interactive reveal/choose UI. Phase 2 (#256) adds that.

**Risk:** If Forge calls `chooseSingleCard()` on the human player's controller during trigger resolution, `InteractivePromptBridge` will block waiting for a response. Mitigation: verify in the puzzle that the AI opponent's trigger resolves without human interaction. If it blocks, the bridge timeout diagnostic will identify the call site.

### 7. Puzzle

**File:** `puzzles/concealing-curtains-transform.pzl`

- **Board:** Concealing Curtains (78895) on battlefield controlled by player, 3+ Swamps untapped, opponent has 1+ hand cards
- **Action:** Activate {2}{B} transform ability at sorcery speed (Main Phase 1)
- **Verify:** grpId mutation (78895 → 78896) in Diff, othersideGrpId swap, Qualification pAnn for Menace, P/T 0/4 → 3/4, no ZoneTransfer for the transform itself
- **Bonus:** Attack with 3/4 Menace creature to verify combat works post-transform

### 8. Catalog update

**File:** `docs/catalog.yaml`

Add under gameplay mechanics:
```yaml
dfc-activated-transform:
  status: missing  # → wired after implementation
  notes: >
    Activated-ability DFC transform (Concealing Curtains, Delver of Secrets, etc.).
    In-place grpId mutation — same instanceId, no ZoneTransfer. Distinct from
    saga DFC which uses ZoneTransfer pair (exile front → resolve back, #191).
    othersideGrpId links front↔back face grpIds on the gameObject.
    Qualification pAnn emits keyword badges on back face (Menace, Flying, etc.).
```

## Out of scope (Phase 2 — #256)

- RevealedCard proxy objects in zone 19
- Hand zone visibility flip
- SelectNReq for "choose from revealed"
- CardRevealed annotation (type 47)
- InstanceRevealedToOpponent annotation (type 75)
- RevealedCard cleanup

## Test plan

1. **Unit:** `GameEventCollectorTest` — `CardTransformed` emitted on `isBackSide()` flip, not on P/T-only changes
2. **Unit:** `AnnotationBuilderTest` — Qualification and TriggeringObject proto shapes match trace
3. **Unit:** `CardProtoBuilderTest` — `othersideGrpId` set for DFC cards, absent for non-DFC
4. **Puzzle:** `concealing-curtains-transform.pzl` in MatchHarness — grpId mutation, annotations, no crash
5. **Integration:** Arena playtest — transform visually works, Menace badge shows, combat functional
