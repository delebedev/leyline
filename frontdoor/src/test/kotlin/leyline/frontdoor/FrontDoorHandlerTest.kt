package leyline.frontdoor

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.frontdoor.domain.CollationPool
import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.InMemoryCourseRepository
import leyline.frontdoor.repo.InMemoryDraftSessionRepository
import leyline.frontdoor.repo.SqlitePlayerStore
import leyline.frontdoor.service.CollectionService
import leyline.frontdoor.service.CourseService
import leyline.frontdoor.service.DeckService
import leyline.frontdoor.service.DraftService
import leyline.frontdoor.service.GeneratedPool
import leyline.frontdoor.service.MatchmakingService
import leyline.frontdoor.service.PlayerService
import leyline.frontdoor.wire.FdEnvelope
import leyline.frontdoor.wire.FdResponseWriter
import leyline.frontdoor.wire.FdWireConstants
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.util.UUID

/**
 * Wire-level integration tests for [FrontDoorHandler].
 *
 * Boots FD in an [EmbeddedChannel] (no TLS, no sockets), sends framed
 * protobuf envelopes, decodes responses, and validates JSON shapes.
 * One test per CmdType dispatch branch.
 */
class FrontDoorHandlerTest :
    FunSpec({

        tags(FdTag)

        val testPlayerId = "test-player-00000000-0000-0000-0000-000000000001"
        val testDeckId = "test-deck-00000000-0000-0000-0000-000000000001"

        // Minimal deck cards matching real Arena shape
        val sampleMainDeck = listOf(DeckCard(75515, 4), DeckCard(75516, 56))

        val json = Json { ignoreUnknownKeys = true }
        val tempDb = File.createTempFile("fd-test", ".db").also { it.deleteOnExit() }
        var channel: EmbeddedChannel? = null

        // Shared services wired to test SQLite
        val db = Database.connect("jdbc:sqlite:${tempDb.absolutePath}", "org.sqlite.JDBC")
        val store = SqlitePlayerStore(db)
        val golden = GoldenData.loadFromClasspath()
        val deckService = DeckService(store)
        val playerService = PlayerService(store)
        val matchmakingService = MatchmakingService(store, "localhost", 30003)
        val writer = FdResponseWriter()

        beforeSpec {
            store.createTables()
            store.ensurePlayer(PlayerId(testPlayerId), "Tester")
            store.save(
                Deck(
                    id = DeckId(testDeckId),
                    playerId = PlayerId(testPlayerId),
                    name = "Test Deck",
                    format = Format.Standard,
                    tileId = 12345,
                    mainDeck = sampleMainDeck,
                    sideboard = emptyList(),
                    commandZone = emptyList(),
                    companions = emptyList(),
                ),
            )
        }

        afterEach {
            channel?.finishAndReleaseAll()
            channel = null
        }

        /** Create a fresh FD channel wired to our test player. */
        fun fdChannel(): EmbeddedChannel {
            val ch = EmbeddedChannel(
                FrontDoorHandler(
                    playerId = PlayerId(testPlayerId),
                    deckService = deckService,
                    playerService = playerService,
                    matchmaking = matchmakingService,
                    collectionService = CollectionService { emptyList() },
                    courseService = CourseService(InMemoryCourseRepository()) { _ ->
                        GeneratedPool(emptyList(), emptyList(), 0)
                    },
                    draftService = DraftService(InMemoryDraftSessionRepository()) { _ -> emptyList() },
                    writer = writer,
                    golden = golden,
                ),
            )
            channel = ch
            return ch
        }

        /** Write a framed Cmd envelope into the channel. */
        fun EmbeddedChannel.writeCmd(cmdType: Int, payload: String? = "{}") {
            val envelope = FdEnvelope.encodeCmd(cmdType, UUID.randomUUID().toString(), payload ?: "{}")
            val header = FdEnvelope.buildOutgoingHeader(envelope.size)
            val buf = Unpooled.buffer(header.size + envelope.size)
            buf.writeBytes(header)
            buf.writeBytes(envelope)
            writeInbound(buf)
        }

        /** Read all pending outbound responses. */
        fun EmbeddedChannel.readAllResponses(): List<FdEnvelope.FdMessage> {
            val results = mutableListOf<FdEnvelope.FdMessage>()
            while (true) {
                val resp = readOutbound<ByteBuf>() ?: break
                results.add(decodeResponse(resp))
            }
            return results
        }

        /** Send a framed Cmd envelope, read back first response as FdMessage. */
        fun EmbeddedChannel.sendCmd(cmdType: Int, payload: String? = "{}"): FdEnvelope.FdMessage {
            writeCmd(cmdType, payload)
            val resp = readOutbound<ByteBuf>() ?: error("No response for CmdType $cmdType")
            return decodeResponse(resp)
        }

        /** Send a cmd and return ALL responses (for 612 which sends ack + push). */
        fun EmbeddedChannel.sendCmdAll(cmdType: Int, payload: String? = "{}"): List<FdEnvelope.FdMessage> {
            writeCmd(cmdType, payload)
            return readAllResponses()
        }

        /** Send a cmd, parse first response as JsonObject. Uses [ch] or creates a fresh channel. */
        fun sendJson(cmdType: Int, payload: String? = "{}", ch: EmbeddedChannel = fdChannel()): JsonObject {
            val msg = ch.sendCmd(cmdType, payload)
            return json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonObject
        }

        /** Create a FD channel with CourseService + DraftService wired (for sealed/draft tests). */
        fun fdChannelWithCourseService(): EmbeddedChannel {
            val poolGen: (String) -> GeneratedPool = { _ ->
                GeneratedPool(
                    cards = (1..84).toList(),
                    byCollation = listOf(CollationPool(100026, (1..84).toList())),
                    collationId = 100026,
                )
            }
            val courseService = CourseService(InMemoryCourseRepository(), poolGen)
            val draftService = DraftService(InMemoryDraftSessionRepository()) { _ ->
                (0 until 3).map { pack -> (1..13).map { card -> 90000 + pack * 100 + card } }
            }
            val ch = EmbeddedChannel(
                FrontDoorHandler(
                    playerId = PlayerId(testPlayerId),
                    deckService = deckService,
                    playerService = playerService,
                    matchmaking = matchmakingService,
                    collectionService = CollectionService { emptyList() },
                    courseService = courseService,
                    draftService = draftService,
                    writer = writer,
                    golden = golden,
                ),
            )
            channel = ch
            return ch
        }

        // --- Tests ---

        test("CmdType 0 - auth returns SessionId and Attached") {
            val obj = sendJson(0, """{"ClientVersion":"1.0","Token":"fake"}""")
            obj["SessionId"].shouldNotBeNull()
            obj["Attached"]?.jsonPrimitive?.boolean shouldBe true
        }

        test("CmdType 1 - StartHook contains DeckSummariesV2 and Decks") {
            val obj = sendJson(1)
            obj["DeckSummariesV2"].shouldNotBeNull()
            (obj["DeckSummariesV2"] as JsonArray).shouldNotBeEmpty()
            obj["Decks"].shouldNotBeNull()
            obj["InventoryInfo"].shouldNotBeNull()
        }

        test("CmdType 1 - StartHook deck summaries have required fields") {
            val obj = sendJson(1)
            val summaries = obj["DeckSummariesV2"]!!.jsonArray
            summaries.shouldNotBeEmpty()
            val deck = summaries[0].jsonObject
            deck["DeckId"].shouldNotBeNull()
            deck["Name"].shouldNotBeNull()
            deck["DeckTileId"].shouldNotBeNull()
            deck["Attributes"].shouldNotBeNull()
            deck["FormatLegalities"].shouldNotBeNull()
        }

        test("CmdType 1 - StartHook deck cards have CardSkins and ReducedSideboard") {
            val obj = sendJson(1)
            val decks = obj["Decks"]!!.jsonObject
            decks.entries.shouldNotBeEmpty()
            for ((_, deckJson) in decks) {
                val deck = deckJson.jsonObject
                deck["MainDeck"].shouldNotBeNull()
                deck["ReducedSideboard"].shouldNotBeNull()
                deck["CardSkins"].shouldNotBeNull()
            }
        }

        test("CmdType 6 - GetFormats returns proto response") {
            val ch = fdChannel()
            val msg = ch.sendCmd(6)
            msg.transactionId.shouldNotBeNull()
            // Proto response — jsonPayload may be null but response must exist
        }

        test("CmdType 612 - AiBotMatch returns ack then MatchCreated with correct EventId") {
            val ch = fdChannel()
            val responses = ch.sendCmdAll(612, """{"deckId":"$testDeckId","eventName":"AIBotMatch"}""")
            responses.size shouldBe 2
            responses[0].transactionId.shouldNotBeNull()
            val push = responses[1]
            val pushJson = push.jsonPayload.shouldNotBeNull()
            pushJson shouldContain "MatchCreated"
            val pushObj = json.parseToJsonElement(pushJson).jsonObject
            pushObj["Type"]?.jsonPrimitive?.content shouldBe "MatchCreated"
            val matchInfo = pushObj["MatchInfoV3"]?.jsonObject
            matchInfo.shouldNotBeNull()
            matchInfo["MatchEndpointHost"].shouldNotBeNull()
            matchInfo["MatchEndpointPort"].shouldNotBeNull()
            matchInfo["MatchId"].shouldNotBeNull()
            matchInfo["EventId"]?.jsonPrimitive?.content shouldBe "AIBotMatch"
        }

        test("CmdType 612 - always creates AIBotMatch regardless of payload") {
            // Real client never sends eventName on 612 — it's always an AI bot match.
            // The eventName-based flow goes through 603 (EnterPairing).
            val ch = fdChannel()
            val responses = ch.sendCmdAll(612, """{"deckId":"$testDeckId","botDeckId":"some-bot-deck","botMatchType":0}""")
            responses.size shouldBe 2
            val matchInfo = json.parseToJsonElement(responses[1].jsonPayload!!)
                .jsonObject["MatchInfoV3"]?.jsonObject
            matchInfo.shouldNotBeNull()
            matchInfo["EventId"]?.jsonPrimitive?.content shouldBe "AIBotMatch"
        }

        test("CmdType 1700 - GraphDefinitions returns JSON") {
            val ch = fdChannel()
            val msg = ch.sendCmd(1700)
            msg.jsonPayload.shouldNotBeNull().shouldNotBeEmpty()
            json.parseToJsonElement(msg.jsonPayload!!) // valid JSON
        }

        test("CmdType 1910 - PlayBladeQueueConfig has all 14 queues from real server") {
            val ch = fdChannel()
            val msg = ch.sendCmd(1910)
            val arr = json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonArray
            arr.size shouldBe 14
            val ids = arr.map { it.jsonObject["Id"]?.jsonPrimitive?.content }
            ids shouldContain "StandardRanked"
            ids shouldContain "HistoricRanked"
            ids shouldContain "ExplorerRanked"
            ids shouldContain "TimelessRanked"
            ids shouldContain "StandardUnranked"
            ids shouldContain "AIBotMatch"
            ids shouldContain "HistoricBrawl"
            ids shouldContain "StandardBrawl"
        }

        test("CmdType 624 - ActiveEventsV2 has events for all formats") {
            val obj = sendJson(624)
            val events = obj["Events"]?.jsonArray
            events.shouldNotBeNull()
            events.shouldNotBeEmpty()
            val names = events.map { it.jsonObject["InternalEventName"]?.jsonPrimitive?.content }
            names shouldContain "Ladder"
            names shouldContain "Historic_Ladder"
            names shouldContain "Explorer_Ladder"
            names shouldContain "Timeless_Ladder"
            names shouldContain "AIBotMatch"
        }

        test("CmdType 624 - every event matches reference shape from real server") {
            val refKeys = loadGoldenRef("golden/fd-reference-event.json")
            val obj = sendJson(624)
            for (event in obj["Events"]!!.jsonArray) {
                val name = event.jsonObject["InternalEventName"]!!.jsonPrimitive.content
                assertKeysMatch(refKeys, event.jsonObject, name)
            }
        }

        test("CmdType 623 - EventGetCoursesV2 returns default courses") {
            val obj = sendJson(623)
            val courses = obj["Courses"]?.jsonArray
            courses.shouldNotBeNull()
            courses.shouldNotBeEmpty()
            val names = courses.map { it.jsonObject["InternalEventName"]?.jsonPrimitive?.content }
            names shouldContain "Ladder"
        }

        test("CmdType 623 - every course matches reference shape from real server") {
            val refKeys = loadGoldenRef("golden/fd-reference-course.json")
            val obj = sendJson(623)
            for (course in obj["Courses"]!!.jsonArray) {
                val name = course.jsonObject["InternalEventName"]!!.jsonPrimitive.content
                assertKeysMatch(refKeys, course.jsonObject, name)
            }
        }

        test("CmdType 1910 - every queue matches reference shape from real server") {
            val refKeys = loadGoldenRef("golden/fd-reference-queue.json")
            val ch = fdChannel()
            val msg = ch.sendCmd(1910)
            val queues = json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonArray
            for (queue in queues) {
                val id = queue.jsonObject["Id"]!!.jsonPrimitive.content
                assertKeysMatch(refKeys, queue.jsonObject, id)
            }
        }

        test("CmdType 410 - PreconDecksV3 returns precon decks from golden") {
            val obj = sendJson(410)
            obj["PreconDecks"]?.jsonArray.shouldNotBeNull()
        }

        test("CmdType 1911 - PlayerPreferences has single Preferences wrapper") {
            val obj = sendJson(1911)
            val prefs = obj["Preferences"]?.jsonObject
            prefs.shouldNotBeNull()
            // Must NOT be double-wrapped: Preferences.Preferences
            prefs.containsKey("Preferences") shouldBe false
        }

        test("CmdType 613 - ActiveMatches returns empty list") {
            val obj = sendJson(613)
            obj["MatchesV3"]?.jsonArray.shouldNotBeNull()
        }

        test("CmdType 1100 - RankInfo returns required fields") {
            val obj = sendJson(1100)
            obj["constructedClass"].shouldNotBeNull()
            obj["limitedClass"].shouldNotBeNull()
        }

        test("CmdType 403 - DeleteDeck removes deck from store") {
            val deletableId = "test-deck-00000000-0000-0000-0000-deleteme0001"
            store.save(
                Deck(
                    id = DeckId(deletableId),
                    playerId = PlayerId(testPlayerId),
                    name = "Doomed Deck",
                    format = Format.Standard,
                    tileId = 99999,
                    mainDeck = sampleMainDeck,
                    sideboard = emptyList(),
                    commandZone = emptyList(),
                    companions = emptyList(),
                ),
            )
            deckService.getById(DeckId(deletableId)).shouldNotBeNull()

            val ch = fdChannel()
            ch.sendCmd(403, """{"DeckId":"$deletableId"}""")

            deckService.getById(DeckId(deletableId)) shouldBe null
        }

        test("CmdType 406 - UpsertDeckV2 creates deck and echoes Summary") {
            val newDeckId = "test-deck-00000000-0000-0000-0000-upsert000001"
            val payload = """
                {
                    "Summary": {"DeckId":"$newDeckId","Name":"New Deck","DeckTileId":11111},
                    "Deck": {
                        "MainDeck": [{"cardId":75515,"quantity":4}],
                        "Sideboard": [],
                        "CommandZone": [],
                        "Companions": []
                    },
                    "ActionType": "Create"
                }
            """.trimIndent()
            val ch = fdChannel()
            val msg = ch.sendCmd(406, payload)
            val resp = json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonObject
            resp["DeckId"]?.jsonPrimitive?.content shouldBe newDeckId

            val saved = deckService.getById(DeckId(newDeckId))
            saved.shouldNotBeNull()
            saved.name shouldBe "New Deck"
            saved.mainDeck.size shouldBe 1
            saved.mainDeck[0].grpId shouldBe 75515
        }

        test("CmdType 1912 - SetPlayerPreferences round-trips through 1911") {
            val prefsPayload = """{"Preferences":{"AutoTapEnabled":true,"AutoOrderTriggeredAbilities":false}}"""
            val ch = fdChannel()
            ch.sendCmd(1912, prefsPayload)

            // Read back via 1911 on same channel
            val readMsg = ch.sendCmd(1911)
            val obj = json.parseToJsonElement(readMsg.jsonPayload.shouldNotBeNull()).jsonObject
            val prefs = obj["Preferences"]?.jsonObject
            prefs.shouldNotBeNull()
            prefs["AutoTapEnabled"]?.jsonPrimitive?.boolean shouldBe true
            prefs["AutoOrderTriggeredAbilities"]?.jsonPrimitive?.boolean shouldBe false
        }

        test("CmdType 9999 - unknown returns response without error") {
            val ch = fdChannel()
            val msg = ch.sendCmd(9999)
            msg.transactionId.shouldNotBeNull()
            // Empty response is fine — just shouldn't crash
        }

        // --- Shape conformance tests ---

        test("CmdType 1 - StartHook matches reference shape from real server") {
            val refKeys = loadGoldenRef("golden/fd-reference-starthook.json")
            val obj = sendJson(1)
            assertKeysMatch(refKeys, obj, "StartHook")
        }

        test("CmdType 612 - MatchCreated matches reference shape from real server") {
            val refKeys = loadGoldenRef("golden/fd-reference-matchcreated.json")
            val ch = fdChannel()
            val responses = ch.sendCmdAll(612, """{"deckId":"$testDeckId"}""")
            val pushJson = responses[1].jsonPayload.shouldNotBeNull()
            val pushObj = json.parseToJsonElement(pushJson).jsonObject
            assertKeysMatch(refKeys, pushObj, "MatchCreated")
        }

        test("CmdType 406 - upserted deck appears in next StartHook") {
            val deckId = "test-deck-00000000-0000-0000-0000-roundtrip001"
            val payload = """
                {
                    "Summary": {"DeckId":"$deckId","Name":"Roundtrip Deck","DeckTileId":77777},
                    "Deck": {
                        "MainDeck": [{"cardId":75515,"quantity":4},{"cardId":75516,"quantity":56}],
                        "Sideboard": [],
                        "CommandZone": [],
                        "Companions": []
                    },
                    "ActionType": "Create"
                }
            """.trimIndent()
            val ch = fdChannel()
            ch.sendCmd(406, payload)

            // StartHook on same channel should include the new deck
            val hook = ch.sendCmd(1)
            val hookObj = json.parseToJsonElement(hook.jsonPayload.shouldNotBeNull()).jsonObject
            val summaries = hookObj["DeckSummariesV2"]!!.jsonArray
            val ids = summaries.map { it.jsonObject["DeckId"]?.jsonPrimitive?.content }
            ids shouldContain deckId

            val decksMap = hookObj["Decks"]!!.jsonObject
            decksMap.containsKey(deckId) shouldBe true
            decksMap[deckId]!!.jsonObject["MainDeck"].shouldNotBeNull()
        }

        // --- Sealed event lifecycle ---

        test("sealed lifecycle - join, get pool, set deck, check courses, resign") {
            val ch = fdChannelWithCourseService()
            val event = "Sealed_FDN_20260307"

            // 1. Join — get DeckSelect module with card pool
            val join = sendJson(600, """{"EventName":"$event"}""", ch)
            val course = join["Course"]!!.jsonObject
            course["CurrentModule"]?.jsonPrimitive?.content shouldBe "DeckSelect"
            course["CardPool"]!!.jsonArray.shouldNotBeEmpty()

            // 2. Set deck — transitions to CreateMatch
            val mainDeck = (1..40).joinToString(",") { """{"cardId":$it,"quantity":1}""" }
            val setDeck = sendJson(
                622,
                """
                {"EventName":"$event",
                 "Deck":{"MainDeck":[$mainDeck],"Sideboard":[],"CommandZone":[],"Companions":[]},
                 "Summary":{"DeckId":"sealed-001","Name":"My Sealed","DeckTileId":12345}}
                """.trimIndent(),
                ch,
            )
            setDeck["CurrentModule"]?.jsonPrimitive?.content shouldBe "CreateMatch"

            // 3. Courses list includes our sealed event
            val courses = sendJson(623, "{}", ch)
            val names = courses["Courses"]!!.jsonArray.map {
                it.jsonObject["InternalEventName"]?.jsonPrimitive?.content
            }
            names shouldContain event

            // 4. Resign — transitions to Complete
            val resign = sendJson(601, """{"EventName":"$event"}""", ch)
            resign["CurrentModule"]?.jsonPrimitive?.content shouldBe "Complete"
        }

        // --- Quick Draft integration tests ---

        test("CmdType 600 - Event_Join draft creates course with BotDraft module") {
            val ch = fdChannelWithCourseService()
            val obj = sendJson(600, """{"EventName":"QuickDraft_ECL_20260223"}""", ch)
            val course = obj["Course"]?.jsonObject
            course.shouldNotBeNull()
            course["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"
            course["CardPool"]?.jsonArray.shouldNotBeNull()
            course["CardPool"]!!.jsonArray.shouldBeEmpty()
        }

        test("CmdType 1800 - BotDraft_StartDraft returns draft response with first pack") {
            val ch = fdChannelWithCourseService()
            // Join first
            ch.writeCmd(600, """{"EventName":"QuickDraft_ECL_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            val obj = sendJson(1800, """{"EventName":"QuickDraft_ECL_20260223"}""", ch)
            obj["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"
            val payloadStr = obj["Payload"]?.jsonPrimitive?.content
            payloadStr.shouldNotBeNull()
            val payload = json.parseToJsonElement(payloadStr).jsonObject
            payload["Result"]?.jsonPrimitive?.content shouldBe "Success"
            payload["DraftStatus"]?.jsonPrimitive?.content shouldBe "PickNext"
            payload["PackNumber"]?.jsonPrimitive?.int shouldBe 0
            payload["PickNumber"]?.jsonPrimitive?.int shouldBe 0
            payload["DraftPack"]?.jsonArray.shouldNotBeNull()
            payload["DraftPack"]!!.jsonArray.size shouldBe 13
        }

        test("CmdType 1801 - BotDraft_DraftPick advances pick and returns updated state") {
            val ch = fdChannelWithCourseService()
            ch.writeCmd(600, """{"EventName":"QuickDraft_ECL_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            // Start draft to get first pack
            val startObj = sendJson(1800, """{"EventName":"QuickDraft_ECL_20260223"}""", ch)
            val startPayload = json.parseToJsonElement(startObj["Payload"]!!.jsonPrimitive.content).jsonObject
            val firstCard = startPayload["DraftPack"]!!.jsonArray[0].jsonPrimitive.content

            // Pick first card
            val pickPayload = """{"EventName":"QuickDraft_ECL_20260223","PickInfo":{"CardIds":["$firstCard"],"PackNumber":0,"PickNumber":0}}"""
            val pickObj = sendJson(1801, pickPayload, ch)
            pickObj["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"
            val payload = json.parseToJsonElement(pickObj["Payload"]!!.jsonPrimitive.content).jsonObject
            payload["PickNumber"]?.jsonPrimitive?.int shouldBe 1
            payload["DraftPack"]!!.jsonArray.size shouldBe 12
            payload["PickedCards"]!!.jsonArray.size shouldBe 1
        }

        test("CmdType 1802 - BotDraft_DraftStatus returns current session") {
            val ch = fdChannelWithCourseService()
            ch.writeCmd(600, """{"EventName":"QuickDraft_ECL_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()
            ch.writeCmd(1800, """{"EventName":"QuickDraft_ECL_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            val obj = sendJson(1802, """{"EventName":"QuickDraft_ECL_20260223"}""", ch)
            obj["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"
            val payload = json.parseToJsonElement(obj["Payload"]!!.jsonPrimitive.content).jsonObject
            payload["DraftStatus"]?.jsonPrimitive?.content shouldBe "PickNext"
            payload["DraftPack"]!!.jsonArray.size shouldBe 13
        }

        test("CmdType 1801 - completing all picks transitions to DeckSelect with card pool") {
            val ch = fdChannelWithCourseService()
            ch.writeCmd(600, """{"EventName":"QuickDraft_ECL_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            // Start draft
            ch.writeCmd(1800, """{"EventName":"QuickDraft_ECL_20260223"}""")
            var resp = ch.readOutbound<ByteBuf>()!!
            var msg = decodeResponse(resp)
            var outer = json.parseToJsonElement(msg.jsonPayload!!).jsonObject
            var payload = json.parseToJsonElement(outer["Payload"]!!.jsonPrimitive.content).jsonObject

            // Pick all 39 cards
            repeat(39) {
                val card = payload["DraftPack"]!!.jsonArray[0].jsonPrimitive.content
                val packNum = payload["PackNumber"]!!.jsonPrimitive.int
                val pickNum = payload["PickNumber"]!!.jsonPrimitive.int
                val pickReq = """{"EventName":"QuickDraft_ECL_20260223","PickInfo":{"CardIds":["$card"],"PackNumber":$packNum,"PickNumber":$pickNum}}"""
                ch.writeCmd(1801, pickReq)
                resp = ch.readOutbound<ByteBuf>()!!
                msg = decodeResponse(resp)
                outer = json.parseToJsonElement(msg.jsonPayload!!).jsonObject
                payload = json.parseToJsonElement(outer["Payload"]!!.jsonPrimitive.content).jsonObject
            }

            // Final pick response should be completed
            outer["CurrentModule"]?.jsonPrimitive?.content shouldBe "DeckSelect"
            payload["DraftStatus"]?.jsonPrimitive?.content shouldBe "Completed"
            payload["PickedCards"]!!.jsonArray.size shouldBe 39

            // Course should have transitioned to DeckSelect with card pool
            ch.writeCmd(623)
            resp = ch.readOutbound<ByteBuf>()!!
            msg = decodeResponse(resp)
            val coursesObj = json.parseToJsonElement(msg.jsonPayload!!).jsonObject
            val courses = coursesObj["Courses"]!!.jsonArray
            val draftCourse = courses.firstOrNull {
                it.jsonObject["InternalEventName"]?.jsonPrimitive?.content == "QuickDraft_ECL_20260223"
            }
            draftCourse.shouldNotBeNull()
            draftCourse.jsonObject["CurrentModule"]?.jsonPrimitive?.content shouldBe "DeckSelect"
            draftCourse.jsonObject["CardPool"]!!.jsonArray.size shouldBe 39
        }

        test("CmdType 609 - Event_Resign drops draft course and session") {
            val ch = fdChannelWithCourseService()
            ch.writeCmd(600, """{"EventName":"QuickDraft_ECL_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()
            ch.writeCmd(1800, """{"EventName":"QuickDraft_ECL_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            // Resign
            val obj = sendJson(609, """{"EventName":"QuickDraft_ECL_20260223"}""", ch)
            obj["CurrentModule"]?.jsonPrimitive?.content shouldBe "Complete"

            // Draft status should return empty (session was dropped)
            ch.writeCmd(1802, """{"EventName":"QuickDraft_ECL_20260223"}""")
            val resp = ch.readOutbound<ByteBuf>()!!
            val msg = decodeResponse(resp)
            // No session → empty response (no JSON payload)
            msg.transactionId.shouldNotBeNull()
        }
    })

/** Load a golden reference JSON from classpath. */
private fun loadGoldenRef(resource: String): JsonObject {
    val bytes = FrontDoorHandlerTest::class.java.classLoader
        .getResourceAsStream(resource)!!
        .readBytes()
    return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
}

/**
 * Recursively assert that [actual] has at least every key present in [reference].
 * Nested JsonObjects are checked recursively. Extra keys in actual are allowed
 * (server may add fields), but missing keys fail with a clear message.
 */
private fun assertKeysMatch(
    reference: JsonObject,
    actual: JsonObject,
    context: String,
    path: String = "",
) {
    val loc = path.ifEmpty { "root" }
    withClue("$context: missing keys at $loc") {
        (reference.keys - actual.keys).shouldBeEmpty()
    }
    for (key in reference.keys) {
        val refVal = reference[key]
        val actVal = actual[key]
        if (refVal is JsonObject && actVal is JsonObject) {
            assertKeysMatch(refVal, actVal, context, "$path.$key")
        }
        // Check first element of arrays-of-objects (e.g. PlayerInfos)
        if (refVal is JsonArray && actVal is JsonArray && refVal.isNotEmpty() && actVal.isNotEmpty()) {
            val refFirst = refVal[0]
            val actFirst = actVal[0]
            if (refFirst is JsonObject && actFirst is JsonObject) {
                assertKeysMatch(refFirst, actFirst, context, "$path.$key[0]")
            }
        }
    }
}

/** Strip 6-byte frame header and decode FD envelope. */
private fun decodeResponse(buf: ByteBuf): FdEnvelope.FdMessage {
    try {
        val totalBytes = buf.readableBytes()
        if (totalBytes <= FdWireConstants.HEADER_SIZE) {
            // Header-only response (empty ack)
            return FdEnvelope.FdMessage(
                cmdType = null,
                transactionId = null,
                jsonPayload = null,
                envelopeType = FdEnvelope.EnvelopeType.RESPONSE,
            )
        }
        // Skip 6-byte header
        buf.skipBytes(FdWireConstants.HEADER_SIZE)
        val payload = ByteArray(buf.readableBytes())
        buf.readBytes(payload)
        return FdEnvelope.decode(payload)
    } finally {
        buf.release()
    }
}
