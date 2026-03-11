# Sealed Format Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add sealed format support — Course as a persistent domain entity with full lifecycle (join → pool → deckbuild → match → W/L → completion).

**Architecture:** CourseService owns the event lifecycle directly (ADR 001). Cross-module wiring via lambdas in LeylineServer composition root. Pack generation delegates to matchdoor's Forge access. See `docs/plans/2026-03-07-sealed-design.md` for full design.

**Tech Stack:** Kotlin, Exposed DSL (SQLite), Kotest FunSpec, kotlinx.serialization JSON builders.

**Key references:**
- Design doc: `docs/plans/2026-03-07-sealed-design.md`
- Existing patterns: `SqlitePlayerStore.kt` (Exposed tables), `DeckServiceTest.kt` (InMemory test doubles), `EventWireBuilder.kt` (wire JSON)
- Recording: `recordings/2026-03-07_11-49-05/` — real sealed session
- Client wire model: from Arena client decompilation (FD stub responses, field validation)

---

## Phase 1: Domain + Persistence

### Task 1: Course domain model

**Files:**
- Create: `frontdoor/src/main/kotlin/leyline/frontdoor/domain/Course.kt`

**Step 1: Create the domain model file**

```kotlin
package leyline.frontdoor.domain

@JvmInline value class CourseId(val value: String)

enum class CourseModule {
    Join, Sealed, GrantCardPool, DeckSelect, CreateMatch,
    MatchResults, RankUpdate, Complete, ClaimPrize;

    fun wireName(): String = name
}

data class CollationPool(val collationId: Int, val cardPool: List<Int>)

data class CourseDeck(
    val deckId: DeckId,
    val mainDeck: List<DeckCard>,
    val sideboard: List<DeckCard>,
)

data class CourseDeckSummary(
    val deckId: DeckId,
    val name: String,
    val tileId: Int,
    val format: String,
)

data class Course(
    val id: CourseId,
    val playerId: PlayerId,
    val eventName: String,
    val module: CourseModule,
    val wins: Int = 0,
    val losses: Int = 0,
    val cardPool: List<Int> = emptyList(),
    val cardPoolByCollation: List<CollationPool> = emptyList(),
    val deck: CourseDeck? = null,
    val deckSummary: CourseDeckSummary? = null,
)
```

**Step 2: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/domain/Course.kt
git commit -m "feat(sealed): add Course domain model — CourseId, CourseModule, Course entity"
```

---

### Task 2: CourseRepository interface

**Files:**
- Create: `frontdoor/src/main/kotlin/leyline/frontdoor/repo/CourseRepository.kt`

**Step 1: Create the repository interface**

Follow the `DeckRepository` pattern (`frontdoor/src/main/kotlin/leyline/frontdoor/repo/DeckRepository.kt`).

```kotlin
package leyline.frontdoor.repo

import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.CourseId
import leyline.frontdoor.domain.PlayerId

interface CourseRepository {
    fun findById(id: CourseId): Course?
    fun findByPlayer(playerId: PlayerId): List<Course>
    fun findByPlayerAndEvent(playerId: PlayerId, eventName: String): Course?
    fun save(course: Course)
    fun delete(id: CourseId)
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/repo/CourseRepository.kt
git commit -m "feat(sealed): add CourseRepository interface"
```

---

### Task 3: InMemoryCourseRepository test double

**Files:**
- Create: `frontdoor/src/test/kotlin/leyline/frontdoor/repo/InMemoryCourseRepository.kt`

Follow the `InMemoryDeckRepository` pattern (`frontdoor/src/test/kotlin/leyline/frontdoor/repo/InMemoryDeckRepository.kt`).

**Step 1: Create the in-memory implementation**

```kotlin
package leyline.frontdoor.repo

import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.CourseId
import leyline.frontdoor.domain.PlayerId

class InMemoryCourseRepository : CourseRepository {
    private val courses = mutableMapOf<CourseId, Course>()

    override fun findById(id: CourseId) = courses[id]

    override fun findByPlayer(playerId: PlayerId) =
        courses.values.filter { it.playerId == playerId }

    override fun findByPlayerAndEvent(playerId: PlayerId, eventName: String) =
        courses.values.firstOrNull { it.playerId == playerId && it.eventName == eventName }

    override fun save(course: Course) {
        courses[course.id] = course
    }

