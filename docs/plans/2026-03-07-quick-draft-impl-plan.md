# Quick Draft (BotDraft) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add Quick Draft support — player drafts 3 packs x 13 picks against AI bots, builds a 40-card deck, plays Bo1 matches (7 wins / 3 losses). Reuses existing Course infrastructure from sealed.

**Architecture:** New `DraftSession` entity tracks pick-by-pick state (current pack, picks made, available cards). Three new CmdType handlers (1800/1801/1802) in FrontDoorHandler serialize draft state as Course-wrapped double-encoded JSON. CourseService.join() creates both a Course (module=BotDraft, empty pool) and a DraftSession. Pack generation is injected as a lambda (same pattern as sealed pool generation).

**Tech Stack:** Kotlin, kotlinx.serialization, Exposed (SQLite), Kotest FunSpec, Forge BoosterDraft (Phase 3)

**Event:** `QuickDraft_ECL_20260223` (Lorwyn Eclipsed)

**Recording reference:** `recordings/2026-03-07_17-57-22/capture/fd-frames.jsonl`

**Design doc:** `docs/plans/2026-03-07-quick-draft-design.md`

---

## Phase 1: Domain + Golden Stubs

### Task 1: Register BotDraft CmdTypes

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/wire/CmdType.kt`

**Step 1: Add three BotDraft CmdType constants and NAMES entries**

In `CmdType.kt`, add after the `// --- Events ---` section (after line 46):

```kotlin
// --- BotDraft ---
val BOT_DRAFT_START = CmdType(1800)
val BOT_DRAFT_PICK = CmdType(1801)
val BOT_DRAFT_STATUS = CmdType(1802)
```

In the `NAMES` map, add after the `626` entry (after line 129):

```kotlin
1800 to "BotDraft_StartDraft",
1801 to "BotDraft_DraftPick",
1802 to "BotDraft_DraftStatus",
```

**Step 2: Verify it compiles**

Run: `cd /Users/denislebedev/src/leyline && ./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/wire/CmdType.kt
git commit -m "feat: register BotDraft CmdTypes 1800/1801/1802"
```

---

### Task 2: Add BotDraft CourseModule variant

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/domain/Course.kt:18-31`

**Step 1: Add BotDraft to CourseModule enum**

Add `BotDraft` variant between `GrantCardPool` and `DeckSelect`:

```kotlin
enum class CourseModule {
    Join,
    Sealed,
    GrantCardPool,
    BotDraft,
    DeckSelect,
    CreateMatch,
    MatchResults,
    RankUpdate,
    Complete,
    ClaimPrize,
    ;

    fun wireName(): String = name
}
```

**Step 2: Update the KDoc to mention draft lifecycle**

Update lines 5-16:

```kotlin
/**
 * Client-visible course state machine modules.
 *
 * Sealed event lifecycle: `DeckSelect -> CreateMatch <-> MatchResults -> Complete`.
 * Quick Draft lifecycle: `BotDraft -> DeckSelect -> CreateMatch <-> MatchResults -> Complete`.
 * Constructed events skip straight to `CreateMatch`. The remaining variants
 * (`Join`, `Sealed`, `GrantCardPool`, `RankUpdate`, `ClaimPrize`) exist in
 * the real server protocol but are not yet used by our implementation.
 *
 * **Invisible constraint:** the client uses [wireName] to drive UI transitions.
 * `BotDraft` shows the draft pick UI; `DeckSelect` shows the sealed deck builder;
 * `CreateMatch` shows the "Play" button; `Complete` shows the event as finished.
 * Sending the wrong module for the current state will confuse the client UI.
 */
```

**Step 3: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/domain/Course.kt
git commit -m "feat: add BotDraft variant to CourseModule enum"
```

---

### Task 3: QuickDraft EventDef in EventRegistry

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/service/EventRegistry.kt:296-310`

**Step 1: Add QuickDraft EventDef after the Sealed entry**

After the Sealed EventDef (line 309), add:

```kotlin
// Quick Draft
EventDef(
    "QuickDraft_ECL_20260223",
    "Quick Draft ECL",
    "Limited",
    formatType = "BotDraft",
    displayPriority = 70,
    flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards"),
    bladeBehavior = null,
    eventTags = listOf("Draft", "Limited"),
    titleLocKey = "Events/Event_Title_QuickDraft_ECL",
    descLocKey = "Events/Event_Desc_QuickDraft_ECL",
    maxWins = 7,
    maxLosses = 3,
),
```

**Step 2: Add isDraft helper**

After `isSealed()` (line 315), add:

```kotlin
fun isDraft(eventName: String): Boolean = findEvent(eventName)?.formatType == "BotDraft"
```

**Step 3: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/service/EventRegistry.kt
git commit -m "feat: add QuickDraft ECL EventDef + isDraft() helper"
```

---

### Task 4: Extract golden payloads from recording

**Files:**
- Create: `frontdoor/src/main/resources/fd-golden/draft-start.json`
- Create: `frontdoor/src/main/resources/fd-golden/draft-pick.json`
- Create: `frontdoor/src/main/resources/fd-golden/draft-status.json`
- Create: `frontdoor/src/main/resources/fd-golden/draft-join.json`

Extract from `recordings/2026-03-07_17-57-22/capture/fd-frames.jsonl`:

**Step 1: Extract Event_Join response for QuickDraft (line 102, seq 142)**

Save the `jsonPayload` from line 102 (S2C response) as `draft-join.json`. This is the Course-wrapped join response with `CurrentModule: "BotDraft"` and empty `CardPool`.

```bash
cd /Users/denislebedev/src/leyline
sed -n '102p' recordings/2026-03-07_17-57-22/capture/fd-frames.jsonl | python3 -c "
import json, sys
frame = json.loads(sys.stdin.read())
payload = json.loads(frame['jsonPayload'])
print(json.dumps(payload, indent=2))
" > frontdoor/src/main/resources/fd-golden/draft-join.json
```

