package leyline.infra

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.FdTag
import leyline.frontdoor.domain.CollationPool
import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.CourseId
import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.DraftStatus
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.CourseRepository
import leyline.frontdoor.repo.DeckRepository
import leyline.frontdoor.repo.DraftSessionRepository
import leyline.frontdoor.service.CourseService
import leyline.frontdoor.service.DeckService
import leyline.frontdoor.service.GeneratedPool

private class FakeDraftRepo : DraftSessionRepository {
    private val sessions = mutableMapOf<DraftSessionId, DraftSession>()
    private val pods = mutableMapOf<DraftSessionId, List<List<Int>>>()

    override fun findById(id: DraftSessionId) = sessions[id]

    override fun findByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ) = sessions.values.firstOrNull { it.playerId == playerId && it.eventName == eventName }

    override fun save(session: DraftSession) {
        sessions[session.id] = session
    }

    override fun delete(id: DraftSessionId) {
        sessions.remove(id)
        pods.remove(id)
    }

    override fun deleteIncomplete() {
        sessions.values
            .filter { it.status != DraftStatus.Completed }
            .map { it.id }
            .forEach {
                sessions.remove(it)
                pods.remove(it)
            }
    }

    override fun savePodResults(
        sessionId: DraftSessionId,
        botDecks: List<List<Int>>,
    ) {
        pods[sessionId] = botDecks.map { it.toList() }
    }

    override fun findPodResults(sessionId: DraftSessionId): List<List<Int>> = pods[sessionId] ?: emptyList()
}

private class FakeCourseRepo : CourseRepository {
    private val courses = mutableMapOf<CourseId, Course>()

    override fun findById(id: CourseId) = courses[id]

    override fun findByPlayer(playerId: PlayerId) = courses.values.filter { it.playerId == playerId }

    override fun findByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ) = courses.values.firstOrNull { it.playerId == playerId && it.eventName == eventName }

    override fun save(course: Course) {
        courses[course.id] = course
    }

    override fun delete(id: CourseId) {
        courses.remove(id)
    }
}

private class FakeDeckRepo(
    private val decks: List<Deck> = emptyList(),
) : DeckRepository {
    override fun findById(id: DeckId): Deck? = decks.firstOrNull { it.id == id }

    override fun findByName(name: String): Deck? = decks.firstOrNull { it.name == name }

    override fun findAllForPlayer(playerId: PlayerId): List<Deck> = decks.filter { it.playerId == playerId }

    override fun save(deck: Deck) {}

    override fun delete(id: DeckId) {}
}