    override fun delete(id: CourseId) {
        courses.remove(id)
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :frontdoor:compileTestKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add frontdoor/src/test/kotlin/leyline/frontdoor/repo/InMemoryCourseRepository.kt
git commit -m "feat(sealed): add InMemoryCourseRepository test double"
```

---

### Task 4: CourseService — join + state transitions

**Files:**
- Create: `frontdoor/src/main/kotlin/leyline/frontdoor/service/CourseService.kt`
- Create: `frontdoor/src/test/kotlin/leyline/frontdoor/service/CourseServiceTest.kt`

**Step 1: Write the failing tests**

Follow the `DeckServiceTest` pattern (`frontdoor/src/test/kotlin/leyline/frontdoor/service/DeckServiceTest.kt`): Kotest FunSpec, `tags(FdTag)`, InMemory repo.

```kotlin
package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.frontdoor.FdTag
import leyline.frontdoor.domain.CourseModule
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.InMemoryCourseRepository

class CourseServiceTest :
    FunSpec({
        tags(FdTag)

        val repo = InMemoryCourseRepository()
        // Stub pool generator: returns 84 cards (6 packs × 14 cards)
        val poolGen: (String) -> GeneratedPool = { setCode ->
            GeneratedPool(
                cards = (1..84).toList(),
                byCollation = listOf(CollationPool(100026, (1..84).toList())),
                collationId = 100026,
            )
        }
        val service = CourseService(repo, poolGen)
        val playerId = PlayerId("p1")

        test("join sealed event creates course with DeckSelect module and card pool") {
            val course = service.join(playerId, "Sealed_FDN_20260307")
            course.module shouldBe CourseModule.DeckSelect
            course.cardPool.size shouldBe 84
            course.cardPoolByCollation.size shouldBe 1
            course.wins shouldBe 0
            course.losses shouldBe 0
        }

        test("join same event twice returns existing course") {
            val again = service.join(playerId, "Sealed_FDN_20260307")
            again.id shouldBe service.getCoursesForPlayer(playerId)
                .first { it.eventName == "Sealed_FDN_20260307" }.id
        }

        test("setDeck transitions to CreateMatch") {
            val deck = CourseDeck(
                deckId = leyline.frontdoor.domain.DeckId("deck1"),
                mainDeck = (1..40).map { leyline.frontdoor.domain.DeckCard(it, 1) },
                sideboard = (41..84).map { leyline.frontdoor.domain.DeckCard(it, 1) },
            )
            val summary = CourseDeckSummary(
                deckId = leyline.frontdoor.domain.DeckId("deck1"),
                name = "Sealed Deck",
                tileId = 12345,
                format = "Limited",
            )
            val course = service.setDeck(playerId, "Sealed_FDN_20260307", deck, summary)
            course.module shouldBe CourseModule.CreateMatch
            course.deck shouldNotBe null
        }

        test("recordMatchResult updates wins") {
            val course = service.recordMatchResult(playerId, "Sealed_FDN_20260307", won = true)
            course.wins shouldBe 1
            course.losses shouldBe 0
            course.module shouldBe CourseModule.CreateMatch
        }

        test("recordMatchResult updates losses") {
            val course = service.recordMatchResult(playerId, "Sealed_FDN_20260307", won = false)
            course.losses shouldBe 1
        }

        test("drop transitions to Complete") {
            val course = service.drop(playerId, "Sealed_FDN_20260307")
            course.module shouldBe CourseModule.Complete
        }

        test("join constructed event creates course at CreateMatch with empty pool") {
            val course = service.join(playerId, "Ladder")
            course.module shouldBe CourseModule.CreateMatch
            course.cardPool shouldBe emptyList()
        }

        test("getCoursesForPlayer returns all courses") {
            val courses = service.getCoursesForPlayer(playerId)
            courses.size shouldBe 2  // Sealed_FDN + Ladder
        }
    })
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :frontdoor:test --tests "leyline.frontdoor.service.CourseServiceTest" -x detekt`
Expected: FAIL — `CourseService` class not found

**Step 3: Implement CourseService**

Data classes needed by CourseService (put at top of CourseService.kt or in a separate file — keep it simple, same file is fine):

```kotlin
package leyline.frontdoor.service

import leyline.frontdoor.domain.CollationPool
import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.CourseDeck
import leyline.frontdoor.domain.CourseDeckSummary
import leyline.frontdoor.domain.CourseId
import leyline.frontdoor.domain.CourseModule
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.CourseRepository
import java.util.UUID

data class GeneratedPool(
    val cards: List<Int>,
    val byCollation: List<CollationPool>,
    val collationId: Int,
)

class CourseService(
    private val repo: CourseRepository,
    private val generatePool: (setCode: String) -> GeneratedPool,
) {
    /** Sealed event names contain the set code: "Sealed_FDN_20260307" → "FDN". */
    private fun extractSetCode(eventName: String): String {
        val parts = eventName.split("_")
        return if (parts.size >= 2 && parts[0].equals("Sealed", ignoreCase = true)) {
            parts[1]
        } else {
            "FDN" // fallback
        }
    }

    private fun isSealed(eventName: String): Boolean =
        eventName.startsWith("Sealed", ignoreCase = true)

    fun join(playerId: PlayerId, eventName: String): Course {
        // Idempotent — return existing course if already joined
        repo.findByPlayerAndEvent(playerId, eventName)?.let { return it }

        val course = if (isSealed(eventName)) {
            val setCode = extractSetCode(eventName)
            val pool = generatePool(setCode)
            Course(
                id = CourseId(UUID.randomUUID().toString()),
                playerId = playerId,
                eventName = eventName,
                module = CourseModule.DeckSelect,
                cardPool = pool.cards,
                cardPoolByCollation = pool.byCollation,
            )
        } else {
            // Constructed events: no pool, straight to CreateMatch
            Course(
                id = CourseId(UUID.randomUUID().toString()),
                playerId = playerId,
                eventName = eventName,
                module = CourseModule.CreateMatch,
            )
        }
        repo.save(course)
        return course
    }

    fun setDeck(
        playerId: PlayerId,
        eventName: String,
        deck: CourseDeck,
        summary: CourseDeckSummary,
    ): Course {
        val course = repo.findByPlayerAndEvent(playerId, eventName)
            ?: throw IllegalArgumentException("No course for $eventName")
        val updated = course.copy(
            module = CourseModule.CreateMatch,
            deck = deck,
            deckSummary = summary,
        )
        repo.save(updated)
        return updated
    }

    fun enterPairing(playerId: PlayerId, eventName: String): Course {
        val course = repo.findByPlayerAndEvent(playerId, eventName)
            ?: throw IllegalArgumentException("No course for $eventName")
        // Stay at CreateMatch — pairing doesn't change module
        return course
    }

    fun recordMatchResult(playerId: PlayerId, eventName: String, won: Boolean): Course {
        val course = repo.findByPlayerAndEvent(playerId, eventName)
            ?: throw IllegalArgumentException("No course for $eventName")
        val updated = if (won) {
            course.copy(wins = course.wins + 1)
        } else {
            course.copy(losses = course.losses + 1)
        }
        // Module stays CreateMatch — completion is checked by caller against EventDef limits
        repo.save(updated)
        return updated
    }

    fun getCoursesForPlayer(playerId: PlayerId): List<Course> =
        repo.findByPlayer(playerId)

    fun drop(playerId: PlayerId, eventName: String): Course {
        val course = repo.findByPlayerAndEvent(playerId, eventName)
            ?: throw IllegalArgumentException("No course for $eventName")
        val updated = course.copy(module = CourseModule.Complete)
        repo.save(updated)
        return updated
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :frontdoor:test --tests "leyline.frontdoor.service.CourseServiceTest" -x detekt`
Expected: All 8 tests PASS

**Step 5: Format**

Run: `just fmt`

**Step 6: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/service/CourseService.kt \
       frontdoor/src/test/kotlin/leyline/frontdoor/service/CourseServiceTest.kt
git commit -m "feat(sealed): add CourseService with join, setDeck, recordResult, drop + tests"
```

---

### Task 5: SQLite persistence — Courses table in SqlitePlayerStore

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/repo/SqlitePlayerStore.kt`
- Create: `frontdoor/src/test/kotlin/leyline/frontdoor/repo/SqliteCourseRepositoryTest.kt`

**Step 1: Write the failing test**

Follow the `SqlitePlayerStoreTest` pattern — temp file DB, `beforeSpec { store.createTables() }`.

```kotlin
package leyline.frontdoor.repo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.frontdoor.FdTag
import leyline.frontdoor.domain.CollationPool
import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.CourseDeck
import leyline.frontdoor.domain.CourseDeckSummary
import leyline.frontdoor.domain.CourseId
import leyline.frontdoor.domain.CourseModule
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File

class SqliteCourseRepositoryTest :
    FunSpec({
        tags(FdTag)

        val dbFile = File.createTempFile("test-courses", ".db")
        val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        val store = SqlitePlayerStore(db)

        beforeSpec { store.createTables() }
        afterSpec { dbFile.delete() }

        val playerId = PlayerId("p1")
        val courseId = CourseId("course-1")

        test("save and retrieve course") {
            val course = Course(
                id = courseId,
                playerId = playerId,
                eventName = "Sealed_FDN_20260307",
                module = CourseModule.DeckSelect,
                cardPool = listOf(1, 2, 3),
                cardPoolByCollation = listOf(CollationPool(100026, listOf(1, 2, 3))),
            )
            store.save(course)
            val found = store.findById(courseId)
            found shouldNotBe null
            found!!.eventName shouldBe "Sealed_FDN_20260307"
            found.module shouldBe CourseModule.DeckSelect
            found.cardPool shouldBe listOf(1, 2, 3)
            found.cardPoolByCollation shouldHaveSize 1
        }

        test("findByPlayerAndEvent") {
            val found = store.findByPlayerAndEvent(playerId, "Sealed_FDN_20260307")
            found shouldNotBe null
            found!!.id shouldBe courseId
        }

        test("findByPlayer returns all player courses") {
            val course2 = Course(
                id = CourseId("course-2"),
                playerId = playerId,
                eventName = "Ladder",
                module = CourseModule.CreateMatch,
            )
            store.save(course2)
            store.findByPlayer(playerId) shouldHaveSize 2
        }

        test("save updates existing course") {
            val updated = Course(
                id = courseId,
                playerId = playerId,
                eventName = "Sealed_FDN_20260307",
                module = CourseModule.CreateMatch,
                wins = 2,
                losses = 1,
                cardPool = listOf(1, 2, 3),
                cardPoolByCollation = listOf(CollationPool(100026, listOf(1, 2, 3))),
                deck = CourseDeck(
                    deckId = DeckId("d1"),
                    mainDeck = listOf(DeckCard(1, 1)),
                    sideboard = listOf(DeckCard(2, 1)),
                ),
                deckSummary = CourseDeckSummary(
                    deckId = DeckId("d1"),
                    name = "My Sealed",
                    tileId = 12345,
                    format = "Limited",
                ),
            )
            store.save(updated)
            val found = store.findById(courseId)!!
            found.wins shouldBe 2
            found.losses shouldBe 1
            found.module shouldBe CourseModule.CreateMatch
            found.deck shouldNotBe null
            found.deckSummary shouldNotBe null
        }

        test("delete removes course") {
            store.delete(courseId)
            store.findById(courseId) shouldBe null
        }
    })
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :frontdoor:test --tests "leyline.frontdoor.repo.SqliteCourseRepositoryTest" -x detekt`
Expected: FAIL — SqlitePlayerStore doesn't implement CourseRepository

**Step 3: Add Courses table + CourseRepository implementation to SqlitePlayerStore**

Modify `frontdoor/src/main/kotlin/leyline/frontdoor/repo/SqlitePlayerStore.kt`:

1. Add `CourseRepository` to the `implements` list on the class declaration (line 28-30)
2. Add a `Courses` table object after the `Decks` table (after line 55):

```kotlin
private object Courses : Table("courses") {
    val id = text("id")
    val playerId = text("player_id")
    val eventName = text("event_name")
    val module = text("module")
    val wins = integer("wins").default(0)
    val losses = integer("losses").default(0)
    val cardPool = text("card_pool").default("[]")
    val cardPoolByCollation = text("card_pool_by_collation").default("[]")
    val deck = text("deck").nullable()
    val deckSummary = text("deck_summary").nullable()
    override val primaryKey = PrimaryKey(id)
}
```

3. Add `Courses` to `createTables()` (line 74-76):

```kotlin
fun createTables() {
    transaction(database) { SchemaUtils.create(Players, Decks, Courses) }
}
```

4. Add serializable DTOs for JSON columns (after the existing `CardsBlob` — around line 68):

```kotlin
@Serializable
private data class CollationPoolDto(val collationId: Int, val cardPool: List<Int>)

@Serializable
private data class CourseDeckDto(
    val deckId: String,
    val mainDeck: List<CardEntry>,
    val sideboard: List<CardEntry>,
)

@Serializable
private data class CourseDeckSummaryDto(
    val deckId: String,
    val name: String,
    val tileId: Int,
    val format: String,
)
```

5. Add `CourseRepository` methods at the end of the class (after the existing DeckRepository/PlayerRepository implementations):

```kotlin
/* ---------- CourseRepository ---------- */

override fun findById(id: CourseId): Course? = transaction(database) {
    Courses.selectAll().where { Courses.id eq id.value }.firstOrNull()?.toCourse()
}

override fun findByPlayer(playerId: PlayerId): List<Course> = transaction(database) {
    Courses.selectAll().where { Courses.playerId eq playerId.value }.map { it.toCourse() }
}

override fun findByPlayerAndEvent(playerId: PlayerId, eventName: String): Course? =
    transaction(database) {
        Courses.selectAll().where {
            (Courses.playerId eq playerId.value) and (Courses.eventName eq eventName)
        }.firstOrNull()?.toCourse()
    }

override fun save(course: Course) {
    transaction(database) {
        val existing = Courses.selectAll().where { Courses.id eq course.id.value }.firstOrNull()
        if (existing != null) {
            Courses.update({ Courses.id eq course.id.value }) {
                it[module] = course.module.name
                it[wins] = course.wins
                it[losses] = course.losses
                it[cardPool] = json.encodeToString(course.cardPool)
                it[cardPoolByCollation] = json.encodeToString(
                    course.cardPoolByCollation.map { cp -> CollationPoolDto(cp.collationId, cp.cardPool) },
                )
                it[deck] = course.deck?.let { d ->
                    json.encodeToString(
                        CourseDeckDto(
                            d.deckId.value,
                            d.mainDeck.map { c -> CardEntry(c.grpId, c.quantity) },
                            d.sideboard.map { c -> CardEntry(c.grpId, c.quantity) },
                        ),
                    )
                }
                it[deckSummary] = course.deckSummary?.let { s ->
                    json.encodeToString(
                        CourseDeckSummaryDto(s.deckId.value, s.name, s.tileId, s.format),
                    )
                }
            }
        } else {
            Courses.insert {
                it[id] = course.id.value
                it[playerId] = course.playerId.value
                it[eventName] = course.eventName
                it[module] = course.module.name
                it[wins] = course.wins
                it[losses] = course.losses
                it[cardPool] = json.encodeToString(course.cardPool)
                it[cardPoolByCollation] = json.encodeToString(
                    course.cardPoolByCollation.map { cp -> CollationPoolDto(cp.collationId, cp.cardPool) },
                )
                it[deck] = course.deck?.let { d ->
                    json.encodeToString(
                        CourseDeckDto(
                            d.deckId.value,
                            d.mainDeck.map { c -> CardEntry(c.grpId, c.quantity) },
                            d.sideboard.map { c -> CardEntry(c.grpId, c.quantity) },
                        ),
                    )
                }
                it[deckSummary] = course.deckSummary?.let { s ->
                    json.encodeToString(
                        CourseDeckSummaryDto(s.deckId.value, s.name, s.tileId, s.format),
                    )
                }
            }
        }
    }
}

override fun delete(id: CourseId) {
    transaction(database) { Courses.deleteWhere { Courses.id eq id.value } }
}

private fun ResultRow.toCourse(): Course {
    val poolJson = this[Courses.cardPool]
    val collationJson = this[Courses.cardPoolByCollation]
    val deckJson = this[Courses.deck]
    val summaryJson = this[Courses.deckSummary]

    return Course(
        id = CourseId(this[Courses.id]),
        playerId = PlayerId(this[Courses.playerId]),
        eventName = this[Courses.eventName],
        module = CourseModule.valueOf(this[Courses.module]),
        wins = this[Courses.wins],
        losses = this[Courses.losses],
        cardPool = json.decodeFromString(poolJson),
        cardPoolByCollation = json.decodeFromString<List<CollationPoolDto>>(collationJson)
            .map { CollationPool(it.collationId, it.cardPool) },
        deck = deckJson?.let { d ->
            val dto = json.decodeFromString<CourseDeckDto>(d)
            CourseDeck(
                DeckId(dto.deckId),
                dto.mainDeck.map { DeckCard(it.cardId, it.quantity) },
                dto.sideboard.map { DeckCard(it.cardId, it.quantity) },
            )
        },
        deckSummary = summaryJson?.let { s ->
            val dto = json.decodeFromString<CourseDeckSummaryDto>(s)
            CourseDeckSummary(DeckId(dto.deckId), dto.name, dto.tileId, dto.format)
        },
    )
}
```

Add required imports at top of file:
```kotlin
import leyline.frontdoor.domain.CollationPool
import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.CourseDeck
import leyline.frontdoor.domain.CourseDeckSummary
import leyline.frontdoor.domain.CourseId
import leyline.frontdoor.domain.CourseModule
import org.jetbrains.exposed.v1.core.and
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :frontdoor:test --tests "leyline.frontdoor.repo.SqliteCourseRepositoryTest" -x detekt`
Expected: All 5 tests PASS

**Step 5: Run existing tests to verify no regression**

Run: `./gradlew :frontdoor:test -x detekt`
Expected: All tests PASS

**Step 6: Format**

Run: `just fmt`

**Step 7: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/repo/SqlitePlayerStore.kt \
       frontdoor/src/test/kotlin/leyline/frontdoor/repo/SqliteCourseRepositoryTest.kt
git commit -m "feat(sealed): add Courses table to SqlitePlayerStore + persistence tests"
```

---

## Phase 2: EventRegistry + Wire Builder

### Task 6: Add maxWins/maxLosses to EventDef + sealed event definition

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/service/EventRegistry.kt`

**Step 1: Add fields to EventDef**

Add to `EventDef` data class (after `eventTags` field, around line 28):

```kotlin
val maxWins: Int? = null,
val maxLosses: Int? = null,
```

**Step 2: Add sealed event definition**

Add to `events` list in `EventRegistry` (after `Jump_In_2024`, around line 290):

```kotlin
// Sealed
EventDef(
    "Sealed_FDN_20260307",
    "Sealed FDN",
    "Sealed",
    formatType = "Sealed",
    displayPriority = 75,
    flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards"),
    bladeBehavior = null,
    eventTags = listOf("Limited"),
    maxWins = 7,
    maxLosses = 3,
),
```

**Step 3: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Format + commit**

```bash
just fmt
git add frontdoor/src/main/kotlin/leyline/frontdoor/service/EventRegistry.kt
git commit -m "feat(sealed): add maxWins/maxLosses to EventDef, register Sealed_FDN event"
```

---

### Task 7: EventWireBuilder — real course serialization

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/wire/EventWireBuilder.kt`

The existing `buildCourseJson` (line 99) takes `(eventName, module)` strings and builds a zeroed-out stub. Replace it with an overload that takes a `Course` domain object and serializes real data.

**Step 1: Add Course-aware overload**

Add these imports and methods to `EventWireBuilder`:

```kotlin
import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.DeckCard
```

Add after the existing `toCoursesJson` method (line 56):

```kotlin
fun toCoursesJson(courses: List<Course>): String = buildJsonObject {
    putJsonArray("Courses") {
        for (course in courses) {
            add(buildCourseJson(course))
        }
    }
}.toString()

fun buildCourseJson(course: Course) = buildJsonObject {
    put("CourseId", course.id.value)
    put("InternalEventName", course.eventName)
    put("CurrentModule", course.module.wireName())
    put("ModulePayload", "")
    putJsonObject("CourseDeckSummary") {
        val s = course.deckSummary
        put("DeckId", s?.deckId?.value ?: "00000000-0000-0000-0000-000000000000")
        put("Name", s?.name ?: "")
        putJsonArray("Attributes") {}
        put("DeckTileId", s?.tileId ?: 0)
        put("DeckArtId", 0)
        putJsonObject("FormatLegalities") {}
        putJsonObject("PreferredCosmetics") {
            put("Avatar", ""); put("Sleeve", ""); put("Pet", ""); put("Title", "")
            putJsonArray("Emotes") {}
        }
        putJsonArray("DeckValidationSummaries") {}
        putJsonObject("UnownedCards") {}
    }
    putJsonObject("CourseDeck") {
        val d = course.deck
        putJsonArray("MainDeck") {
            d?.mainDeck?.forEach { add(buildDeckCardJson(it)) } }
        putJsonArray("ReducedSideboard") {}
        putJsonArray("Sideboard") {
            d?.sideboard?.forEach { add(buildDeckCardJson(it)) } }
        putJsonArray("CommandZone") {}
        putJsonArray("Companions") {}
        putJsonArray("CardSkins") {}
    }
    put("CurrentWins", course.wins)
    put("CurrentLosses", course.losses)
    putJsonArray("CardPool") {
        course.cardPool.forEach { add(JsonPrimitive(it)) }
    }
    putJsonArray("CardPoolByCollation") {
        for (cp in course.cardPoolByCollation) {
            add(buildJsonObject {
                put("CollationId", cp.collationId)
                putJsonArray("CardPool") {
                    cp.cardPool.forEach { add(JsonPrimitive(it)) }
                }
            })
        }
    }
    putJsonArray("CardStyles") {}
}

private fun buildDeckCardJson(card: DeckCard) = buildJsonObject {
    put("cardId", card.grpId)
    put("quantity", card.quantity)
}
```

**Step 2: Add buildJoinResponse**

The Event_Join response wraps the course with inventory data. Add:

```kotlin
fun buildJoinResponse(course: Course): String = buildJsonObject {
    // The join response IS the course object directly
    // (based on recording seq 227)
    val courseJson = buildCourseJson(course)
    courseJson.forEach { (key, value) -> put(key, value) }
}.toString()
```

**Step 3: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Format + commit**

```bash
just fmt
git add frontdoor/src/main/kotlin/leyline/frontdoor/wire/EventWireBuilder.kt
git commit -m "feat(sealed): add Course-aware wire serialization to EventWireBuilder"
```

---

## Phase 2.5: Golden Playtest

### Task 8: Extract golden payloads from recording

**Files:**
- Create: `frontdoor/src/main/resources/fd-golden/sealed-join.json`
- Create: `frontdoor/src/main/resources/fd-golden/sealed-setdeck.json`
- Create: `frontdoor/src/main/resources/fd-golden/sealed-courses.json`

**Step 1: Extract Event_Join response (seq 227)**

```bash
just fd-response Event_Join | jq . > frontdoor/src/main/resources/fd-golden/sealed-join.json
```

Verify it has the key fields: `CourseId`, `CurrentModule`, `CardPool` (84 items), `CardPoolByCollation`.

```bash
just fd-response Event_Join | jq 'keys'
just fd-response Event_Join | jq '.CardPool | length'
```

**Step 2: Extract Event_SetDeckV2 response (seq 244)**

```bash
just fd-response Event_SetDeckV2 | jq . > frontdoor/src/main/resources/fd-golden/sealed-setdeck.json
```

Verify: `CurrentModule` should be `"CreateMatch"`.

**Step 3: Extract Event_GetCoursesV2 response with sealed course (seq 684)**

```bash
just fd-response Event_GetCoursesV2 | jq . > frontdoor/src/main/resources/fd-golden/sealed-courses.json
```

Verify: should contain a `Courses` array with the sealed event entry.

**Step 4: Commit golden files**

```bash
git add frontdoor/src/main/resources/fd-golden/sealed-*.json
git commit -m "chore(sealed): extract golden payloads from proxy recording for wire validation"
```

---

### Task 9: Wire golden sealed responses in FrontDoorHandler

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/GoldenData.kt`
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt`

**Step 1: Add sealed golden fields to GoldenData**

Add fields to `GoldenData` class (around line 18-24):

```kotlin
val sealedJoinJson: String,
val sealedSetDeckJson: String,
val sealedCoursesJson: String,
```

Add to `loadFromClasspath()`:

```kotlin
sealedJoinJson = loadTextResource("fd-golden/sealed-join.json"),
sealedSetDeckJson = loadTextResource("fd-golden/sealed-setdeck.json"),
sealedCoursesJson = loadTextResource("fd-golden/sealed-courses.json"),
```

**Step 2: Update Event_Join handler to use sealed golden for sealed events**

In `FrontDoorHandler.kt`, replace the Event_Join handler (line 305-309):

```kotlin
CmdType.EVENT_JOIN.value -> {
    val req = FdRequests.parseEventJoin(json)
    log.info("Front Door: Event_Join event={}", req?.eventName)
    val isSealed = req?.eventName?.startsWith("Sealed") == true
    writer.send(ctx, txId, FdResponse.Json(
        if (isSealed) golden.sealedJoinJson else golden.eventJoinJson,
    ))
}
```

**Step 3: Update Event_SetDeckV2 handler to use sealed golden**

Replace the Event_SetDeckV2 handler (line 360-367):

```kotlin
CmdType.EVENT_SET_DECK_V2.value -> {
    val req = FdRequests.parseSetDeck(json)
    if (req != null && req.deckId != null) {
        selectedDeckByEvent[req.eventName] = req.deckId
    }
    log.info("Front Door: Event_SetDeckV2 event={} deck={}", req?.eventName, req?.deckId)
    val isSealed = req?.eventName?.startsWith("Sealed") == true
    writer.send(ctx, txId, FdResponse.Json(
        if (isSealed) golden.sealedSetDeckJson else golden.eventSetDeckJson,
    ))
}
```

**Step 4: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Format + commit**

```bash
just fmt
git add frontdoor/src/main/kotlin/leyline/frontdoor/GoldenData.kt \
       frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt
git commit -m "feat(sealed): wire golden sealed payloads in FD handler for playtest"
```

---

### Task 10: Golden playtest — `just serve` + client sealed flow

**Step 1: Clean DB for fresh state**

```bash
trash data/player.db 2>/dev/null; just seed-db
```

**Step 2: Start server**

```bash
tmux new-session -d -s leyline 'cd /Users/denislebedev/src/leyline && just serve'
```

Wait 5s, check logs: `tail -20 logs/leyline.log`

**Step 3: Launch client + navigate to sealed event**

```bash
bin/arena launch
```

Navigate to Events tab. Find "Sealed FDN". Click to join.

**Step 4: Verify the golden flow**

- **Join**: client shows card pool / deckbuilder UI (no crash)
- **Build deck**: select 40 cards, submit deck
- **Queue**: click Play — should transition to matchmaking

Check `recordings/latest/client-errors.jsonl` for any NREs or crashes.

**Step 5: Record results**

Note which steps worked and which crashed. Common failure modes:
- Null field in golden JSON → client NRE (check `~/Library/Logs/Wizards of the Coast/MTGA/Player.log`)
- Wrong `CurrentModule` string → client stuck
- Missing `CardPool` → empty deckbuilder

If crashes occur, compare our golden against `just fd-keys <seq>` and fix missing/null fields.

**Step 6: Stop server**

```bash
just stop
```

**Gate:** Client renders sealed deckbuilder without crashes. Deck submission doesn't NRE. If this fails, fix golden payloads before proceeding to Phase 3.

---

## Phase 3: Handler Wiring

### Task 11: Replace golden stubs with CourseService

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt`
- Modify: `app/main/kotlin/leyline/infra/LeylineServer.kt`

**Step 1: Add CourseService parameter to FrontDoorHandler**

Add to constructor (after `collectionService` parameter, around line 50):

```kotlin
private val courseService: CourseService? = null,
```

Add import:
```kotlin
import leyline.frontdoor.service.CourseService
```

**Step 2: Replace Event_Join handler** (line 305-309)

Replace the golden stub with CourseService call:

```kotlin
CmdType.EVENT_JOIN.value -> {
    val req = FdRequests.parseEventJoin(json)
    val eventName = req?.eventName
    log.info("Front Door: Event_Join event={}", eventName)
    if (eventName != null && courseService != null && playerId != null) {
        val course = courseService.join(playerId, eventName)
        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildJoinResponse(course)))
    } else {
        writer.send(ctx, txId, FdResponse.Json(golden.eventJoinJson))
    }
}
```

**Step 3: Replace Event_SetDeckV2 handler** (line 360-367)

```kotlin
CmdType.EVENT_SET_DECK_V2.value -> {
    val req = FdRequests.parseSetDeck(json)
    if (req != null && req.deckId != null) {
        selectedDeckByEvent[req.eventName] = req.deckId
    }
    log.info("Front Door: Event_SetDeckV2 event={} deck={}", req?.eventName, req?.deckId)
    if (req != null && courseService != null && playerId != null) {
        val deck = CourseDeck(
            deckId = DeckId(req.deckId ?: UUID.randomUUID().toString()),
            mainDeck = req.mainDeck,
            sideboard = req.sideboard,
        )
        val summary = CourseDeckSummary(
            deckId = DeckId(req.deckId ?: UUID.randomUUID().toString()),
            name = req.deckName ?: "Sealed Deck",
            tileId = req.tileId ?: 0,
            format = "Limited",
        )
        val course = courseService.setDeck(playerId, req.eventName, deck, summary)
        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildCourseJson(course).toString()))
    } else {
        writer.send(ctx, txId, FdResponse.Json(golden.eventSetDeckJson))
    }
}
```

Note: This step may need adjustments based on what `FdRequests.parseSetDeck` returns. Check the actual return type and field names. The key fields needed are `eventName`, `deckId`, `mainDeck` (as `List<DeckCard>`), `sideboard`, `deckName`, `tileId`. If `parseSetDeck` doesn't return `mainDeck`/`sideboard` as `DeckCard`, parse them from the raw JSON.

**Step 4: Replace Event_GetCoursesV2 handler** (find it in the handler — look for `EVENT_GET_COURSES`)

```kotlin
CmdType.EVENT_GET_COURSES_V2.value -> {
    log.info("Front Door: Event_GetCoursesV2")
    if (courseService != null && playerId != null) {
        val courses = courseService.getCoursesForPlayer(playerId)
        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.toCoursesJson(courses)))
    } else {
        writer.send(ctx, txId, FdResponse.Json(
            EventWireBuilder.toCoursesJson(EventRegistry.defaultCourses),
        ))
    }
}
```

**Step 5: Replace Event_Drop handler** (line 311-315)

```kotlin
CmdType.EVENT_DROP.value -> {
    val req = FdRequests.parseEventName(json)
    log.info("Front Door: Event_Drop event={}", req?.eventName)
    if (req?.eventName != null && courseService != null && playerId != null) {
        val course = courseService.drop(playerId, req.eventName)
        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildCourseJson(course).toString()))
    } else {
        writer.send(ctx, txId, FdResponse.Json("{}"))
    }
}
```

**Step 6: Wire CourseService in LeylineServer.startLocal()**

In `app/main/kotlin/leyline/infra/LeylineServer.kt`, in `startLocal()` (around line 169, after `val deckService = DeckService(store)`):

```kotlin
val courseService = CourseService(store) { setCode ->
    // TODO Phase 3: wire real pack generation via matchdoor Forge
    // For now, generate a dummy pool for testing
    GeneratedPool(
        cards = (1..84).toList(),
        byCollation = listOf(CollationPool(100026, (1..84).toList())),
        collationId = 100026,
    )
}
```

Add `courseService = courseService` to the `FrontDoorHandler(...)` constructor call (around line 196-207).

Add imports:
```kotlin
import leyline.frontdoor.service.CourseService
import leyline.frontdoor.service.GeneratedPool
import leyline.frontdoor.domain.CollationPool
```

**Step 7: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 8: Format + commit**

```bash
just fmt
git add frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt \
       app/main/kotlin/leyline/infra/LeylineServer.kt
