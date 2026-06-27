package leyline.debug

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.FdTag
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.CollationPool
import leyline.domain.Course
import leyline.domain.CourseId
import leyline.domain.CourseModule
import leyline.domain.Deck
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.DraftSession
import leyline.domain.DraftSessionId
import leyline.domain.DraftStatus
import leyline.domain.PlayerId
import leyline.domain.repo.CourseRepository
import leyline.domain.repo.DeckRepository
import leyline.domain.repo.DraftSessionRepository
import leyline.domain.service.CourseService
import leyline.domain.service.DeckService
import leyline.domain.service.DraftService
import leyline.domain.service.GeneratedPool
import leyline.infra.AppMatchCoordinator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI

class DraftControlApiTest :
    FunSpec({
        tags(FdTag)

        test("start and status expose draft state over HTTP") {
            withApi { client, _, _, _, _ ->
                val start =
                    client.post(
                        "/api/draft/start",
                        """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""",
                    )
                val status = client.get("/api/draft/status?playerId=p1&eventName=QuickDraft_FDN_20260223")

                assertSoftly {
                    start.code shouldBe 200
                    start.json()["status"]!!.jsonPrimitive.content shouldBe "PickNext"
                    start.json()["draftPack"]!!.jsonArray.map { it.jsonPrimitive.int } shouldBe listOf(100, 101)
                    status.code shouldBe 200
                    status.json()["pickNumber"]!!.jsonPrimitive.int shouldBe 0
                }
            }
        }

        test("completed pick moves shared course to deck selection") {
            withApi { client, courseRepo, _, _, _ ->
                client.post("/api/draft/start", """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""")
                client.post(
                    "/api/draft/pick",
                    """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223","cardId":100,"packNumber":0,"pickNumber":0}""",
                )
                val completed =
                    client.post(
                        "/api/draft/pick",
                        """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223","cardId":101,"packNumber":0,"pickNumber":1}""",
                    )

                val course = courseRepo.findByPlayerAndEvent(PlayerId("p1"), "QuickDraft_FDN_20260223")
                assertSoftly {
                    completed.code shouldBe 200
                    completed.json()["status"]!!.jsonPrimitive.content shouldBe "Completed"
                    course?.module shouldBe CourseModule.DeckSelect
                    course?.cardPool shouldBe listOf(100, 101)
                }
            }
        }

        test("start clears stale completed draft when prior course is complete") {
            withApi { client, courseRepo, _, _, _ ->
                completeDraftAndSubmitDeck(client, playerId = "p1")
                val completeCourse =
                    checkNotNull(courseRepo.findByPlayerAndEvent(PlayerId("p1"), "QuickDraft_FDN_20260223"))
                        .copy(module = CourseModule.Complete)
                courseRepo.save(completeCourse)

                val restarted = client.post("/api/draft/start", """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""")

                assertSoftly {
                    restarted.code shouldBe 200
                    restarted.json()["status"]!!.jsonPrimitive.content shouldBe "PickNext"
                    restarted.json()["pickNumber"]!!.jsonPrimitive.int shouldBe 0
                    restarted.json()["pickedCards"]!!.jsonArray shouldBe emptyList<Any>()
                    restarted.json()["draftPack"]!!.jsonArray.map { it.jsonPrimitive.int } shouldBe listOf(100, 101)
                }
            }
        }

        test("deck submission stores course deck") {
            withApi { client, courseRepo, _, _, _ ->
                client.post("/api/draft/start", """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""")
                repeat(2) { index ->
                    val pickBody =
                        buildString {
                            append("{\"playerId\":\"p1\",\"eventName\":\"QuickDraft_FDN_20260223\"")
                            append(",\"cardId\":${100 + index},\"packNumber\":0,\"pickNumber\":$index}")
                        }
                    client.post(
                        "/api/draft/pick",
                        pickBody,
                    )
                }

                val deckBody =
                    buildString {
                        append("{\"playerId\":\"p1\",\"eventName\":\"QuickDraft_FDN_20260223\"")
                        append(",\"name\":\"Draft Deck\",\"mainDeck\":[{\"grpId\":100,\"quantity\":1}]")
                        append(",\"sideboard\":[{\"grpId\":101,\"quantity\":1}]}")
                    }
                val response =
                    client.post(
                        "/api/draft/deck",
                        deckBody,
                    )

                val course = courseRepo.findByPlayerAndEvent(PlayerId("p1"), "QuickDraft_FDN_20260223")
                assertSoftly {
                    response.code shouldBe 200
                    response.json()["module"]!!.jsonPrimitive.content shouldBe "CreateMatch"
                    course?.deck?.mainDeck shouldBe listOf(DeckCard(100, 1))
                    course?.deck?.sideboard shouldBe listOf(DeckCard(101, 1))
                }
            }
        }

        test("bearer token protects draft endpoints") {
            withApi(token = "secret") { client, _, _, _, _ ->
                val unauthorized = client.get("/api/draft/status?playerId=p1&eventName=QuickDraft_FDN_20260223")
                val authorized =
                    client.post(
                        "/api/draft/start",
                        """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""",
                        token = "secret",
                    )

                unauthorized.code shouldBe 401
                authorized.code shouldBe 200
            }
        }

        test("play configures match from submitted course deck and pod opponent") {
            withApi { client, courseRepo, _, registry, coordinator ->
                completeDraftAndSubmitDeck(client, playerId = "p1")

                val response =
                    client.post(
                        "/api/draft/play",
                        """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""",
                    )

                val matchId = response.json()["matchId"]!!.jsonPrimitive.content
                val config = registry.get(matchId)
                assertSoftly {
                    response.code shouldBe 200
                    config?.seat1Deck?.contains("1 Forest") shouldBe true
                    config?.seat1Deck?.contains("1 Mountain") shouldBe true
                    config?.seat2Deck?.contains("1 Mountain") shouldBe true
                }

                coordinator.reportMatchResult(matchId, won = true)
                val course = courseRepo.findByPlayerAndEvent(PlayerId("p1"), "QuickDraft_FDN_20260223")
                course?.wins shouldBe 1
            }
        }

        test("drop clears draft session and completes course") {
            withApi { client, courseRepo, draftRepo, _, _ ->
                client.post("/api/draft/start", """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""")

                val response = client.delete("/api/draft?playerId=p1&eventName=QuickDraft_FDN_20260223")
                val session = draftRepo.findByPlayerAndEvent(PlayerId("p1"), "QuickDraft_FDN_20260223")
                val course = courseRepo.findByPlayerAndEvent(PlayerId("p1"), "QuickDraft_FDN_20260223")

                assertSoftly {
                    response.code shouldBe 200
                    session shouldBe null
                    course?.module shouldBe CourseModule.Complete
                }
            }
        }

        test("two players keep independent draft sessions") {
            withApi { client, _, _, _, _ ->
                client.post("/api/draft/start", """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223"}""")
                client.post("/api/draft/start", """{"playerId":"p2","eventName":"QuickDraft_FDN_20260223"}""")
                client.post(
                    "/api/draft/pick",
                    """{"playerId":"p1","eventName":"QuickDraft_FDN_20260223","cardId":100,"packNumber":0,"pickNumber":0}""",
                )

                val p2 = client.get("/api/draft/status?playerId=p2&eventName=QuickDraft_FDN_20260223")

                assertSoftly {
                    p2.code shouldBe 200
                    p2.json()["pickedCards"]!!.jsonArray shouldBe emptyList<Any>()
                    p2.json()["draftPack"]!!.jsonArray.map { it.jsonPrimitive.int } shouldBe listOf(100, 101)
                }
            }
        }
    })

