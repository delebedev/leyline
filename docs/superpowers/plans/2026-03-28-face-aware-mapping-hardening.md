# Face-Aware Mapping Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the Arena-Forge mapping layer with face-awareness so DFCs, MDFCs, Adventures, and Split cards don't require per-card fixes.

**Architecture:** Bottom-up chain: CardData gains `linkedFaceGrpIds` from client DB -> PuzzleCardRegistrar auto-populates it -> AbilityRegistry uses formalized SlotLayout and invalidates on transform -> transform detection becomes structurally clean -> non-realloc categories become data -> Qualification annotations become table-driven.

**Tech Stack:** Kotlin, Kotest FunSpec, protobuf (messages.proto), Exposed (SQLite ORM), Forge engine submodule

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `matchdoor/src/main/kotlin/leyline/game/CardData.kt` | Modify | Add `linkedFaceGrpIds` field |
| `matchdoor/src/main/kotlin/leyline/game/CardRepository.kt` | Modify | Add `findLinkedFaces()` default method |
| `matchdoor/src/main/kotlin/leyline/game/ExposedCardRepository.kt` | Modify | Read `LinkedFaceGrpIds` column |
| `matchdoor/src/main/kotlin/leyline/game/InMemoryCardRepository.kt` | No change | Inherits default `findLinkedFaces()` |
| `matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt` | Modify | Populate `linkedFaceGrpIds` for Forge cards |
| `matchdoor/src/main/kotlin/leyline/game/SlotLayout.kt` | Create | SlotLayout, SlotEntry, SlotKind types |
| `matchdoor/src/main/kotlin/leyline/game/AbilityIdDeriver.kt` | Modify | Return SlotLayout alongside abilityIds |
| `matchdoor/src/main/kotlin/leyline/game/AbilityRegistry.kt` | Modify | Store + expose SlotLayout |
| `matchdoor/src/main/kotlin/leyline/game/GameBridge.kt` | Modify | Evict AbilityRegistry on transform |
| `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt` | Modify | Consume SlotLayout in resolveAbilityIndex |
| `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt` | Modify | Expand CardTransformed to use CardStateName |
| `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt` | Modify | Extract transform detection, upgrade tracking |
| `matchdoor/src/main/kotlin/leyline/game/TransferCategory.kt` | Modify | Add `keepsSameInstanceId` property |
| `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt` | Modify | Use `keepsSameInstanceId`, wire Qualification |
| `matchdoor/src/main/kotlin/leyline/game/KeywordQualifications.kt` | Create | Keyword-to-QualificationType mapping table |
| `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` | Modify | Add `qualification()` builder method |
| `matchdoor/src/test/kotlin/leyline/game/CardRepositoryTest.kt` | Modify | Add `linkedFaceGrpIds` tests |
| `matchdoor/src/test/kotlin/leyline/game/SlotLayoutTest.kt` | Create | SlotLayout unit tests |
| `matchdoor/src/test/kotlin/leyline/game/AbilityRegistryTest.kt` | Modify | Add transform invalidation test |
| `matchdoor/src/test/kotlin/leyline/game/GameEventCollectorTest.kt` | Modify | Add transform detection tests |
| `matchdoor/src/test/kotlin/leyline/game/TransferCategoryTest.kt` | Create | Verify keepsSameInstanceId property |
| `matchdoor/src/test/kotlin/leyline/game/KeywordQualificationsTest.kt` | Create | Qualification table lookup tests |

---

### Task 1: Face-Aware CardData (R1, #262)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/CardData.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/CardRepository.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/ExposedCardRepository.kt`
- Test: `matchdoor/src/test/kotlin/leyline/game/CardRepositoryTest.kt`

- [ ] **Step 1: Write failing tests for linkedFaceGrpIds on CardData and CardRepository**

In `CardRepositoryTest.kt`, add tests after the existing `tokenGrpIdForCard` tests:

```kotlin
// --- linkedFaceGrpIds ---

test("CardData isMultiFace true when linkedFaceGrpIds non-empty") {
    val data = CardData(
        grpId = 78895, titleId = 1, power = "0", toughness = "4",
        colors = listOf(3), types = listOf(2), subtypes = emptyList(),
        supertypes = emptyList(), abilityIds = emptyList(), manaCost = emptyList(),
        linkedFaceGrpIds = listOf(78896),
    )
    data.isMultiFace shouldBe true
}

test("CardData isMultiFace false when linkedFaceGrpIds empty") {
    val data = CardData(
        grpId = 75515, titleId = 1, power = "2", toughness = "2",
        colors = emptyList(), types = listOf(2), subtypes = emptyList(),
        supertypes = emptyList(), abilityIds = emptyList(), manaCost = emptyList(),
    )
    data.isMultiFace shouldBe false
}

test("findLinkedFaces returns linkedFaceGrpIds from registered CardData") {
    repo.registerData(
        CardData(
            grpId = 78895, titleId = 1, power = "0", toughness = "4",
            colors = emptyList(), types = emptyList(), subtypes = emptyList(),
            supertypes = emptyList(), abilityIds = emptyList(), manaCost = emptyList(),
            linkedFaceGrpIds = listOf(78896),
        ),
        "Concealing Curtains",
    )
    repo.findLinkedFaces(78895) shouldBe listOf(78896)
}

test("findLinkedFaces returns empty for unknown grpId") {
    repo.findLinkedFaces(99999) shouldBe emptyList()
}

test("findLinkedFaces returns empty for single-face card") {
    repo.registerData(
        CardData(
            grpId = 75515, titleId = 1, power = "2", toughness = "2",
            colors = emptyList(), types = emptyList(), subtypes = emptyList(),
            supertypes = emptyList(), abilityIds = emptyList(), manaCost = emptyList(),
        ),
        "Grizzly Bears",
    )
    repo.findLinkedFaces(75515) shouldBe emptyList()
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `just test-one CardRepositoryTest`
Expected: Compilation errors — `linkedFaceGrpIds` parameter doesn't exist on `CardData`, `isMultiFace` property doesn't exist, `findLinkedFaces` method doesn't exist.

- [ ] **Step 3: Add `linkedFaceGrpIds` to CardData**

In `matchdoor/src/main/kotlin/leyline/game/CardData.kt`, add the field after `keywordAbilityGrpIds` and add the computed property:

```kotlin
data class CardData(
    val grpId: Int,
    val titleId: Int,
    val power: String,
    val toughness: String,
    val colors: List<Int>, // proto CardColor values
    val types: List<Int>, // proto CardType values
    val subtypes: List<Int>, // proto SubType values
    val supertypes: List<Int>, // proto SuperType values
    val abilityIds: List<Pair<Int, Int>>, // abilityGrpId:textId pairs
    val manaCost: List<Pair<ManaColor, Int>>, // (color, count) from OldSchoolManaText
    val tokenGrpIds: Map<Int, Int> = emptyMap(), // abilityGrpId → tokenGrpId
    val keywordAbilityGrpIds: Map<String, Int> = emptyMap(), // keyword name → abilityGrpId
    val linkedFaceGrpIds: List<Int> = emptyList(), // DFC/MDFC/Adventure/Split linked faces
) {
    /** True if this card has linked faces (DFC, MDFC, Adventure, Split). */
    val isMultiFace: Boolean get() = linkedFaceGrpIds.isNotEmpty()
}
```

- [ ] **Step 4: Add `findLinkedFaces` to CardRepository interface**

In `matchdoor/src/main/kotlin/leyline/game/CardRepository.kt`, add a default method:

```kotlin
/** Linked face grpIds for multi-face cards (DFC, MDFC, Adventure, Split). */
fun findLinkedFaces(grpId: Int): List<Int> =
    findByGrpId(grpId)?.linkedFaceGrpIds ?: emptyList()
```

- [ ] **Step 5: Add `LinkedFaceGrpIds` column to ExposedCardRepository**

In `matchdoor/src/main/kotlin/leyline/game/ExposedCardRepository.kt`:

Add to `Cards` table object (after `expansionCode`):
```kotlin
val linkedFaceGrpIds = text("LinkedFaceGrpIds").default("")
```

In `queryCardData()`, add to the `CardData(...)` constructor call (after `tokenGrpIds`):
```kotlin
linkedFaceGrpIds = parseIntList(row[Cards.linkedFaceGrpIds]),
```

Note: `parseIntList` already handles comma-separated ints and blanks — used for `colors`, `types`, etc.

- [ ] **Step 6: Run tests to verify they pass**

Run: `just test-one CardRepositoryTest`
Expected: All pass including the new `linkedFaceGrpIds` tests.

- [ ] **Step 7: Run format and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/CardData.kt \
       matchdoor/src/main/kotlin/leyline/game/CardRepository.kt \
       matchdoor/src/main/kotlin/leyline/game/ExposedCardRepository.kt \
       matchdoor/src/test/kotlin/leyline/game/CardRepositoryTest.kt
git commit -m "feat(game): add linkedFaceGrpIds to CardData (#262)

Read LinkedFaceGrpIds from client card DB. Add findLinkedFaces()
to CardRepository interface with default implementation.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Auto-Register Back Faces in PuzzleCardRegistrar (R2, #263)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt`
- Test: existing puzzle tests exercise this path

- [ ] **Step 1: Write a test for linkedFaceGrpIds population on puzzle DFC cards**

In `CardRepositoryTest.kt`, add:

```kotlin
test("PuzzleCardRegistrar populates linkedFaceGrpIds for DFC") {
    val registrar = PuzzleCardRegistrar(repo)
    val card = Card.fromPaperCard(
        FModel.getMagicDb().commonCards.getCard("Concealing Curtains"),
        null,
    )
    registrar.ensureCardRegistered(card)

    val frontGrpId = repo.findGrpIdByName("Concealing Curtains")
    frontGrpId.shouldNotBeNull()
    val frontData = repo.findByGrpId(frontGrpId)
    frontData.shouldNotBeNull()
    frontData.linkedFaceGrpIds.shouldHaveSize(1)

    // Back face should also be registered
    val backGrpId = repo.findGrpIdByName("Revealing Eye")
    backGrpId.shouldNotBeNull()
    frontData.linkedFaceGrpIds[0] shouldBe backGrpId
}
```

Note: This test requires `initCardDatabase()` in `beforeSpec`. If `CardRepositoryTest` doesn't have it, either add a `ConformanceTag`-tagged test in a new file `PuzzleCardRegistrarTest.kt` or add `base.initCardDatabase()` setup.

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one CardRepositoryTest` (or the new test file)
Expected: FAIL — `frontData.linkedFaceGrpIds` is empty.

- [ ] **Step 3: Populate linkedFaceGrpIds in PuzzleCardRegistrar.fromForgeCard()**

In `matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt`, modify `fromForgeCard()`. After constructing the `CardData`, resolve linked face grpIds:

```kotlin
private fun fromForgeCard(card: Card): CardData {
    val name = card.name
    val grpId = nameToGrpId.getOrPut(name) { nextGrpId.getAndIncrement() }
    val titleId = nextTitleId.getAndIncrement()

    // ... existing type/color/power/toughness/manaCost/abilityIds derivation ...

    // Resolve linked face grpIds for multi-face cards
    val linkedFaces = resolveLinkedFaceGrpIds(card)

    return CardData(
        grpId = grpId,
        titleId = titleId,
        power = power,
        toughness = toughness,
        colors = colors,
        types = types,
        subtypes = subtypes,
        supertypes = supertypes,
        abilityIds = abilityIds,
        manaCost = manaCost,
        keywordAbilityGrpIds = keywordAbilityGrpIds,
        linkedFaceGrpIds = linkedFaces,
    )
}
```

Add the helper method:

```kotlin
/**
 * Resolve linked face grpIds for multi-face cards.
 * Checks alternate states (Backside, Flipped, Modal, Adventure, Meld).
 * Uses client DB if available, otherwise allocates synthetic grpIds.
 */