git commit -m "feat(sealed): wire CourseService into FrontDoorHandler + LeylineServer"
```

---

### Task 12: FdRequests — check/extend parseSetDeck for deck card data

**Files:**
- Check: `frontdoor/src/main/kotlin/leyline/frontdoor/wire/FdRequests.kt`

**Step 1: Read FdRequests to see what parseSetDeck returns**

Check what fields are available. The handler in Task 8 needs `mainDeck` and `sideboard` as card lists from the parsed request. If `parseSetDeck` only returns `eventName` and `deckId`, extend it.

Look for the `parseSetDeck` method. The request JSON from the client looks like:
```json
{
  "EventName": "Sealed_FDN_20260307",
  "Summary": { "DeckId": "...", "Name": "...", "DeckTileId": 12345, ... },
  "Deck": { "MainDeck": [{"cardId":1,"quantity":1},...], "Sideboard": [...] }
}
```

Extend the return type to include the deck contents needed by CourseService. Adjust the Task 8 handler code accordingly if the field names differ.

**Step 2: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`

**Step 3: Commit if changes were needed**

```bash
just fmt
git add frontdoor/src/main/kotlin/leyline/frontdoor/wire/FdRequests.kt
git commit -m "feat(sealed): extend FdRequests.parseSetDeck to include deck card lists"
```

