package leyline.frontdoor

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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.FdTag
import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.SqlitePlayerStore
import leyline.frontdoor.service.CollectionService
import leyline.frontdoor.service.DeckService
import leyline.frontdoor.service.MatchmakingService
import leyline.frontdoor.service.PlayerService
import leyline.frontdoor.wire.FdResponseWriter
import leyline.protocol.ClientFrameDecoder
import leyline.protocol.FdEnvelope
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
                ClientFrameDecoder(),
                FrontDoorHandler(
                    playerId = PlayerId(testPlayerId),
                    deckService = deckService,
                    playerService = playerService,
                    matchmaking = matchmakingService,
                    collectionService = CollectionService { emptyList() },
                    writer = writer,
                    golden = golden,
                ),
            )
            channel = ch
            return ch
        }

        /** Send a framed Cmd envelope, read back first response as FdMessage. */
        fun EmbeddedChannel.sendCmd(cmdType: Int, payload: String? = "{}"): FdEnvelope.FdMessage {
            val txId = UUID.randomUUID().toString()
            val envelope = FdEnvelope.encodeCmd(cmdType, txId, payload ?: "{}")
            val header = FdEnvelope.buildOutgoingHeader(envelope.size)
            val buf = Unpooled.buffer(header.size + envelope.size)
            buf.writeBytes(header)
            buf.writeBytes(envelope)
            writeInbound(buf)

            val resp = readOutbound<ByteBuf>() ?: error("No response for CmdType $cmdType")
            return decodeResponse(resp)
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

        /** Send a cmd and return ALL responses (for 612 which sends ack + push). */
        fun EmbeddedChannel.sendCmdAll(cmdType: Int, payload: String? = "{}"): List<FdEnvelope.FdMessage> {
            val txId = UUID.randomUUID().toString()
            val envelope = FdEnvelope.encodeCmd(cmdType, txId, payload ?: "{}")
            val header = FdEnvelope.buildOutgoingHeader(envelope.size)
            val buf = Unpooled.buffer(header.size + envelope.size)
            buf.writeBytes(header)
            buf.writeBytes(envelope)
            writeInbound(buf)
            return readAllResponses()
        }

        /** Send a cmd, parse first response as JsonObject. */
        fun sendJson(cmdType: Int, payload: String? = "{}"): kotlinx.serialization.json.JsonObject {
            val ch = fdChannel()
            val msg = ch.sendCmd(cmdType, payload)
            return json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonObject
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

        test("CmdType 612 - eventName propagates to MatchCreated EventId") {
            val ch = fdChannel()
            val responses = ch.sendCmdAll(612, """{"deckId":"$testDeckId","eventName":"Historic_Ladder"}""")
            responses.size shouldBe 2
            val matchInfo = json.parseToJsonElement(responses[1].jsonPayload!!)
                .jsonObject["MatchInfoV3"]?.jsonObject
            matchInfo.shouldNotBeNull()
            matchInfo["EventId"]?.jsonPrimitive?.content shouldBe "Historic_Ladder"
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

        test("CmdType 9999 - unknown returns response without error") {
            val ch = fdChannel()
            val msg = ch.sendCmd(9999)
            msg.transactionId.shouldNotBeNull()
            // Empty response is fine — just shouldn't crash
        }
    })

/** Load a golden reference JSON from classpath. */
private fun loadGoldenRef(resource: String): kotlinx.serialization.json.JsonObject {
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
    reference: kotlinx.serialization.json.JsonObject,
    actual: kotlinx.serialization.json.JsonObject,
    context: String,
    path: String = "",
) {
    val loc = path.ifEmpty { "root" }
    io.kotest.assertions.withClue("$context: missing keys at $loc") {
        (reference.keys - actual.keys).shouldBeEmpty()
    }
    for (key in reference.keys) {
        val refVal = reference[key]
        val actVal = actual[key]
        if (refVal is kotlinx.serialization.json.JsonObject && actVal is kotlinx.serialization.json.JsonObject) {
            assertKeysMatch(refVal, actVal, context, "$path.$key")
        }
    }
}

/** Strip 6-byte frame header and decode FD envelope. */
private fun decodeResponse(buf: ByteBuf): FdEnvelope.FdMessage {
    try {
        val totalBytes = buf.readableBytes()
        if (totalBytes <= ClientFrameDecoder.HEADER_SIZE) {
            // Header-only response (empty ack)
            return FdEnvelope.FdMessage(
                cmdType = null,
                transactionId = null,
                jsonPayload = null,
                envelopeType = FdEnvelope.EnvelopeType.RESPONSE,
            )
        }
        // Skip 6-byte header
        buf.skipBytes(ClientFrameDecoder.HEADER_SIZE)
        val payload = ByteArray(buf.readableBytes())
        buf.readBytes(payload)
        return FdEnvelope.decode(payload)
    } finally {
        buf.release()
    }
}