private fun withApi(
    token: String? = null,
    block: (TestClient, FakeCourseRepo, FakeDraftRepo, RuntimeMatchConfigRegistry, AppMatchCoordinator) -> Unit,
) {
    val courseRepo = FakeCourseRepo()
    val draftRepo = FakeDraftRepo()
    val courseService =
        CourseService(courseRepo) {
            GeneratedPool(emptyList(), listOf(CollationPool(0, emptyList())), 0)
        }
    val draftService = DraftService(draftRepo, ScriptedDraftDriver(listOf(listOf(100, 101))))
    val registry = RuntimeMatchConfigRegistry()
    val coordinator =
        AppMatchCoordinator(
            playerId = PlayerId("local-player"),
            deckService = DeckService(FakeDeckRepo()),
            courseService = courseService,
            draftRepo = draftRepo,
            nameByGrpId = { grpId ->
                if (grpId == 100) {
                    "Forest"
                } else if (grpId == 101) {
                    "Mountain"
                } else {
                    null
                }
            },
        )
    val server = HttpServer.create(InetSocketAddress(0), 0)
    DraftControlApi(
        draftServiceProvider = { draftService },
        courseServiceProvider = { courseService },
        matchCoordinatorProvider = { coordinator },
        runtimeMatchConfigs = registry,
        controlToken = token,
    ).mount(server)
    server.start()
    try {
        block(TestClient(server.address.port), courseRepo, draftRepo, registry, coordinator)
    } finally {
        server.stop(0)
    }
}