**Step 2: Extract BotDraft_StartDraft response (line 107, seq 147)**

The StartDraft response is Course-wrapped: `{"CurrentModule":"BotDraft","Payload":"{...}"}`. Save the full response JSON.

```bash
sed -n '107p' recordings/2026-03-07_17-57-22/capture/fd-frames.jsonl | python3 -c "
import json, sys
frame = json.loads(sys.stdin.read())
payload = json.loads(frame['jsonPayload'])
print(json.dumps(payload, indent=2))
" > frontdoor/src/main/resources/fd-golden/draft-start.json
```

**Step 3: Extract first BotDraft_DraftPick response (line 114, seq 166)**

```bash
sed -n '114p' recordings/2026-03-07_17-57-22/capture/fd-frames.jsonl | python3 -c "
import json, sys
frame = json.loads(sys.stdin.read())
payload = json.loads(frame['jsonPayload'])
print(json.dumps(payload, indent=2))
" > frontdoor/src/main/resources/fd-golden/draft-pick.json
```

**Step 4: Extract BotDraft_DraftStatus response (line 110, seq 150)**

```bash
sed -n '110p' recordings/2026-03-07_17-57-22/capture/fd-frames.jsonl | python3 -c "
import json, sys
frame = json.loads(sys.stdin.read())
payload = json.loads(frame['jsonPayload'])
print(json.dumps(payload, indent=2))
" > frontdoor/src/main/resources/fd-golden/draft-status.json
```

**Step 5: Verify all 4 files have valid JSON**

```bash
for f in frontdoor/src/main/resources/fd-golden/draft-*.json; do
    echo -n "$f: "; python3 -c "import json; json.load(open('$f')); print('OK')"
done
```

Expected: All 4 files print OK.

**Step 6: Commit**

```bash
git add frontdoor/src/main/resources/fd-golden/draft-*.json
git commit -m "feat: extract golden BotDraft payloads from recording"
```

---

### Task 5: Load golden BotDraft data in GoldenData

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/GoldenData.kt`

**Step 1: Add 4 new fields to GoldenData**

Add after `sealedCoursesJson` (line 27):

```kotlin
val draftJoinJson: String,
val draftStartJson: String,
val draftPickJson: String,
val draftStatusJson: String,
```

**Step 2: Load them in loadFromClasspath()**

Add after the `sealedCoursesJson` loading (line 51):

```kotlin
draftJoinJson = loadTextResource("fd-golden/draft-join.json"),
draftStartJson = loadTextResource("fd-golden/draft-start.json"),
draftPickJson = loadTextResource("fd-golden/draft-pick.json"),
draftStatusJson = loadTextResource("fd-golden/draft-status.json"),
```

**Step 3: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/GoldenData.kt
git commit -m "feat: load golden BotDraft data in GoldenData"
```

---

### Task 6: Wire golden BotDraft stub handlers in FrontDoorHandler

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt`

**Step 1: Add 3 handler cases in the dispatch `when` block**

Add after the `EVENT_GET_ACTIVE_EVENTS_V2` handler (find the line), before the `else` branch:

```kotlin
CmdType.BOT_DRAFT_START.value -> {
    val req = FdRequests.parseEventName(json)
    log.info("Front Door: BotDraft_StartDraft event={}", req?.eventName)
    writer.send(ctx, txId, FdResponse.Json(golden.draftStartJson))
}

CmdType.BOT_DRAFT_PICK.value -> {
    log.info("Front Door: BotDraft_DraftPick (golden stub)")
    writer.send(ctx, txId, FdResponse.Json(golden.draftPickJson))
}

CmdType.BOT_DRAFT_STATUS.value -> {
    val req = FdRequests.parseEventName(json)
    log.info("Front Door: BotDraft_DraftStatus event={}", req?.eventName)
    writer.send(ctx, txId, FdResponse.Json(golden.draftStatusJson))
}
```

**Step 2: Update Event_Join to handle draft events**

Modify the `EVENT_JOIN` handler (lines 326-342). When `EventRegistry.isDraft(eventName)` and courseService is null, fall back to `golden.draftJoinJson`:

```kotlin
CmdType.EVENT_JOIN.value -> {
    val req = FdRequests.parseEventJoin(json)
    val eventName = req?.eventName
    log.info("Front Door: Event_Join event={}", eventName)
    if (eventName != null && courseService != null && playerId != null) {
        val course = courseService.join(playerId, eventName)
        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildJoinResponse(course)))
    } else {
        val golden = when {
            EventRegistry.isDraft(eventName ?: "") -> golden.draftJoinJson
            EventRegistry.isSealed(eventName ?: "") -> golden.sealedJoinJson
            else -> golden.eventJoinJson
        }
        writer.send(ctx, txId, FdResponse.Json(golden))
    }
}
```

**Step 3: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt
git commit -m "feat: wire golden BotDraft stub handlers (1800/1801/1802)"
```

---

### Task 7: Golden playtest — just serve + draft UI loads

**Gate:** `just serve` -> client joins Quick Draft -> draft UI appears with 13 cards -> picking does something (even if it returns the same golden pack every time).

**Step 1: Start the server**

```bash
just serve
```

**Step 2: Launch Arena client and navigate to Quick Draft**

Follow `docs/arena-nav.md` Quick Draft Loop section.

**Step 3: Verify draft UI loads after clicking the event and joining**

- Event_Join should return the golden draft-join.json (module=BotDraft, empty pool)
- Client should show draft UI
- BotDraft_StartDraft (1800) should return first pack with 13 cards
- Clicking a card and confirming should trigger BotDraft_DraftPick (1801)

**Step 4: Check server logs**

```bash
grep -i "BotDraft\|DraftPick\|StartDraft" logs/leyline.log | tail -10
```

Expected: Handler log messages for 1800/1801/1802 appear.