---

### Task 13: Smoke test — `just serve` with real CourseService

**Step 1: Delete/trash existing player.db to get clean state with new Courses table**

```bash
trash data/player.db 2>/dev/null; just seed-db
```

**Step 2: Start server**

```bash
tmux new-session -d -s leyline 'cd /Users/denislebedev/src/leyline && just serve'
```

Wait 5s, then check logs:
```bash
tail -20 logs/leyline.log
```

Expected: "Front Door (local) listening on :30010", no errors about Courses table.

**Step 3: Verify client connects**

```bash
bin/arena launch
```

Wait for client to connect. Check:
```bash
lsof -i :30010 | grep ESTABLISHED
```

Expected: MTGA process connected.

**Step 4: Navigate to Events tab — verify sealed event appears**

```bash
bin/arena ocr
```

Look for "Sealed FDN" in the events list. Click to join if visible.

**Step 5: Stop server + client**

```bash
just stop
```

**Step 6: Note any issues for Phase 3**

Record what worked and what crashed in `docs/SESSION.md`.

---

## Phase 4: Real Pack Generation (Forge Integration)

### Task 14: Wire real pack generation lambda

**Files:**
- Modify: `app/main/kotlin/leyline/infra/LeylineServer.kt`

**Step 1: Check forge-web for pack generation API**

