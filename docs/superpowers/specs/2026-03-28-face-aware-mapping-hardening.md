# Face-Aware Mapping Hardening

Arena-Forge state/card mapping layer hardening. Adds face-awareness to CardData, formalizes ability slot layout, improves transform detection, and generalizes annotation emission for multi-face cards.

**Issues:** #262 (CardData), #263 (auto-register), #264 (AbilityRegistry invalidation), #265 (transform detection), #266 (non-realloc categories), #267 (Qualification generalization), #268 (SlotLayout contract)

**Deferred:** #269 (CardIdentity wrapper type) — builds on this work, not needed yet.

## Motivation

The DFC PR (Concealing Curtains, #261) required 18 commits touching 22 files. Three independent systems each assumed single-face cards, causing repeated fix rounds:

1. Back face not registered in CardRepository
2. Keyword count derived wrong for real (non-puzzle) cards
3. Qualification annotation hardcoded per-card

All three are symptoms of the same structural gap: the mapping layer doesn't know about faces. This spec fixes the foundation so future multi-face types (MDFCs, Adventures, Split cards) don't repeat the cycle.

## Dependency Chain

```
R1 (CardData) -> R2 (auto-register) -> R3+R8 (AbilityRegistry + SlotLayout) -> R4 (transform detection) -> R5 (non-realloc) -> R6 (Qualification table)
```

R5 is independent — can land anytime. R6 depends on R4 for reliable transform events but can start with existing detection.

---

## R1: Face-Aware CardData (#262)

### Discovery

`LinkedFaceGrpIds` exists in Arena's client SQLite `Cards` table. It's bidirectional and handles all multi-face types:

| Card Type | Example | LinkedFaceGrpIds |
|-----------|---------|------------------|
| DFC | Concealing Curtains (78895) | `78896` |
| MDFC | Barkchannel Pathway (75298) | `75299` |
| Adventure | Bonecrusher Giant (70262) | `70488` |
| Split | Warrant // Warden (69376) | `69377,69378` |

Our `ExposedCardRepository` doesn't read this column.

### Changes

**CardData** — add field:

```kotlin
data class CardData(
    ...existing fields...,
    val linkedFaceGrpIds: List<Int> = emptyList(),
) {
    val isMultiFace: Boolean get() = linkedFaceGrpIds.isNotEmpty()
}
```

**ExposedCardRepository** — add `linkedFaceGrpIds` column to `Cards` table object. Parse comma-separated ints in `queryCardData()`:

```kotlin
// In Cards table object:
val linkedFaceGrpIds = text("LinkedFaceGrpIds").default("")

// In queryCardData():
linkedFaceGrpIds = row[Cards.linkedFaceGrpIds]
    .split(",")
    .filter { it.isNotBlank() }
    .map { it.trim().toInt() },
```

**CardRepository interface** — add:

```kotlin
fun findLinkedFaces(grpId: Int): List<Int>
```

Reads from CardData cache. Default implementation returns `findByGrpId(grpId)?.linkedFaceGrpIds ?: emptyList()`.

**ObjectMapper.resolveOthersideGrpId** — replace Forge `card.getState(CardStateName.Backside)` escape hatch with `cards.findLinkedFaces(grpId).firstOrNull() ?: 0`. Removes the direct Forge coupling from the mapper.

### Files touched

- `matchdoor/src/main/kotlin/leyline/game/CardData.kt`
- `matchdoor/src/main/kotlin/leyline/game/CardRepository.kt`
- `matchdoor/src/main/kotlin/leyline/game/ExposedCardRepository.kt`
- `matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt`

---

## R2: Auto-Register Back Faces in PuzzleCardRegistrar (#263)

### Current state

`registerAlternateFaces()` (added in commit `28e71cbe`) already walks `Card.getStates()` and registers alternate face CardData. This covers the registration gap.

### Remaining change

Ensure the front-face CardData's `linkedFaceGrpIds` is populated when registering from Forge. `PuzzleCardRegistrar.fromForgeCard()` must:

1. Check `card.isDoubleFaced` (or more broadly, `card.states.size > 1`)
2. For each alternate state, resolve its grpId (client DB lookup or synthetic)
3. Set `linkedFaceGrpIds` on the front-face CardData to point to the resolved grpIds

### Files touched

- `matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt`

---

## R3 + R8: AbilityRegistry Invalidation + SlotLayout (#264, #268)

### SlotLayout (R8)

Single source of truth for the ability slot layout. Produced by `AbilityIdDeriver`, consumed by `resolveAbilityIndex`.

```kotlin
data class SlotLayout(
    val keywordCount: Int,
    val activatedCount: Int,
    val slots: List<SlotEntry>,
) {
    fun forgeIndexFor(abilityGrpId: Int): Int? {
        val slot = slots.indexOfFirst { it.abilityGrpId == abilityGrpId }
        if (slot < 0) return null
        return slot - keywordCount
    }
}

data class SlotEntry(
    val abilityGrpId: Int,
    val textId: Int,
    val kind: SlotKind,
)

enum class SlotKind { Keyword, Activated, Mana, Intrinsic }
```

**AbilityIdDeriver** — `deriveAbilityIds()` returns `SlotLayout` instead of `List<Pair<Int, Int>>`. The `abilityIds` pairs on CardData remain unchanged (they're what gets written to proto); SlotLayout is the internal coordination type.

**AbilityRegistry** — `build()` produces a `SlotLayout` alongside the existing trait-to-slot mapping. The registry stores the layout and exposes it.

**MatchSession.resolveAbilityIndex** — calls `slotLayout.forgeIndexFor(abilityGrpId)` instead of re-deriving keyword count from `card.spellAbilities`. Eliminates dual-derivation bug class.

### AbilityRegistry Invalidation (R3)

**GameBridge** — when `CardTransformed` event fires (from `GameEventCollector.drainEvents()`), evict the AbilityRegistry entry for that card ID:

```kotlin
// In the event processing loop in StateMapper or GameBridge:
is GameEvent.CardTransformed -> {
    abilityRegistries.remove(ev.cardId)
}
```

Next access to `abilityRegistryFor(cardId)` rebuilds from the card's current (transformed) face. SlotLayout is rebuilt too since it's produced by the same build path.

### Files touched

- `matchdoor/src/main/kotlin/leyline/game/AbilityIdDeriver.kt` (new SlotLayout return type)
- `matchdoor/src/main/kotlin/leyline/game/AbilityRegistry.kt` (store + expose SlotLayout)
- `matchdoor/src/main/kotlin/leyline/game/GameBridge.kt` (eviction on transform)
- `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt` (consume SlotLayout)

---

## R4: Decouple Transform Detection (#265)

### Discovery

`GameEventCardStatsChanged` has a `transform` boolean field, but it's **hardcoded to `false`** in all constructors (Forge source line 24: "disable for now"). No dedicated Forge transform event exists.

Our current `lastBackSide` tracking in `GameEventCollector` is the correct approach — just needs structural cleanup.

### Changes

1. **Upgrade tracking type:** `lastBackSide: ConcurrentHashMap<ForgeCardId, Boolean>` becomes `lastStateName: ConcurrentHashMap<ForgeCardId, CardStateName>`. Covers Original, Backside, Flipped, Meld, Adventure, Modal.

2. **Extract to named method:** Move transform detection out of the `GameEventCardStatsChanged` visitor body into `checkForTransform(card: CardView)`. The visitor calls this for each card in `ev.cards()`.

3. **Expand CardTransformed event:**

```kotlin
// Before:
data class CardTransformed(val cardId: ForgeCardId, val isBackSide: Boolean)

// After:
data class CardTransformed(
    val cardId: ForgeCardId,
    val newStateName: CardStateName,
) {
    val isBackSide: Boolean get() = newStateName == CardStateName.Backside
}
```

Forward-compatible with Modal, Adventure, Meld face types.

4. **Clear cache on zone exit:** Keep the existing cleanup when cards leave the battlefield (`lastStateName.remove(cardId)`).

### Files touched

- `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt`
- `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt` (CardTransformed shape)

---

## R5: Extract Non-Realloc Categories (#266)

### Changes

Add `keepsSameInstanceId` property to `TransferCategory`:

```kotlin
enum class TransferCategory(
    val label: String,
    val keepsSameInstanceId: Boolean = false,
) {
    Resolve("Resolve", keepsSameInstanceId = true),
    // all others default to false
    PlayLand("PlayLand"),
    CastSpell("CastSpell"),
    Destroy("Destroy"),
    ...
}
```

**AnnotationPipeline.detectZoneTransfers** — replace:

```kotlin
// Before:
if (category != TransferCategory.Resolve && forgeCardId != null)

// After:
if (!category.keepsSameInstanceId && forgeCardId != null)
```

When DFC in-place transform lands as a transfer category, it gets `keepsSameInstanceId = true` — data, not conditionals.

### Files touched

- `matchdoor/src/main/kotlin/leyline/game/TransferCategory.kt`
- `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt`

---

## R6: Generalize Qualification Annotations (#267)

### Changes

**KeywordQualifications table:**

```kotlin
object KeywordQualifications {
    data class QualInfo(
        val grpId: Int,
        val qualificationType: Int,
        val qualificationSubtype: Int = 0,
    )

    private val table: Map<String, QualInfo> = mapOf(
        "Menace" to QualInfo(grpId = 142, qualificationType = 40),
        // Add entries as recordings provide data for other keywords
    )

    fun forKeyword(keyword: String): QualInfo? = table[keyword]
    fun knownKeywords(): Set<String> = table.keys
}
```

**AnnotationPipeline (mechanicAnnotations)** — replace hardcoded Menace emission:

```kotlin
is GameEvent.CardTransformed -> {
    val instanceId = idResolver(ev.cardId).value
    if (ev.isBackSide) {
        // Scan back face keywords, emit Qualification pAnns for known ones
        val forgeCard = cardResolver(ev.cardId) ?: return@forEach
        for (keyword in forgeCard.keywords) {
            val qual = KeywordQualifications.forKeyword(keyword) ?: run {
                log.warn("Unknown keyword Qualification for '{}' on transform", keyword)
                return@run null
            } ?: continue
            persistent.add(
                AnnotationBuilder.qualification(
                    affectorId = instanceId,
                    instanceId = instanceId,
                    grpId = qual.grpId,
                    qualificationType = qual.qualificationType,
                    qualificationSubtype = qual.qualificationSubtype,
                    sourceParent = instanceId,
                )
            )
        }
    }
    // TODO: when isBackSide=false (transforming back), retire the pAnns
}
```

**Populating the table:** Recording-driven. Each keyword needs one recording of a card with that keyword to capture the real server's grpId + qualificationType. Start with Menace (known). The `log.warn` makes gaps visible in server logs without crashing.

### Files touched

- `matchdoor/src/main/kotlin/leyline/game/KeywordQualifications.kt` (new)
- `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt`

---

## Testing Strategy

| Change | Test approach |
|--------|---------------|
| R1 (CardData) | Unit test: `ExposedCardRepository` returns `linkedFaceGrpIds` for DFC/MDFC/Split cards. Unit test: `ObjectMapper.resolveOthersideGrpId` uses CardData, not Forge. |
| R2 (auto-register) | Puzzle test: DFC puzzle with back-face abilities — no manual registration needed. |
| R3 (invalidation) | Unit test: after `CardTransformed`, registry rebuild returns back-face ability slots. |
| R8 (SlotLayout) | Unit test: `SlotLayout.forgeIndexFor` returns correct indices. Integration: `resolveAbilityIndex` matches expected Forge ability. |
| R4 (transform detection) | Unit test: `checkForTransform` emits `CardTransformed` with correct `CardStateName`. Test: non-P/T-changing transform still detected. |
| R5 (non-realloc) | Existing tests — behavioral no-op. Verify `TransferCategory.Resolve.keepsSameInstanceId == true`. |
| R6 (Qualification) | Puzzle test: DFC with Menace back face emits Qualification pAnn. PurePipelineTest with synthetic CardTransformed event. |

## Out of Scope

- MDFC casting (ActionType 18/19) — separate feature work
- Adventure casting — separate feature work
- Split card fuse — separate feature work
- Multiplayer zone layout — too early
- CardIdentity wrapper type (#269) — deferred until this lands and stabilizes