**No commit — this is a verification gate only.**

---

## Phase 2: Domain + Real Handlers

### Task 8: DraftSession domain model

**Files:**
- Create: `frontdoor/src/main/kotlin/leyline/frontdoor/domain/DraftSession.kt`

**Step 1: Write the DraftSession entity**

```kotlin
package leyline.frontdoor.domain

/**
 * Draft session state — tracks pick-by-pick progress through 3 packs.
 *
 * Quick Draft lifecycle: `PickNext -> ... -> Completed`.
 * Each pick removes a card from [draftPack] and adds it to [pickedCards].
 * When all 39 picks are made (3 packs x 13 picks), status becomes Completed.
 *
 * **Wire format:** BotDraft responses are Course-wrapped double-encoded JSON:
 * `{"CurrentModule":"BotDraft","Payload":"{\"Result\":\"Success\",...}"}`
 * The Payload is a JSON string containing the draft state fields.
 */

@JvmInline value class DraftSessionId(val value: String)

enum class DraftStatus {
    PickNext,
    Completed,
    ;

    fun wireName(): String = name
}

data class DraftSession(
    val id: DraftSessionId,
    val playerId: PlayerId,
    val eventName: String,
    val status: DraftStatus = DraftStatus.PickNext,
    val packNumber: Int = 0,
    val pickNumber: Int = 0,
    /** Cards available in the current pack for picking. */
    val draftPack: List<Int> = emptyList(),
    /** All packs for the draft session (3 packs x up to 13 cards each, pre-generated). */
    val packs: List<List<Int>> = emptyList(),
    /** Cards picked so far (cumulative). */
    val pickedCards: List<Int> = emptyList(),
)
```

**Step 2: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/domain/DraftSession.kt
git commit -m "feat: DraftSession domain model"
```

---

### Task 9: DraftSessionRepository interface + InMemory test double

**Files:**
- Create: `frontdoor/src/main/kotlin/leyline/frontdoor/repo/DraftSessionRepository.kt`
- Create: `frontdoor/src/test/kotlin/leyline/frontdoor/repo/InMemoryDraftSessionRepository.kt`

**Step 1: Write the repository interface**

```kotlin
package leyline.frontdoor.repo

import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.PlayerId

interface DraftSessionRepository {
    fun findById(id: DraftSessionId): DraftSession?
    fun findByPlayerAndEvent(playerId: PlayerId, eventName: String): DraftSession?
    fun save(session: DraftSession)
    fun delete(id: DraftSessionId)
}
```

**Step 2: Write the in-memory test double**

```kotlin
package leyline.frontdoor.repo

import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.PlayerId

class InMemoryDraftSessionRepository : DraftSessionRepository {
    private val store = mutableMapOf<DraftSessionId, DraftSession>()

    override fun findById(id: DraftSessionId): DraftSession? = store[id]

    override fun findByPlayerAndEvent(playerId: PlayerId, eventName: String): DraftSession? =
        store.values.find { it.playerId == playerId && it.eventName == eventName }

    override fun save(session: DraftSession) {
        store[session.id] = session
    }

    override fun delete(id: DraftSessionId) {
        store.remove(id)
    }
}
```

**Step 3: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin :frontdoor:compileTestKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/repo/DraftSessionRepository.kt \
       frontdoor/src/test/kotlin/leyline/frontdoor/repo/InMemoryDraftSessionRepository.kt
git commit -m "feat: DraftSessionRepository interface + InMemory test double"
```

---

### Task 10: DraftService with unit tests

**Files:**
- Create: `frontdoor/src/main/kotlin/leyline/frontdoor/service/DraftService.kt`
- Create: `frontdoor/src/test/kotlin/leyline/frontdoor/service/DraftServiceTest.kt`

**Step 1: Write the failing tests first**

```kotlin
package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.frontdoor.domain.DraftStatus
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.InMemoryDraftSessionRepository

class DraftServiceTest : FunSpec({

    val playerId = PlayerId("test-player")
    val eventName = "QuickDraft_ECL_20260223"

    fun createService(): DraftService {
        val repo = InMemoryDraftSessionRepository()
        // Generate 3 packs of 13 cards each (simple sequential grpIds for testing)
        val generatePacks: (String) -> List<List<Int>> = { _ ->
            (0 until 3).map { pack ->
                (1..13).map { card -> 90000 + pack * 100 + card }
            }
        }
        return DraftService(repo, generatePacks)
    }

    test("startDraft creates session with first pack of 13 cards") {
        val service = createService()
        val session = service.startDraft(playerId, eventName)

        session.status shouldBe DraftStatus.PickNext
        session.packNumber shouldBe 0
        session.pickNumber shouldBe 0
        session.draftPack shouldHaveSize 13
        session.pickedCards shouldHaveSize 0
        session.packs shouldHaveSize 3
    }

    test("startDraft returns existing session if already started") {
        val service = createService()
        val first = service.startDraft(playerId, eventName)
        val second = service.startDraft(playerId, eventName)
        first.id shouldBe second.id
    }

    test("pick removes card from pack and adds to pickedCards") {
        val service = createService()
        val session = service.startDraft(playerId, eventName)
        val cardToPick = session.draftPack.first()

        val after = service.pick(playerId, eventName, cardToPick, packNumber = 0, pickNumber = 0)

        after.pickNumber shouldBe 1
        after.packNumber shouldBe 0
        after.draftPack shouldHaveSize 12
        after.pickedCards shouldBe listOf(cardToPick)
        after.status shouldBe DraftStatus.PickNext
    }

    test("picking all 13 cards in pack 0 advances to pack 1") {
        val service = createService()
        var session = service.startDraft(playerId, eventName)

        // Pick all 13 cards from pack 0
        for (i in 0 until 13) {
            val card = session.draftPack.first()
            session = service.pick(playerId, eventName, card, packNumber = session.packNumber, pickNumber = session.pickNumber)
        }

        session.packNumber shouldBe 1
        session.pickNumber shouldBe 0
        session.draftPack shouldHaveSize 13  // fresh pack 1
        session.pickedCards shouldHaveSize 13
    }

    test("picking all 39 cards completes draft") {
        val service = createService()
        var session = service.startDraft(playerId, eventName)

        // Pick all 39 cards (3 packs x 13)
        for (i in 0 until 39) {
            val card = session.draftPack.first()
            session = service.pick(playerId, eventName, card, packNumber = session.packNumber, pickNumber = session.pickNumber)
        }

        session.status shouldBe DraftStatus.Completed
        session.pickedCards shouldHaveSize 39
        session.draftPack shouldHaveSize 0
        session.packNumber shouldBe 2
        session.pickNumber shouldBe 13
    }

    test("getStatus returns current session state") {
        val service = createService()
        service.startDraft(playerId, eventName)

        val status = service.getStatus(playerId, eventName)
        status shouldNotBe null
        status!!.status shouldBe DraftStatus.PickNext
        status.draftPack shouldHaveSize 13
    }

    test("getStatus returns null for non-existent session") {
        val service = createService()
        service.getStatus(playerId, eventName) shouldBe null
    }
})
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :frontdoor:test --tests "leyline.frontdoor.service.DraftServiceTest" --info 2>&1 | tail -20`
Expected: FAIL — DraftService class doesn't exist yet.