Reference: `~/src/forge-web/forge-web/src/main/kotlin/forge/web/game/SealedSessionManager.kt` — uses `UnOpenedProduct` for booster generation.

Find the equivalent Forge API available in matchdoor:

```bash
grep -r "UnOpenedProduct\|generateBooster\|getBoosters" matchdoor/src/
```

**Step 2: Replace stub pool generator with real Forge call**

In `LeylineServer.startLocal()`, replace the TODO stub with a lambda that calls Forge's pack generation. The lambda should:

1. Look up the set's booster template via Forge's card database
2. Generate 6 boosters (sealed = 6 packs)
3. Map each card to its Arena grpId via `cardRepo`
4. Return `GeneratedPool(cards, byCollation, collationId)`

The exact implementation depends on what Forge APIs are accessible from LeylineServer (composition root has access to matchdoor). If direct Forge access isn't available, wire it through matchdoor's bridge.

**Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`

**Step 4: Test with `just serve`**

Join the sealed event in-game. Check that the card pool has real cards (not sequential 1-84).

**Step 5: Commit**

```bash
just fmt
git add app/main/kotlin/leyline/infra/LeylineServer.kt
git commit -m "feat(sealed): wire real Forge pack generation for sealed pools"
```

---

### Task 15: Match result callback

**Files:**
- Modify: `app/main/kotlin/leyline/infra/LeylineServer.kt`
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt` (Event_GetMatchResultReport handler)