class AppMatchCoordinatorTest :
    FunSpec({

        tags(FdTag)

        val playerId = PlayerId("test-player")
        val event = "QuickDraft_FDN_20260503"

        fun coordinator(
            draftRepo: FakeDraftRepo = FakeDraftRepo(),
            courseRepo: FakeCourseRepo = FakeCourseRepo(),
            deckRepo: FakeDeckRepo = FakeDeckRepo(),
        ): AppMatchCoordinator {
            val deckService = DeckService(deckRepo)
            val courseService =
                CourseService(courseRepo) {
                    GeneratedPool(emptyList(), listOf(CollationPool(0, emptyList())), 0)
                }
            return AppMatchCoordinator(playerId, deckService, courseService, draftRepo)
        }

        fun deck(
            id: String,
            name: String,
            grpId: Int,
            format: Format = Format.Standard,
        ): Deck =
            Deck(
                id = DeckId(id),
                playerId = playerId,
                name = name,
                format = format,
                tileId = 0,
                mainDeck = listOf(DeckCard(grpId, 4)),
                sideboard = emptyList(),
                commandZone = emptyList(),
                companions = emptyList(),
            )

        test("resolveOpponentDeckJson returns null when no draft session") {
            coordinator().resolveOpponentDeckJson(event) shouldBe null
        }

        test("resolveDeckJsonByName random picks a non-selected player deck") {
            val coord =
                coordinator(
                    deckRepo =
                        FakeDeckRepo(
                            listOf(
                                deck("selected", "Selected", 101),
                                deck("other", "Other", 202),
                            ),
                        ),
                )
            coord.selectDeck("selected")

            val json = coord.resolveDeckJsonByName("random")

            json.shouldNotBeNull()
            val main = Json.parseToJsonElement(json).jsonObject["MainDeck"]!!.jsonArray
            main.size shouldBe 1
            main[0].jsonObject["cardId"]?.jsonPrimitive?.int shouldBe 202
        }

        test("resolveDeckJsonByName random filters by selected event format") {
            val coord =
                coordinator(
                    deckRepo =
                        FakeDeckRepo(
                            listOf(
                                deck("standard", "Standard", 101),
                                deck("brawl", "Brawl", 202, Format.Brawl),
                            ),
                        ),
                )
            coord.selectEvent("Play_Brawl")

            val json = coord.resolveDeckJsonByName("random")

            json.shouldNotBeNull()
            val main = Json.parseToJsonElement(json).jsonObject["MainDeck"]!!.jsonArray
            main.size shouldBe 1
            main[0].jsonObject["cardId"]?.jsonPrimitive?.int shouldBe 202
        }

        test("resolveRandomDeckPairJson picks two distinct decks when possible") {
            val coord =
                coordinator(
                    deckRepo =
                        FakeDeckRepo(
                            listOf(
                                deck("one", "One", 101),
                                deck("two", "Two", 202),
                            ),
                        ),
                )

            val pair = coord.resolveRandomDeckPairJson()

            pair.shouldNotBeNull()
            val first =
                Json
                    .parseToJsonElement(pair.first)
                    .jsonObject["MainDeck"]!!
                    .jsonArray[0]
                    .jsonObject["cardId"]
                    ?.jsonPrimitive
                    ?.int
            val second =
                Json
                    .parseToJsonElement(pair.second)
                    .jsonObject["MainDeck"]!!
                    .jsonArray[0]
                    .jsonObject["cardId"]
                    ?.jsonPrimitive
                    ?.int
            setOf(first, second) shouldBe setOf(101, 202)
        }

        test("resolveOpponentDeckJson returns null when draft incomplete") {
            val draftRepo = FakeDraftRepo()
            draftRepo.save(
                DraftSession(
                    id = DraftSessionId("d1"),
                    playerId = playerId,
                    eventName = event,
                    status = DraftStatus.PickNext,
                    draftPack = listOf(1, 2, 3),
                    pickedCards = emptyList(),
                ),
            )
            coordinator(draftRepo).resolveOpponentDeckJson(event) shouldBe null
        }

        test("resolveOpponentDeckJson returns null when no pod persisted") {
            val draftRepo = FakeDraftRepo()
            draftRepo.save(
                DraftSession(
                    id = DraftSessionId("d1"),
                    playerId = playerId,
                    eventName = event,
                    status = DraftStatus.Completed,
                ),
            )
            coordinator(draftRepo).resolveOpponentDeckJson(event) shouldBe null
        }

        test("resolveOpponentDeckJson groups grpIds by quantity") {
            val draftRepo = FakeDraftRepo()
            val sessionId = DraftSessionId("d1")
            draftRepo.save(
                DraftSession(
                    id = sessionId,
                    playerId = playerId,
                    eventName = event,
                    status = DraftStatus.Completed,
                ),
            )
            val seat1Deck = listOf(100, 100, 100, 200, 200, 300)
            draftRepo.savePodResults(
                sessionId,
                listOf(seat1Deck) + List(6) { listOf(900 + it) },
            )

            val json = coordinator(draftRepo).resolveOpponentDeckJson(event)
            json.shouldNotBeNull()
            val parsed = Json.parseToJsonElement(json).jsonObject
            val main = parsed["MainDeck"]!!.jsonArray

            assertSoftly {
                main.size shouldBe 3
                json shouldContain "100"
                json shouldContain "200"
                json shouldContain "300"
            }

            val entry100 = main.firstOrNull { it.jsonObject["cardId"]?.jsonPrimitive?.int == 100 }
            entry100.shouldNotBeNull()
            entry100.jsonObject["quantity"]?.jsonPrimitive?.int shouldBe 3
        }

        test("resolveOpponentDeckJson rotates bot selection per match launch") {
            val draftRepo = FakeDraftRepo()
            val courseRepo = FakeCourseRepo()
            val sessionId = DraftSessionId("d1")
            draftRepo.save(
                DraftSession(
                    id = sessionId,
                    playerId = playerId,
                    eventName = event,
                    status = DraftStatus.Completed,
                ),
            )
            val pod = (0 until 7).map { seat -> listOf(700 + seat) }
            draftRepo.savePodResults(sessionId, pod)

            val coord = coordinator(draftRepo, courseRepo)
            val firstMain = Json.parseToJsonElement(coord.resolveOpponentDeckJson(event)!!).jsonObject["MainDeck"]!!.jsonArray
            firstMain.size shouldBe 1
            firstMain[0].jsonObject["cardId"]?.jsonPrimitive?.int shouldBe 700

            val secondMain = Json.parseToJsonElement(coord.resolveOpponentDeckJson(event)!!).jsonObject["MainDeck"]!!.jsonArray
            secondMain.size shouldBe 1
            secondMain[0].jsonObject["cardId"]?.jsonPrimitive?.int shouldBe 701
        }
    })