**Step 3: Write minimal DraftService implementation**

```kotlin
package leyline.frontdoor.service

import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.DraftStatus
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.DraftSessionRepository
import java.util.UUID

/**
 * Manages BotDraft session lifecycle — start, pick, status.
 *
 * Lives in frontdoor alongside CourseService. Pack generation is injected
 * as a lambda to keep Forge engine dependencies out of this module.
 *
 * **Wire contract:** Quick Draft uses 3 packs x 13 picks = 39 total picks.
 * Each pick shrinks the current pack by 1. When a pack is exhausted,
 * the next pack loads. After 39 picks, status becomes Completed and the
 * Course transitions from BotDraft to DeckSelect.
 */
class DraftService(
    private val repo: DraftSessionRepository,
    private val generatePacks: (setCode: String) -> List<List<Int>>,
) {
    companion object {
        const val CARDS_PER_PACK = 13
        const val TOTAL_PACKS = 3
    }

    fun startDraft(playerId: PlayerId, eventName: String): DraftSession {
        repo.findByPlayerAndEvent(playerId, eventName)?.let { return it }

        val packs = generatePacks(extractSetCode(eventName))
        val session = DraftSession(
            id = DraftSessionId(UUID.randomUUID().toString()),
            playerId = playerId,
            eventName = eventName,
            status = DraftStatus.PickNext,
            packNumber = 0,
            pickNumber = 0,
            draftPack = packs[0],
            packs = packs,
            pickedCards = emptyList(),
        )
        repo.save(session)
        return session
    }

    fun pick(
        playerId: PlayerId,
        eventName: String,
        cardId: Int,
        packNumber: Int,
        pickNumber: Int,
    ): DraftSession {
        val session = repo.findByPlayerAndEvent(playerId, eventName)
            ?: throw IllegalArgumentException("No draft session for $eventName")

        require(session.status == DraftStatus.PickNext) { "Draft already completed" }
        require(cardId in session.draftPack) { "Card $cardId not in current pack" }

        val newPickedCards = session.pickedCards + cardId
        val remainingPack = session.draftPack - cardId

        val totalPicks = newPickedCards.size
        val completed = totalPicks >= TOTAL_PACKS * CARDS_PER_PACK

        val (nextPack, nextPackNumber, nextPickNumber, nextDraftPack) = if (completed) {
            // Draft done
            Triple(session.packs, session.packNumber, totalPicks % CARDS_PER_PACK).let {
                Quad(session.packs, 2, CARDS_PER_PACK, emptyList())
            }
        } else if (remainingPack.isEmpty()) {
            // Pack exhausted, move to next
            val nextPN = session.packNumber + 1
            Quad(session.packs, nextPN, 0, session.packs[nextPN])
        } else {
            Quad(session.packs, session.packNumber, session.pickNumber + 1, remainingPack)
        }

        val updated = session.copy(
            status = if (completed) DraftStatus.Completed else DraftStatus.PickNext,
            packNumber = nextPackNumber,
            pickNumber = nextPickNumber,
            draftPack = nextDraftPack,
            pickedCards = newPickedCards,
        )
        repo.save(updated)
        return updated
    }

    fun getStatus(playerId: PlayerId, eventName: String): DraftSession? =
        repo.findByPlayerAndEvent(playerId, eventName)

    private fun extractSetCode(eventName: String): String {
        // QuickDraft_ECL_20260223 -> ECL
        val parts = eventName.split("_")
        return if (parts.size >= 2 && parts[0].equals("QuickDraft", ignoreCase = true)) {
            parts[1]
        } else {
            "FDN"
        }
    }

    /** Destructuring helper for 4-element tuple. */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :frontdoor:test --tests "leyline.frontdoor.service.DraftServiceTest" --info 2>&1 | tail -20`
Expected: All 6 tests PASS.

**Step 5: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/service/DraftService.kt \
       frontdoor/src/test/kotlin/leyline/frontdoor/service/DraftServiceTest.kt
git commit -m "feat: DraftService with unit tests (start, pick, status, completion)"
```

---

### Task 11: DraftWireBuilder — Course-wrapped double-encoded JSON

**Files:**
- Create: `frontdoor/src/main/kotlin/leyline/frontdoor/wire/DraftWireBuilder.kt`
- Create: `frontdoor/src/test/kotlin/leyline/frontdoor/wire/DraftWireBuilderTest.kt`

**Step 1: Write the failing test**

```kotlin
package leyline.frontdoor.wire

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.DraftStatus
import leyline.frontdoor.domain.PlayerId