**Step 1: Wire match result callback in LeylineServer**

After a match completes in matchdoor, it needs to call back to CourseService to update W/L. Wire a `(PlayerId, String, Boolean) -> Unit` lambda from matchdoor's match completion to `courseService.recordMatchResult()`.

Check how match completion is currently signaled:
```bash
grep -r "matchResult\|onMatchEnd\|matchComplete" matchdoor/src/ app/main/
```

**Step 2: Update Event_GetMatchResultReport handler**

Replace golden stub (line 348-352) with real course state:

```kotlin
CmdType.EVENT_GET_MATCH_RESULT.value -> {
    val req = FdRequests.parseMatchResult(json)
    log.info("Front Door: Event_GetMatchResultReport event={}", req?.eventName)
    if (req?.eventName != null && courseService != null && playerId != null) {
        val course = courseService.getCoursesForPlayer(playerId)
            .firstOrNull { it.eventName == req.eventName }
        if (course != null) {
            writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildCourseJson(course).toString()))
        } else {
            writer.send(ctx, txId, FdResponse.Json(golden.eventMatchResultReportJson))
        }
    } else {
        writer.send(ctx, txId, FdResponse.Json(golden.eventMatchResultReportJson))
    }
}
```

**Step 3: Verify + test**

Run: `./gradlew compileKotlin`
Then `just serve` → join sealed → play a match → verify W/L updates.