private fun resolveLinkedFaceGrpIds(card: Card): List<Int> {
    val states = card.states ?: return emptyList()
    if (states.size <= 1) return emptyList()

    val linkedIds = mutableListOf<Int>()
    for ((stateName, _) in states) {
        if (stateName == forge.card.CardStateName.Original) continue
        if (stateName == forge.card.CardStateName.FaceDown) continue
        val altState = card.getState(stateName) ?: continue
        val altName = altState.name ?: continue
        if (altName == card.name) continue

        // Resolve grpId: try client DB, then existing registration, then allocate synthetic
        val altGrpId = clientRepo?.findGrpIdByName(altName)
            ?: repo.findGrpIdByName(altName)
            ?: nameToGrpId.getOrPut(altName) { nextGrpId.getAndIncrement() }
        linkedIds.add(altGrpId)
    }
    return linkedIds
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `just test-one CardRepositoryTest` (or the new test file)
Expected: PASS

- [ ] **Step 5: Run format and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt \
       matchdoor/src/test/kotlin/leyline/game/CardRepositoryTest.kt
git commit -m "feat(game): auto-populate linkedFaceGrpIds in PuzzleCardRegistrar (#263)

Multi-face cards (DFC, MDFC, Adventure, Split) now have their
linkedFaceGrpIds set when registered from Forge card objects.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: SlotLayout Data Type (R8, #268)

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/game/SlotLayout.kt`
- Create: `matchdoor/src/test/kotlin/leyline/game/SlotLayoutTest.kt`

- [ ] **Step 1: Write failing tests for SlotLayout**

Create `matchdoor/src/test/kotlin/leyline/game/SlotLayoutTest.kt`:

```kotlin
package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class SlotLayoutTest : FunSpec({

    tags(UnitTag)

    test("forgeIndexFor returns activated ability index offset by keyword count") {
        val layout = SlotLayout(
            keywordCount = 2,
            activatedCount = 1,
            slots = listOf(
                SlotEntry(abilityGrpId = 100, textId = 0, kind = SlotKind.Keyword),
                SlotEntry(abilityGrpId = 101, textId = 0, kind = SlotKind.Keyword),
                SlotEntry(abilityGrpId = 102, textId = 0, kind = SlotKind.Activated),
            ),
        )
        // Activated ability at slot 2, minus 2 keywords = forge index 0
        layout.forgeIndexFor(102) shouldBe 0
    }

    test("forgeIndexFor returns null for unknown abilityGrpId") {
        val layout = SlotLayout(
            keywordCount = 1,
            activatedCount = 0,
            slots = listOf(
                SlotEntry(abilityGrpId = 100, textId = 0, kind = SlotKind.Keyword),
            ),
        )
        layout.forgeIndexFor(999).shouldBeNull()
    }

    test("forgeIndexFor with zero keywords returns slot index directly") {
        val layout = SlotLayout(
            keywordCount = 0,
            activatedCount = 2,
            slots = listOf(
                SlotEntry(abilityGrpId = 200, textId = 0, kind = SlotKind.Activated),
                SlotEntry(abilityGrpId = 201, textId = 0, kind = SlotKind.Activated),
            ),
        )
        layout.forgeIndexFor(200) shouldBe 0
        layout.forgeIndexFor(201) shouldBe 1
    }

    test("forgeIndexFor keyword returns negative offset") {
        val layout = SlotLayout(
            keywordCount = 2,
            activatedCount = 0,
            slots = listOf(
                SlotEntry(abilityGrpId = 100, textId = 0, kind = SlotKind.Keyword),
                SlotEntry(abilityGrpId = 101, textId = 0, kind = SlotKind.Keyword),
            ),
        )
        // Keywords return negative indices — caller should treat as "not an activated ability"
        layout.forgeIndexFor(100) shouldBe -2
        layout.forgeIndexFor(101) shouldBe -1
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one SlotLayoutTest`
Expected: Compilation error — `SlotLayout`, `SlotEntry`, `SlotKind` don't exist.

- [ ] **Step 3: Create SlotLayout data types**

Create `matchdoor/src/main/kotlin/leyline/game/SlotLayout.kt`:

```kotlin
package leyline.game

/**
 * Single source of truth for the ability slot layout of a card.
 *
 * Produced by [AbilityIdDeriver], consumed by [AbilityRegistry] and
 * `MatchSession.resolveAbilityIndex`. Eliminates the dual-derivation
 * bug class where keyword count was computed independently in two places.
 *
 * Slot ordering: keywords occupy slots `[0, keywordCount)`,
 * activated abilities occupy `[keywordCount, keywordCount + activatedCount)`.
 */
data class SlotLayout(
    val keywordCount: Int,
    val activatedCount: Int,
    val slots: List<SlotEntry>,
) {
    /**
     * Map an Arena abilityGrpId to its Forge ability index.
     *
     * Returns the slot index minus keyword count:
     * - Activated abilities return `>= 0` (the Forge ability index)
     * - Keywords return negative values (not activated abilities)
     * - Unknown abilityGrpIds return `null`
     */
    fun forgeIndexFor(abilityGrpId: Int): Int? {
        val slot = slots.indexOfFirst { it.abilityGrpId == abilityGrpId }
        if (slot < 0) return null
        return slot - keywordCount
    }

    /** The abilityIds pairs for CardData (grpId to textId). */
    fun toAbilityIdPairs(): List<Pair<Int, Int>> = slots.map { it.abilityGrpId to it.textId }

    companion object {
        val EMPTY = SlotLayout(keywordCount = 0, activatedCount = 0, slots = emptyList())
    }
}

data class SlotEntry(
    val abilityGrpId: Int,
    val textId: Int,
    val kind: SlotKind,
)

enum class SlotKind { Keyword, Activated, Mana, Intrinsic }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `just test-one SlotLayoutTest`
Expected: All 4 tests pass.

- [ ] **Step 5: Run format and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/SlotLayout.kt \
       matchdoor/src/test/kotlin/leyline/game/SlotLayoutTest.kt
git commit -m "feat(game): add SlotLayout data type for ability slot contract (#268)

Single source of truth for keyword/activated ability slot layout.
Produced by AbilityIdDeriver, consumed by resolveAbilityIndex.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Wire SlotLayout into AbilityIdDeriver and AbilityRegistry (R8, #268)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AbilityIdDeriver.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/AbilityRegistry.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt`
- Test: `matchdoor/src/test/kotlin/leyline/game/AbilityRegistryTest.kt`

- [ ] **Step 1: Modify AbilityIdDeriver to return SlotLayout alongside abilityIds**

In `matchdoor/src/main/kotlin/leyline/game/AbilityIdDeriver.kt`:

Change the return type of `deriveAbilityIds` to include a `SlotLayout`. Since existing callers use the `Pair<List<Pair<Int,Int>>, Map<String,Int>>` return, add a new method and keep backward compat:

```kotlin
object AbilityIdDeriver {

    val BASIC_LAND_ABILITIES = listOf(
        "plains" to 1001,
        "island" to 1002,
        "swamp" to 1003,
        "mountain" to 1004,
        "forest" to 1005,
    )

    data class DerivedAbilities(
        val abilityIds: List<Pair<Int, Int>>,
        val keywordAbilityGrpIds: Map<String, Int>,
        val slotLayout: SlotLayout,
    )

    /**
     * Derive ability slots for a card.
     *
     * Layout: keywords occupy the first N slots, then non-mana activated abilities.
     * Returns [DerivedAbilities] with abilityIds, keyword map, and [SlotLayout].
     */
    fun deriveAbilityIds(
        card: Card,
        counter: AtomicInteger,
    ): DerivedAbilities {
        // Basic lands get well-known ability IDs
        val subtypes = card.type.subtypes.map { it.lowercase() }
        for ((subtype, abilityId) in BASIC_LAND_ABILITIES) {
            if (subtype in subtypes) return DerivedAbilities(
                abilityIds = listOf(abilityId to 0),
                keywordAbilityGrpIds = emptyMap(),
                slotLayout = SlotLayout(
                    keywordCount = 0,
                    activatedCount = 0,
                    slots = listOf(SlotEntry(abilityId, 0, SlotKind.Mana)),
                ),
            )
        }

        val keywords = card.rules?.mainPart?.keywords?.toList() ?: emptyList()
        val activatedCount = card.spellAbilities?.count { it.isActivatedAbility && !it.isManaAbility() } ?: 0
        val totalCount = maxOf(1, keywords.size + activatedCount)

        val abilityIds = (0 until totalCount).map { counter.getAndIncrement() to 0 }

        // Build SlotLayout entries
        val slotEntries = mutableListOf<SlotEntry>()
        val keywordMap = mutableMapOf<String, Int>()

        for ((i, kw) in keywords.withIndex()) {
            if (i < abilityIds.size) {
                keywordMap[kw.uppercase()] = abilityIds[i].first
                slotEntries.add(SlotEntry(abilityIds[i].first, 0, SlotKind.Keyword))
            }
        }
        for (i in keywords.size until totalCount) {
            slotEntries.add(SlotEntry(abilityIds[i].first, 0, SlotKind.Activated))
        }

        return DerivedAbilities(
            abilityIds = abilityIds,
            keywordAbilityGrpIds = keywordMap,
            slotLayout = SlotLayout(
                keywordCount = keywords.size,
                activatedCount = activatedCount,
                slots = slotEntries,
            ),
        )
    }
}
```

- [ ] **Step 2: Update PuzzleCardRegistrar to use DerivedAbilities**

In `matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt`, update `deriveAbilityIds` call in `fromForgeCard()`:

```kotlin
// Before:
val (abilityIds, keywordAbilityGrpIds) = deriveAbilityIds(card)

// After:
val derived = deriveAbilityIds(card)
val abilityIds = derived.abilityIds
val keywordAbilityGrpIds = derived.keywordAbilityGrpIds
```

And update the `deriveAbilityIds` private method:

```kotlin
private fun deriveAbilityIds(card: Card) = AbilityIdDeriver.deriveAbilityIds(card, nextAbilityGrpId)
```

- [ ] **Step 3: Add SlotLayout to AbilityRegistry**

In `matchdoor/src/main/kotlin/leyline/game/AbilityRegistry.kt`, add a `slotLayout` field:

```kotlin
class AbilityRegistry private constructor(
    private val saMap: Map<Int, Int>,
    private val staticMap: Map<Int, Int>,
    private val triggerMap: Map<Int, Int>,
    val slotLayout: SlotLayout = SlotLayout.EMPTY,
) {
```

Update `EMPTY`:
```kotlin
val EMPTY = AbilityRegistry(emptyMap(), emptyMap(), emptyMap(), SlotLayout.EMPTY)
```

Update `build()` to accept and pass through a `SlotLayout`:
```kotlin
fun build(card: Card, cardData: CardData, slotLayout: SlotLayout = SlotLayout.EMPTY): AbilityRegistry {
    val abilityIds = cardData.abilityIds
    if (abilityIds.isEmpty()) return EMPTY

    val fallbackGrpId = abilityIds[0].first
    val saMap = mutableMapOf<Int, Int>()
    val staticMap = mutableMapOf<Int, Int>()
    val triggerMap = mutableMapOf<Int, Int>()

    val keywordCount = mapKeywords(card, abilityIds, saMap, staticMap, triggerMap)
    mapActivatedAbilities(card, abilityIds, keywordCount, saMap)
    mapManaAbilities(card, fallbackGrpId, saMap)
    mapUnclaimedIntrinsics(card, fallbackGrpId, staticMap, triggerMap)

    return AbilityRegistry(saMap, staticMap, triggerMap, slotLayout)
}
```

- [ ] **Step 4: Run existing tests to verify nothing broke**

Run: `just test-one AbilityRegistryTest`
Expected: Pass — backward compatible (slotLayout defaults to EMPTY).

- [ ] **Step 5: Run format and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/AbilityIdDeriver.kt \
       matchdoor/src/main/kotlin/leyline/game/AbilityRegistry.kt \
       matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt
git commit -m "feat(game): wire SlotLayout through AbilityIdDeriver and AbilityRegistry (#268)

AbilityIdDeriver now returns DerivedAbilities with SlotLayout.
AbilityRegistry stores and exposes the layout. Backward compatible.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Consume SlotLayout in MatchSession.resolveAbilityIndex (R8, #268)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt`

- [ ] **Step 1: Replace dual-derivation in resolveAbilityIndex**

In `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt`, replace the `resolveAbilityIndex` method (lines 513-522):

```kotlin
/**
 * Map Arena abilityGrpId → Forge ability index via [SlotLayout].
 *
 * Uses the AbilityRegistry's SlotLayout as the single source of truth
 * for keyword/activated slot positions. Falls back to 0 if lookup fails.
 */
private fun resolveAbilityIndex(action: Action, bridge: GameBridge): Int {
    val abilityGrpId = action.abilityGrpId
    if (abilityGrpId == 0) return 0

    val cardData = bridge.cards.findByGrpId(action.grpId) ?: return 0
    val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return 0
    val game = bridge.getGame() ?: return 0
    val forgeCard = game.findById(forgeCardId.value) ?: return 0
    val registry = bridge.abilityRegistryFor(forgeCard, cardData) ?: return 0

    val index = registry.slotLayout.forgeIndexFor(abilityGrpId)
    return if (index != null && index >= 0) index else 0
}
```

This replaces the old approach that independently derived `keywordCount` from `cardData.keywordAbilityGrpIds.size`. Now keyword count comes from `SlotLayout` — single source of truth.

- [ ] **Step 2: Run existing ability activation tests**

Run: `just test-one ActivateAbilityTest`
Expected: Pass — behavior is identical, just sourced from SlotLayout.

- [ ] **Step 3: Run format and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/match/MatchSession.kt
git commit -m "refactor(match): use SlotLayout in resolveAbilityIndex (#268)

Single source of truth for keyword/activated slot positions.
Eliminates dual-derivation of keyword count.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: AbilityRegistry Invalidation on Transform (R3, #264)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameBridge.kt`

- [ ] **Step 1: Add eviction method to GameBridge**

In `matchdoor/src/main/kotlin/leyline/game/GameBridge.kt`, add a method near `abilityRegistryFor`:

```kotlin
/** Evict cached AbilityRegistry for a card (e.g. after DFC transform). */
fun evictAbilityRegistry(forgeCardId: Int) {
    abilityRegistries.remove(forgeCardId)
}
```

- [ ] **Step 2: Wire eviction from CardTransformed events**

Find where events are processed (in `StateMapper.buildFromGame()` or the event drain loop). Add:

```kotlin
// In the event processing loop where GameEvent.CardTransformed is handled:
is GameEvent.CardTransformed -> {
    bridge.evictAbilityRegistry(ev.cardId.value)
}
```

Note: The exact location depends on where `CardTransformed` events are currently consumed. If there's no existing handler for `CardTransformed` in the main branch, add it to the event processing block in `StateMapper.buildFromGame()` or wherever `drainEvents()` results are iterated.

- [ ] **Step 3: Run tests**

Run: `./gradlew :matchdoor:testGate`
Expected: All pass. No behavioral change until a DFC actually transforms.

- [ ] **Step 4: Commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/GameBridge.kt \
       matchdoor/src/main/kotlin/leyline/game/StateMapper.kt
git commit -m "fix(game): evict AbilityRegistry on CardTransformed (#264)

Cached ability slot layout goes stale when a DFC transforms.
Evict on transform so next access rebuilds from current face.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Decouple Transform Detection (R4, #265)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt`
- Modify or Create: `matchdoor/src/test/kotlin/leyline/game/GameEventCollectorTest.kt`

- [ ] **Step 1: Add CardTransformed event variant to GameEvent.kt**

If `CardTransformed` doesn't exist yet on this branch, add it to the `GameEvent` sealed interface in `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt`:

```kotlin
/** A card changed state (DFC transform, flip, modal face switch).
 *  [newStateName] is the Forge CardStateName after the change.
 *  Detected by [GameEventCollector] from [GameEventCardStatsChanged]. */
data class CardTransformed(
    val cardId: ForgeCardId,
    val newStateName: forge.card.CardStateName,
) : GameEvent {
    /** Convenience — true when the card flipped to its back face. */
    val isBackSide: Boolean get() = newStateName == forge.card.CardStateName.Backside
}
```

- [ ] **Step 2: Extract transform detection in GameEventCollector**

In `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt`:

Add a `lastStateName` cache (alongside `lastPT`):

```kotlin
/** Last-seen CardStateName per card — used to detect transform in GameEventCardStatsChanged. */
private val lastStateName = ConcurrentHashMap<ForgeCardId, forge.card.CardStateName>()
```

Add the detection method:

```kotlin
/**
 * Check if a card's state name changed (transform, flip, modal face switch).
 * Emits [GameEvent.CardTransformed] on change. Called per-card from
 * [visit(GameEventCardStatsChanged)].
 */
private fun checkForTransform(cardView: forge.game.card.CardView) {
    val id = ForgeCardId(cardView.id)
    val newState = cardView.currentState?.state ?: return
    val prevState = lastStateName.put(id, newState)
    if (prevState != null && prevState != newState) {
        queue.add(GameEvent.CardTransformed(id, newState))
        log.debug("event: CardTransformed card={} {} → {}", cardView.name, prevState, newState)
    }
}
```

In the existing `visit(ev: GameEventCardStatsChanged)` method, add `checkForTransform` call for each card:

```kotlin
override fun visit(ev: GameEventCardStatsChanged) {
    for (card in ev.cards()) {
        val id = ForgeCardId(card.id)

        // Detect transform (DFC, flip, modal)
        checkForTransform(card)

        // Detect P/T changes
        val newPower = card.currentState.power
        val newTough = card.currentState.toughness
        val prev = lastPT.put(id, Pair(newPower, newTough))
        val oldPower = prev?.first ?: newPower
        val oldTough = prev?.second ?: newTough
        if (oldPower != newPower || oldTough != newTough) {
            queue.add(
                GameEvent.PowerToughnessChanged(
                    cardId = id,
                    oldPower = oldPower,
                    newPower = newPower,
                    oldToughness = oldTough,
                    newToughness = newTough,
                ),
            )
            log.debug("event: P/T changed card={} {}/{}→{}/{}", card.name, oldPower, oldTough, newPower, newTough)
        }
    }
}
```

Clear `lastStateName` when cards leave the battlefield (in the existing `visit(GameEventCardChangeZone)`), alongside `lastPT`:

```kotlin
if (from == ZoneType.Battlefield) {
    lastPT.remove(ForgeCardId(card.id))
    lastStateName.remove(ForgeCardId(card.id))
}
```

- [ ] **Step 3: Run tests**

Run: `just test-one GameEventCollectorTest`
Expected: All existing tests pass. No new events emitted unless stats change fires with different state name.

- [ ] **Step 4: Commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/GameEvent.kt \
       matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt
git commit -m "feat(game): decouple transform detection from P/T events (#265)

CardTransformed event uses CardStateName (not Boolean). Transform
detection is a named method, independent of P/T change handling.
Forward-compatible with Modal, Adventure, Meld face types.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Extract Non-Realloc Categories (R5, #266)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/TransferCategory.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt`
- Create: `matchdoor/src/test/kotlin/leyline/game/TransferCategoryTest.kt`

- [ ] **Step 1: Write failing test for keepsSameInstanceId property**

Create `matchdoor/src/test/kotlin/leyline/game/TransferCategoryTest.kt`:

```kotlin
package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class TransferCategoryTest : FunSpec({

    tags(UnitTag)

    test("Resolve keeps same instanceId") {
        TransferCategory.Resolve.keepsSameInstanceId shouldBe true
    }

    test("all other categories realloc instanceId") {
        val nonRealloc = TransferCategory.entries.filter { it.keepsSameInstanceId }
        nonRealloc.map { it.name } shouldBe listOf("Resolve")
    }

    test("CastSpell does not keep same instanceId") {
        TransferCategory.CastSpell.keepsSameInstanceId shouldBe false
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one TransferCategoryTest`
Expected: Compilation error — `keepsSameInstanceId` property doesn't exist.

- [ ] **Step 3: Add keepsSameInstanceId to TransferCategory**

In `matchdoor/src/main/kotlin/leyline/game/TransferCategory.kt`:

```kotlin
enum class TransferCategory(
    val label: String,
    val keepsSameInstanceId: Boolean = false,
) {
    PlayLand("PlayLand"),
    CastSpell("CastSpell"),
    Resolve("Resolve", keepsSameInstanceId = true),
    Destroy("Destroy"),
    Sacrifice("Sacrifice"),
    Countered("Countered"),
    Bounce("Bounce"),
    Draw("Draw"),
    Discard("Discard"),
    Mill("Mill"),
    Exile("Exile"),
    Return("Return"),
    Search("Search"),
    Put("Put"),
    Surveil("Surveil"),
    SbaLegendRule("SBA_LegendRule"),
    ZoneTransfer("ZoneTransfer"),
}
```

- [ ] **Step 4: Update AnnotationPipeline to use keepsSameInstanceId**

In `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt`, line ~173:

```kotlin
// Before:
val realloc = if (category != TransferCategory.Resolve && forgeCardId != null) {

// After:
val realloc = if (!category.keepsSameInstanceId && forgeCardId != null) {
```

- [ ] **Step 5: Run tests**

Run: `just test-one TransferCategoryTest` and `./gradlew :matchdoor:testGate`
Expected: All pass. Behavioral no-op — same logic, data-driven.

- [ ] **Step 6: Commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/TransferCategory.kt \
       matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt \
       matchdoor/src/test/kotlin/leyline/game/TransferCategoryTest.kt
git commit -m "refactor(game): extract keepsSameInstanceId to TransferCategory (#266)

Replace hardcoded Resolve exception with data-driven property.
Future non-realloc categories (DFC in-place transform) just
set keepsSameInstanceId = true.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Generalize Qualification Annotations (R6, #267)

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/game/KeywordQualifications.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt`
- Create: `matchdoor/src/test/kotlin/leyline/game/KeywordQualificationsTest.kt`

- [ ] **Step 1: Write failing tests for KeywordQualifications**

Create `matchdoor/src/test/kotlin/leyline/game/KeywordQualificationsTest.kt`:

```kotlin
package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class KeywordQualificationsTest : FunSpec({

    tags(UnitTag)

    test("Menace has known QualInfo") {
        val info = KeywordQualifications.forKeyword("Menace")
        info.shouldNotBeNull()
        info.grpId shouldBe 142
        info.qualificationType shouldBe 40
        info.qualificationSubtype shouldBe 0
    }

    test("unknown keyword returns null") {
        KeywordQualifications.forKeyword("Nonexistent").shouldBeNull()
    }

    test("knownKeywords includes Menace") {
        KeywordQualifications.knownKeywords() shouldContain "Menace"
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one KeywordQualificationsTest`
Expected: Compilation error — `KeywordQualifications` doesn't exist.

- [ ] **Step 3: Create KeywordQualifications**

Create `matchdoor/src/main/kotlin/leyline/game/KeywordQualifications.kt`:

```kotlin
package leyline.game

/**
 * Mapping table: keyword name → Arena Qualification annotation parameters.
 *
 * Populated from real server recordings. Each keyword needs one recording
 * of a card with that keyword to capture the server's grpId and
 * qualificationType values. Unknown keywords log a warning at runtime
 * but don't crash.
 */
object KeywordQualifications {

    data class QualInfo(
        val grpId: Int,
        val qualificationType: Int,
        val qualificationSubtype: Int = 0,
    )

    private val table: Map<String, QualInfo> = mapOf(
        "Menace" to QualInfo(grpId = 142, qualificationType = 40),
        // Add entries as recordings provide data for other keywords:
        // "Flying" to QualInfo(grpId = ?, qualificationType = ?),
        // "Trample" to QualInfo(grpId = ?, qualificationType = ?),
        // "Lifelink" to QualInfo(grpId = ?, qualificationType = ?),
    )

    /** Look up Qualification parameters for a keyword. Returns null if unknown. */
    fun forKeyword(keyword: String): QualInfo? = table[keyword]

    /** All keywords with known Qualification mappings. */
    fun knownKeywords(): Set<String> = table.keys
}
```

- [ ] **Step 4: Run tests**

Run: `just test-one KeywordQualificationsTest`
Expected: All pass.

- [ ] **Step 5: Add qualification() builder to AnnotationBuilder if not present**

Check if `AnnotationBuilder` has a `qualification` method. If not, add one. The exact proto shape comes from the Concealing Curtains trace (spec `docs/card-specs/concealing-curtains.md` line 87):

```kotlin
// In AnnotationBuilder.kt:
fun qualification(
    affectorId: Int,
    instanceId: Int,
    grpId: Int,
    qualificationType: Int,
    qualificationSubtype: Int = 0,
    sourceParent: Int,
): AnnotationInfo {
    return AnnotationInfo.newBuilder()
        .setType(AnnotationType.Qualification)
        .addAffectorId(affectorId)
        .addAffectedIds(instanceId)
        .apply {
            addDetails(DetailKeys.intDetail("grpid", grpId))
            addDetails(DetailKeys.intDetail("QualificationType", qualificationType))
            addDetails(DetailKeys.intDetail("QualificationSubtype", qualificationSubtype))
            addDetails(DetailKeys.intDetail("SourceParent", sourceParent))
        }
        .build()
}
```

Note: Check if `AnnotationType.Qualification` exists in the proto. If not, use the raw int value from the recording trace. Also verify `DetailKeys` has the right constants or use string literals.

- [ ] **Step 6: Commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/KeywordQualifications.kt \
       matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt \
       matchdoor/src/test/kotlin/leyline/game/KeywordQualificationsTest.kt
git commit -m "feat(game): add KeywordQualifications table and qualification builder (#267)

Table-driven keyword→QualificationType mapping. Starts with Menace
(from recording). Unknown keywords log a warning, don't crash.
Builder method for Qualification persistent annotations.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Final Gate — Run All Tests

- [ ] **Step 1: Run full test gate**

```bash
just test-gate
```

Expected: All tests pass. No behavioral changes — all modifications are additive (new fields, new types, new builder methods) or exact behavioral equivalents (keepsSameInstanceId, SlotLayout).

- [ ] **Step 2: Run format check**

```bash
just fmt
```

Expected: No changes (already formatted per-task).

- [ ] **Step 3: Verify all files compile**

```bash
just build
```

Expected: Clean build.