class DraftWireBuilderTest : FunSpec({

    val session = DraftSession(
        id = DraftSessionId("test-id"),
        playerId = PlayerId("test-player"),
        eventName = "QuickDraft_ECL_20260223",
        status = DraftStatus.PickNext,
        packNumber = 0,
        pickNumber = 0,
        draftPack = listOf(98353, 98519, 98532),
        packs = emptyList(),
        pickedCards = emptyList(),
    )

    test("buildDraftResponse wraps payload in Course-style double-encoded JSON") {
        val json = DraftWireBuilder.buildDraftResponse(session)
        val outer = Json.parseToJsonElement(json).jsonObject

        // Outer level: CurrentModule
        outer["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"

        // Payload is a JSON string (double-encoded)
        val payloadStr = outer["Payload"]?.jsonPrimitive?.content ?: error("no Payload")
        val inner = Json.parseToJsonElement(payloadStr).jsonObject

        inner["Result"]?.jsonPrimitive?.content shouldBe "Success"
        inner["EventName"]?.jsonPrimitive?.content shouldBe "QuickDraft_ECL_20260223"
        inner["DraftStatus"]?.jsonPrimitive?.content shouldBe "PickNext"
        inner["PackNumber"]?.jsonPrimitive?.content shouldBe "0"
        inner["PickNumber"]?.jsonPrimitive?.content shouldBe "0"
        inner["NumCardsToPick"]?.jsonPrimitive?.content shouldBe "1"
    }

    test("DraftPack contains string grpIds") {
        val json = DraftWireBuilder.buildDraftResponse(session)
        val outer = Json.parseToJsonElement(json).jsonObject
        val payloadStr = outer["Payload"]!!.jsonPrimitive.content
        // grpIds are strings in the payload
        payloadStr shouldContain "\"98353\""
        payloadStr shouldContain "\"98519\""
    }

    test("completed draft has DeckSelect module and empty pack") {
        val completed = session.copy(
            status = DraftStatus.Completed,
            draftPack = emptyList(),
            pickedCards = listOf(98353, 98519),
        )
        val json = DraftWireBuilder.buildDraftResponse(completed)
        val outer = Json.parseToJsonElement(json).jsonObject

        // Module switches to DeckSelect on completion
        outer["CurrentModule"]?.jsonPrimitive?.content shouldBe "DeckSelect"

        val payloadStr = outer["Payload"]!!.jsonPrimitive.content
        val inner = Json.parseToJsonElement(payloadStr).jsonObject
        inner["DraftStatus"]?.jsonPrimitive?.content shouldBe "Completed"
    }
})
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :frontdoor:test --tests "leyline.frontdoor.wire.DraftWireBuilderTest" --info 2>&1 | tail -20`
Expected: FAIL — DraftWireBuilder doesn't exist.

**Step 3: Write DraftWireBuilder**

```kotlin
package leyline.frontdoor.wire

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftStatus

/**
 * Serializes BotDraft responses as Course-wrapped double-encoded JSON.
 *
 * Wire format: `{"CurrentModule":"BotDraft","Payload":"{\"Result\":\"Success\",...}"}`
 * The Payload field is a JSON string containing the actual draft state.
 * On completion, CurrentModule switches to "DeckSelect".
 *
 * Reference: `recordings/2026-03-07_17-57-22/capture/fd-frames.jsonl` lines 107, 114, 190.
 */
object DraftWireBuilder {

    fun buildDraftResponse(session: DraftSession): String {
        val payload = buildPayloadJson(session)
        val module = if (session.status == DraftStatus.Completed) "DeckSelect" else "BotDraft"
        return buildJsonObject {
            put("CurrentModule", module)
            put("Payload", payload)
        }.toString()
    }

    private fun buildPayloadJson(session: DraftSession): String = buildJsonObject {
        put("Result", "Success")
        put("EventName", session.eventName)
        put("DraftStatus", session.status.wireName())
        put("PackNumber", session.packNumber)
        put("PickNumber", session.pickNumber)
        put("NumCardsToPick", 1)
        put("DraftPack", buildJsonArray {
            session.draftPack.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toString())) }
        })
        put("PackStyles", buildJsonArray {})
        put("PickedCards", buildJsonArray {
            session.pickedCards.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toString())) }
        })
        put("PickedStyles", buildJsonArray {})
    }.toString()
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :frontdoor:test --tests "leyline.frontdoor.wire.DraftWireBuilderTest" --info 2>&1 | tail -20`
Expected: All 3 tests PASS.

**Step 5: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/wire/DraftWireBuilder.kt \
       frontdoor/src/test/kotlin/leyline/frontdoor/wire/DraftWireBuilderTest.kt
git commit -m "feat: DraftWireBuilder — Course-wrapped double-encoded draft responses"
```

---

### Task 12: Parse BotDraft requests in FdRequests

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/wire/FdRequests.kt`

**Step 1: Add DraftPick request data class and parser**

Add after existing parse functions:

```kotlin
data class DraftPick(
    val eventName: String,
    val cardId: Int,
    val packNumber: Int,
    val pickNumber: Int,
)

fun parseDraftPick(json: String?): DraftPick? = parseJson(json) { obj ->
    val eventName = obj["EventName"]?.jsonPrimitive?.content ?: return@parseJson null
    val pickInfo = obj["PickInfo"]?.jsonObject ?: return@parseJson null
    val cardIds = pickInfo["CardIds"]?.jsonArray ?: return@parseJson null
    val cardId = cardIds.firstOrNull()?.jsonPrimitive?.content?.toIntOrNull() ?: return@parseJson null
    val packNumber = pickInfo["PackNumber"]?.jsonPrimitive?.int ?: return@parseJson null
    val pickNumber = pickInfo["PickNumber"]?.jsonPrimitive?.int ?: return@parseJson null
    DraftPick(eventName, cardId, packNumber, pickNumber)
}
```

Note: You'll need to add `jsonArray` import: `import kotlinx.serialization.json.jsonArray` and `import kotlinx.serialization.json.int`.

Check existing imports in the file — `jsonArray` and `int` may already be imported. If not, add them.

**Step 2: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/wire/FdRequests.kt
git commit -m "feat: parse BotDraft_DraftPick request (eventName, cardId, pack/pick)"
```