private fun completeDraftAndSubmitDeck(
    client: TestClient,
    playerId: String,
) {
    client.post("/api/draft/start", """{"playerId":"$playerId","eventName":"QuickDraft_FDN_20260223"}""")
    client.post(
        "/api/draft/pick",
        """{"playerId":"$playerId","eventName":"QuickDraft_FDN_20260223","cardId":100,"packNumber":0,"pickNumber":0}""",
    )
    client.post(
        "/api/draft/pick",
        """{"playerId":"$playerId","eventName":"QuickDraft_FDN_20260223","cardId":101,"packNumber":0,"pickNumber":1}""",
    )
    val deckBody =
        buildString {
            append("{\"playerId\":\"$playerId\",\"eventName\":\"QuickDraft_FDN_20260223\"")
            append(",\"name\":\"Draft Deck\",\"mainDeck\":[{\"grpId\":100,\"quantity\":1}]")
            append(",\"sideboard\":[{\"grpId\":101,\"quantity\":1}]}")
        }
    client.post(
        "/api/draft/deck",
        deckBody,
    )
}

private class TestClient(
    private val port: Int,
) {
    fun get(
        path: String,
        token: String? = null,
    ): TestResponse = request("GET", path, null, token)

    fun post(
        path: String,
        body: String,
        token: String? = null,
    ): TestResponse = request("POST", path, body, token)

    fun delete(
        path: String,
        token: String? = null,
    ): TestResponse = request("DELETE", path, null, token)

    private fun request(
        method: String,
        path: String,
        body: String?,
        token: String?,
    ): TestResponse {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        return TestResponse(code, stream?.bufferedReader()?.readText().orEmpty())
    }
}

private data class TestResponse(
    val code: Int,
    val body: String,
) {
    fun json() = Json.parseToJsonElement(body).jsonObject
}

private class ScriptedDraftDriver(
    packs: List<List<Int>>,
) : DraftService.Driver {
    private val template = packs.map { it.toList() }
    private val sessions = mutableMapOf<String, ScriptedDraftState>()

    override fun start(
        sessionKey: String,
        setCode: String,
    ): List<Int> {
        val state = ScriptedDraftState(template.map { it.toMutableList() }.toMutableList())
        sessions[sessionKey] = state
        return state.remainingByPack[state.currentPack].toList()
    }

    override fun pick(
        sessionKey: String,
        grpId: Int,
    ): DraftService.PickOutcome {
        val state = sessions[sessionKey] ?: error("missing scripted session")
        state.remainingByPack[state.currentPack].remove(grpId)
        val completedPick = state.pick
        state.pick++
        if (state.remainingByPack[state.currentPack].isEmpty()) {
            state.currentPack++
            state.pick = 0
        }
        val complete = state.currentPack >= state.remainingByPack.size
        return DraftService.PickOutcome(
            packNumber = if (complete) state.currentPack - 1 else state.currentPack,
            pickNumber = if (complete) completedPick else state.pick,
            nextPack = if (complete) emptyList() else state.remainingByPack[state.currentPack].toList(),
            complete = complete,
        )
    }

    override fun complete(sessionKey: String): DraftService.PodOutcome =
        DraftService.PodOutcome(playerPool = listOf(100, 101), botDecks = List(7) { listOf(101) })

    private data class ScriptedDraftState(
        val remainingByPack: MutableList<MutableList<Int>>,
        var currentPack: Int = 0,
        var pick: Int = 0,
    )
}

private class FakeDeckRepo : DeckRepository {
    override fun findById(id: DeckId): Deck? = null

    override fun findByName(name: String): Deck? = null

    override fun findAllForPlayer(playerId: PlayerId): List<Deck> = emptyList()

    override fun save(deck: Deck) {}

    override fun delete(id: DeckId) {}
}

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
            .forEach(::delete)
    }

    override fun savePodResults(
        sessionId: DraftSessionId,
        botDecks: List<List<Int>>,
    ) {
        pods[sessionId] = botDecks
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