**Step 4: Commit**

```bash
just fmt
git add -A
git commit -m "feat(sealed): wire match result callback to CourseService"
```

---

## Phase 5: Conformance

### Task 16: Compare wire output against recording

**Step 1: Extract key response shapes from recording**

```bash
just fd-response Event_Join | jq 'keys' > /tmp/real-join-keys.json
just fd-response Event_SetDeckV2 | jq 'keys' > /tmp/real-setdeck-keys.json
just fd-response Event_GetCoursesV2 | jq '.Courses[0] | keys' > /tmp/real-course-keys.json
```

**Step 2: Start `just serve`, join sealed, capture our responses**

Compare our response shapes against the recording. Look for:
- Missing top-level keys
- Wrong field names (casing matters)
- null vs empty array vs missing
- Field ordering (shouldn't matter for JSON, but verify)

**Step 3: Fix any discrepancies**

Update `EventWireBuilder` methods to match the recording shapes exactly.

**Step 4: Run all frontdoor tests**

Run: `./gradlew :frontdoor:test -x detekt`
Expected: All tests PASS

**Step 5: Commit**

```bash
just fmt
git add -A
git commit -m "fix(sealed): conformance fixes — match recording wire shapes"
```

---

## Post-Implementation

### Task 17: Update catalog + docs

**Files:**
- Modify: `docs/catalog.yaml` — add sealed entry
- Modify: `docs/SESSION.md` — record session notes

**Step 1: Update catalog**

Add sealed format entry to `docs/catalog.yaml` under the appropriate section.

**Step 2: Commit**

```bash
git add docs/catalog.yaml docs/SESSION.md
git commit -m "docs: add sealed format to catalog, update session notes"
```