---

### Task 13: Replace golden stubs with real DraftService handlers

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt`

**Step 1: Add DraftService as optional constructor parameter**

After `courseService` parameter (line 54), add:

```kotlin
private val draftService: DraftService? = null,
```

Add import:
```kotlin
import leyline.frontdoor.wire.DraftWireBuilder
```

**Step 2: Replace the golden stub handlers with real logic**

Replace the 3 BotDraft handlers from Task 6 with:

```kotlin
CmdType.BOT_DRAFT_START.value -> {
    val req = FdRequests.parseEventName(json)
    val eventName = req?.eventName
    log.info("Front Door: BotDraft_StartDraft event={}", eventName)
    if (eventName != null && draftService != null && playerId != null) {
        val session = draftService.startDraft(playerId, eventName)
        writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
    } else {
        writer.send(ctx, txId, FdResponse.Json(golden.draftStartJson))
    }
}

CmdType.BOT_DRAFT_PICK.value -> {
    val req = FdRequests.parseDraftPick(json)
    log.info("Front Door: BotDraft_DraftPick card={} pack={} pick={}", req?.cardId, req?.packNumber, req?.pickNumber)
    if (req != null && draftService != null && playerId != null) {
        val session = draftService.pick(playerId, req.eventName, req.cardId, req.packNumber, req.pickNumber)
        // On completion, transition Course from BotDraft to DeckSelect with card pool
        if (session.status == leyline.frontdoor.domain.DraftStatus.Completed && courseService != null) {
            courseService.completeDraft(playerId, req.eventName, session.pickedCards)
        }
        writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
    } else {
        writer.send(ctx, txId, FdResponse.Json(golden.draftPickJson))
    }
}

CmdType.BOT_DRAFT_STATUS.value -> {
    val req = FdRequests.parseEventName(json)
    val eventName = req?.eventName
    log.info("Front Door: BotDraft_DraftStatus event={}", eventName)
    if (eventName != null && draftService != null && playerId != null) {
        val session = draftService.getStatus(playerId, eventName)
        if (session != null) {
            writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
        } else {
            writer.send(ctx, txId, FdResponse.Json(golden.draftStatusJson))
        }
    } else {
        writer.send(ctx, txId, FdResponse.Json(golden.draftStatusJson))
    }
}
```

**Step 3: Update Event_Join for draft events with courseService**

In the `EVENT_JOIN` handler, add a branch for draft events when courseService is available. The join should create a Course with module=BotDraft and empty cardPool:

Inside the `if (eventName != null && courseService != null && playerId != null)` block, the existing `courseService.join()` needs to handle draft events. This is wired in Task 14.

**Step 4: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL (may need to add `completeDraft` to CourseService — see Task 14)

**Step 5: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt
git commit -m "feat: replace golden BotDraft stubs with real DraftService handlers"
```

---

### Task 14: CourseService draft support — join + completeDraft

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/service/CourseService.kt`
- Modify: `frontdoor/src/test/kotlin/leyline/frontdoor/service/CourseServiceTest.kt`

**Step 1: Write failing tests for draft course lifecycle**

Add to `CourseServiceTest.kt`:

```kotlin
test("join for draft event creates course with BotDraft module and empty pool") {
    val course = courseService.join(playerId, "QuickDraft_ECL_20260223")
    course.module shouldBe CourseModule.BotDraft
    course.cardPool shouldBe emptyList()
}

