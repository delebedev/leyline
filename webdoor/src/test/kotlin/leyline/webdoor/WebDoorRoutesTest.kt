package leyline.webdoor

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.domain.Course
import leyline.domain.CourseId
import leyline.domain.Deck
import leyline.domain.DeckId
import leyline.domain.DraftSession
import leyline.domain.DraftSessionId
import leyline.domain.DraftStatus
import leyline.domain.PlayerId
import leyline.domain.repo.CourseRepository
import leyline.domain.repo.DeckRepository
import leyline.domain.repo.DraftSessionRepository
import leyline.domain.service.CollectionService
import leyline.domain.service.CourseService
import leyline.domain.service.DeckService
import leyline.domain.service.DraftService
import leyline.domain.service.GeneratedPool

class WebDoorRoutesTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("serves checked-in OpenAPI contract") {
            withWebDoor { client, _ ->
                val response = client.get("/openapi.json")
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

                response.status shouldBe HttpStatusCode.OK
                body["openapi"]?.jsonPrimitive?.content shouldBe "3.1.0"
            }
        }

        test("starts and reads draft status") {
            withWebDoor { client, _ ->
                val start =
                    client.post("/api/draft/start") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""")
                    }
                val status = client.get("/api/draft/status?playerId=p1&eventName=QuickDraft_FDN_20260223")

                assertSoftly {
                    start.status shouldBe HttpStatusCode.OK
                    json.parseToJsonElement(start.bodyAsText()).jsonObject["draftPack"]!!.jsonArray.size shouldBe 2
                    status.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("rejects undersized draft deck") {
            withWebDoor { client, _ ->
                client.post("/api/draft/start") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""")
                }
                val response =
                    client.post("/api/draft/deck") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223","mainDeck":[{"grpId":100,"quantity":1}]}""",
                        )
                    }

                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("drops draft and course") {
            withWebDoor { client, repos ->
                client.post("/api/draft/start") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""")
                }
                val response = client.delete("/api/draft?playerId=p1&eventName=QuickDraft_FDN_20260223")

                response.status shouldBe HttpStatusCode.NoContent
                repos.draft.findByPlayerAndEvent(PlayerId("p1"), "QuickDraft_FDN_20260223") shouldBe null
            }
        }
    })

private fun withWebDoor(block: suspend (io.ktor.client.HttpClient, TestRepos) -> Unit) =
    testApplication {
        val repos = TestRepos()
        val services =
            WebDoorServices(
                draftService = DraftService(repos.draft, StaticDraftDriver()),
                courseService = CourseService(repos.course) { GeneratedPool(cards = emptyList(), byCollation = emptyList(), collationId = 0) },
                deckService = DeckService(repos.deck),
                collectionService = CollectionService { listOf(100, 101) },
                matchLauncher = object : WebMatchLauncher {
                    override fun launchCourseMatch(playerId: PlayerId, eventName: String) = DraftPlayResponse("match-1", "wire-1")
                },
            )
        application { installWebDoor(services) }
        block(client, repos)
    }

private class TestRepos {
    val course = MemoryCourseRepo()
    val draft = MemoryDraftRepo()
    val deck = MemoryDeckRepo()
}

private class StaticDraftDriver : DraftService.Driver {
    override fun start(sessionKey: String, setCode: String) = listOf(100, 101)
    override fun pick(sessionKey: String, grpId: Int) = DraftService.PickOutcome(0, 1, listOf(101), complete = false)
    override fun complete(sessionKey: String) = DraftService.PodOutcome(emptyList(), emptyList())
}

private class MemoryCourseRepo : CourseRepository {
    private val courses = mutableMapOf<CourseId, Course>()
    override fun findById(id: CourseId) = courses[id]
    override fun findByPlayer(playerId: PlayerId) = courses.values.filter { it.playerId == playerId }
    override fun findByPlayerAndEvent(playerId: PlayerId, eventName: String) = courses.values.firstOrNull { it.playerId == playerId && it.eventName == eventName }
    override fun save(course: Course) { courses[course.id] = course }
    override fun delete(id: CourseId) { courses.remove(id) }
}

private class MemoryDraftRepo : DraftSessionRepository {
    private val sessions = mutableMapOf<DraftSessionId, DraftSession>()
    override fun findById(id: DraftSessionId) = sessions[id]
    override fun findByPlayerAndEvent(playerId: PlayerId, eventName: String) = sessions.values.firstOrNull { it.playerId == playerId && it.eventName == eventName }
    override fun save(session: DraftSession) { sessions[session.id] = session }
    override fun delete(id: DraftSessionId) { sessions.remove(id) }
    override fun deleteIncomplete() { sessions.values.removeIf { it.status != DraftStatus.Completed } }
    override fun savePodResults(sessionId: DraftSessionId, botDecks: List<List<Int>>) = Unit
    override fun findPodResults(sessionId: DraftSessionId): List<List<Int>> = emptyList()
}

private class MemoryDeckRepo : DeckRepository {
    private val decks = mutableMapOf<DeckId, Deck>()
    override fun findById(id: DeckId) = decks[id]
    override fun findByName(name: String) = decks.values.firstOrNull { it.name == name }
    override fun findAllForPlayer(playerId: PlayerId) = decks.values.filter { it.playerId == playerId }
    override fun save(deck: Deck) { decks[deck.id] = deck }
    override fun delete(id: DeckId) { decks.remove(id) }
}