test("completeDraft transitions course to DeckSelect with card pool") {
    courseService.join(playerId, "QuickDraft_ECL_20260223")
    val pickedCards = listOf(98353, 98519, 98350)
    val course = courseService.completeDraft(playerId, "QuickDraft_ECL_20260223", pickedCards)
    course.module shouldBe CourseModule.DeckSelect
    course.cardPool shouldBe pickedCards
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :frontdoor:test --tests "leyline.frontdoor.service.CourseServiceTest" --info 2>&1 | tail -20`
Expected: FAIL — draft branches don't exist yet.

**Step 3: Modify CourseService.join() to handle draft events**

In `CourseService.join()`, add a draft branch after the sealed branch (line 59):

```kotlin
val course = if (EventRegistry.isSealed(eventName)) {
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
} else if (EventRegistry.isDraft(eventName)) {
    Course(
        id = CourseId(UUID.randomUUID().toString()),
        playerId = playerId,
        eventName = eventName,
        module = CourseModule.BotDraft,
    )
} else {
    Course(
        id = CourseId(UUID.randomUUID().toString()),
        playerId = playerId,
        eventName = eventName,
        module = CourseModule.CreateMatch,
    )
}
```

**Step 4: Add completeDraft method**

```kotlin
fun completeDraft(playerId: PlayerId, eventName: String, pickedCards: List<Int>): Course {
    val course = repo.findByPlayerAndEvent(playerId, eventName)
        ?: throw IllegalArgumentException("No course for $eventName")
    val updated = course.copy(
        module = CourseModule.DeckSelect,
        cardPool = pickedCards,
        cardPoolByCollation = listOf(CollationPool(0, pickedCards)),
    )
    repo.save(updated)
    return updated
}
```

**Step 5: Run tests to verify they pass**

Run: `./gradlew :frontdoor:test --tests "leyline.frontdoor.service.CourseServiceTest" --info 2>&1 | tail -20`
Expected: All tests PASS.

**Step 6: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/service/CourseService.kt \
       frontdoor/src/test/kotlin/leyline/frontdoor/service/CourseServiceTest.kt
git commit -m "feat: CourseService draft support — join(BotDraft) + completeDraft()"
```

---

### Task 15: SQLite persistence for DraftSession

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/repo/SqlitePlayerStore.kt`

**Step 1: Add DraftSessions table definition**

After the `Courses` table object, add:

```kotlin
private object DraftSessions : Table("draft_sessions") {
    val id = text("id")
    val playerId = text("player_id")
    val eventName = text("event_name")
    val status = text("status")
    val packNumber = integer("pack_number").default(0)
    val pickNumber = integer("pick_number").default(0)
    val draftPack = text("draft_pack").default("[]")
    val packs = text("packs").default("[]")
    val pickedCards = text("picked_cards").default("[]")
    override val primaryKey = PrimaryKey(id)
}
```

**Step 2: Add createTables for DraftSessions**

In `createTables()`, add: `SchemaUtils.create(DraftSessions)` alongside `SchemaUtils.create(Courses)`.

**Step 3: Implement DraftSessionRepository**

Add a nested class or methods implementing `DraftSessionRepository` in `SqlitePlayerStore`, following the same pattern as the Course repository:

```kotlin
// --- DraftSession repository ---

fun findDraftById(id: DraftSessionId): DraftSession? = transaction(db) {
    DraftSessions.selectAll().where { DraftSessions.id eq id.value }
        .firstOrNull()?.toDraftSession()
}

fun findDraftByPlayerAndEvent(playerId: PlayerId, eventName: String): DraftSession? = transaction(db) {
    DraftSessions.selectAll().where {
        (DraftSessions.playerId eq playerId.value) and (DraftSessions.eventName eq eventName)
    }.firstOrNull()?.toDraftSession()
}

fun saveDraft(session: DraftSession): Unit = transaction(db) {
    val packsJson = json.encodeToString(session.packs)
    val draftPackJson = json.encodeToString(session.draftPack)
    val pickedJson = json.encodeToString(session.pickedCards)

    val exists = DraftSessions.selectAll().where { DraftSessions.id eq session.id.value }.count() > 0
    if (exists) {
        DraftSessions.update({ DraftSessions.id eq session.id.value }) {
            it[status] = session.status.name
            it[packNumber] = session.packNumber
            it[pickNumber] = session.pickNumber
            it[draftPack] = draftPackJson
            it[packs] = packsJson
            it[pickedCards] = pickedJson
        }
    } else {
        DraftSessions.insert {
            it[id] = session.id.value
            it[playerId] = session.playerId.value
            it[eventName] = session.eventName
            it[status] = session.status.name
            it[this.packNumber] = session.packNumber
            it[this.pickNumber] = session.pickNumber
            it[this.draftPack] = draftPackJson
            it[this.packs] = packsJson
            it[this.pickedCards] = pickedJson
        }
    }
}

fun deleteDraft(id: DraftSessionId): Unit = transaction(db) {
    DraftSessions.deleteWhere { DraftSessions.id eq id.value }
}

private fun ResultRow.toDraftSession(): DraftSession = DraftSession(
    id = DraftSessionId(this[DraftSessions.id]),
    playerId = PlayerId(this[DraftSessions.playerId]),
    eventName = this[DraftSessions.eventName],
    status = DraftStatus.valueOf(this[DraftSessions.status]),
    packNumber = this[DraftSessions.packNumber],
    pickNumber = this[DraftSessions.pickNumber],
    draftPack = json.decodeFromString(this[DraftSessions.draftPack]),
    packs = json.decodeFromString(this[DraftSessions.packs]),
    pickedCards = json.decodeFromString(this[DraftSessions.pickedCards]),
)
```

**Step 4: Create an adapter class that implements DraftSessionRepository**

```kotlin
fun asDraftSessionRepository(): DraftSessionRepository = object : DraftSessionRepository {
    override fun findById(id: DraftSessionId) = findDraftById(id)
    override fun findByPlayerAndEvent(playerId: PlayerId, eventName: String) = findDraftByPlayerAndEvent(playerId, eventName)
    override fun save(session: DraftSession) = saveDraft(session)
    override fun delete(id: DraftSessionId) = deleteDraft(id)
}
```

**Step 5: Verify it compiles**

Run: `./gradlew :frontdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add frontdoor/src/main/kotlin/leyline/frontdoor/repo/SqlitePlayerStore.kt
git commit -m "feat: SQLite persistence for DraftSession"
```

---

### Task 16: Wire DraftService in LeylineServer

**Files:**
- Modify: `app/src/main/kotlin/leyline/infra/LeylineServer.kt`

**Step 1: Find where CourseService is created and wire DraftService alongside it**

Look for where `CourseService` is constructed (around line 174-182). After it, create `DraftService`:

```kotlin
val draftRepo = store.asDraftSessionRepository()
val draftService = DraftService(draftRepo) { setCode ->
    // Phase 2: placeholder packs (3 x 13 random grpIds from the set)
    // Phase 3: real Forge BoosterDraft packs
    (0 until 3).map { (1..13).map { 90000 + it } }  // placeholder
}
```

**Step 2: Pass draftService to FrontDoorHandler**

Find where `FrontDoorHandler` is constructed and add `draftService = draftService`.

**Step 3: Verify it compiles**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/leyline/infra/LeylineServer.kt
git commit -m "feat: wire DraftService in LeylineServer (placeholder packs)"
```

---

### Task 17: Smoke test — just serve + full draft flow

**Gate:** `just serve` -> client joins Quick Draft -> draft UI loads with 13 cards (placeholder) -> pick all 39 cards -> deck builder opens with 39 cards in pool -> can build and submit deck -> match starts.

**Step 1: Start server and navigate to Quick Draft**

```bash
just serve
```

**Step 2: Join Quick Draft event in Arena client**

Follow `docs/arena-nav.md` Quick Draft Loop.

**Step 3: Complete the draft**

Pick 39 cards through 3 packs. Each pick should show a shrinking pack and growing picked cards list.

**Step 4: Verify deck builder opens**

After pick 39, course module should switch to DeckSelect. Client shows deck builder with 39 cards.

**Step 5: Build and submit deck, start match**

Submit a 40-card deck (39 drafted + basics). Match should start normally (reuses sealed match flow).

**Step 6: Check server logs**

```bash
grep -i "BotDraft\|DraftPick\|DraftStatus\|completeDraft" logs/leyline.log | tail -20
```

**No commit — verification gate.**

---

## Phase 3: Forge Integration

### Task 18: Wire real Forge pack generation

**Files:**
- Modify: `app/src/main/kotlin/leyline/infra/LeylineServer.kt`
- May need: `matchdoor/src/main/kotlin/leyline/game/` — new DraftPackGenerator or extend SealedPoolGenerator

**Step 1: Investigate Forge BoosterDraft API**

Read: `forge/forge-gui/src/main/java/forge/gamemodes/limited/BoosterDraft.java`

The key interface: `BoosterDraft` generates 8-player pod with AI picks and pack passing. We need to extract the human player's 3 packs (each starting at 13-14 cards, shrinking as bots pick).

**Step 2: Create DraftPackGenerator**

Create a class in matchdoor (similar to `SealedPoolGenerator`) that:
- Creates an 8-player BoosterDraft for the given set
- Simulates AI picks for all 7 bots
- Returns the 3 packs that the human player would see (after bot picks)
- Maps Forge card names to Arena grpIds via CardRepository

The exact implementation depends on Forge's BoosterDraft API — read the source and adapt.

**Step 3: Wire the real generator into DraftService lambda**

Replace the placeholder lambda in LeylineServer with:

```kotlin
val draftPackGen = DraftPackGenerator(cardRepo)
val draftService = DraftService(draftRepo) { setCode ->
    draftPackGen.generate(setCode)
}
```

**Step 4: Verify real cards appear**

```bash
just serve
# Join Quick Draft, verify cards are real ECL cards (not placeholder 90000s)
```

**Step 5: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/DraftPackGenerator.kt \
       app/src/main/kotlin/leyline/infra/LeylineServer.kt
git commit -m "feat: wire real Forge BoosterDraft pack generation for Quick Draft"
```

---

### Task 19: CollationId for ECL set

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/SealedPoolGenerator.kt` (or wherever COLLATION_IDS lives)

**Step 1: Find the collation ID map and add ECL**

Look for `COLLATION_IDS` in SealedPoolGenerator. Add the ECL collation ID (check from recording — the Event_Join response contains InventoryInfo with Boosters listing CollationIds for ECL).

From the recording (line 102), ECL's collation ID is visible in the InventoryInfo. Find it and add to the map.

**Step 2: Update CourseService.completeDraft to use real collationId**

The `completeDraft` method currently uses `CollationPool(0, pickedCards)`. Wire it to use the real ECL collation ID.

**Step 3: Verify**

Run: `./gradlew :frontdoor:test :matchdoor:test --info 2>&1 | tail -20`
Expected: All tests PASS.

**Step 4: Commit**

```bash
git commit -am "feat: add ECL collation ID for draft card pool"
```

---

## Phase 4: Polish + Conformance

### Task 20: Conformance — compare wire output against recording

**Files:**
- Create: `frontdoor/src/test/kotlin/leyline/frontdoor/wire/DraftWireConformanceTest.kt` (optional)

**Step 1: Compare key fields between our output and the recording**

For BotDraft_StartDraft response:
- Our output: `DraftWireBuilder.buildDraftResponse(session)`
- Recording: line 107 of `fd-frames.jsonl`

Check: CurrentModule, Payload structure, DraftPack as string array, Result, DraftStatus, PackNumber, PickNumber, NumCardsToPick.

For BotDraft_DraftPick response:
- Our output vs recording line 114
- Check: pack shrinks by 1, PickedCards grows by 1, PickNumber increments

For completion:
- Our output vs recording line 190
- Check: CurrentModule switches to DeckSelect, DraftStatus=Completed, DraftPack=[]

**Step 2: Fix any field differences found**

**Step 3: Commit**

```bash
git commit -am "fix: conformance fixes for BotDraft wire format"
```

---

### Task 21: Update catalog + docs

**Files:**
- Modify: `docs/catalog.yaml`
- Modify: `docs/rosetta.md`

**Step 1: Add Quick Draft to catalog**

Add entry under the appropriate section noting BotDraft support status.

**Step 2: Add CmdTypes 1800/1801/1802 to rosetta table**

Add BotDraft_StartDraft, BotDraft_DraftPick, BotDraft_DraftStatus to the CmdType reference.

**Step 3: Commit**

```bash
git commit -am "docs: add Quick Draft to catalog + rosetta"
```

---

## Task Dependency Graph

```
Task 1 (CmdTypes) ─┐
Task 2 (Module)  ───┤
Task 3 (EventDef) ──┼── Task 4 (goldens) ── Task 5 (GoldenData) ── Task 6 (stub handlers) ── Task 7 (golden playtest)
                    │
Task 8 (domain) ────┼── Task 9 (repo) ── Task 10 (DraftService) ── Task 11 (WireBuilder)
                    │                                                    │
                    │   Task 12 (parse) ─────────────────────────────────┤
                    │                                                    │
                    └── Task 14 (CourseService draft) ── Task 13 (real handlers) ── Task 15 (SQLite)
                                                                                       │
                                                                        Task 16 (wiring) ── Task 17 (smoke test)
                                                                                                     │
                                                                        Task 18 (Forge packs) ── Task 19 (collation)
                                                                                                     │
                                                                        Task 20 (conformance) ── Task 21 (docs)
```

**Parallelizable:** Tasks 1-3 can run in parallel. Tasks 8-9 can start while Tasks 4-6 are in progress.
