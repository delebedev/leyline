package leyline.arena

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `arena board` — unified board state merging debug API + OCR + zone geometry.
 *
 * Outputs a single JSON object with everything the agent needs to decide and act:
 * cards grouped by zone with names, instanceIds, P/T, tap state, and estimated
 * screen coordinates where possible.
 */
object Board {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Zone rects in 960x568 logical coords (from arena-annotate)
    private val ZONE_RECTS = mapOf(
        "opp_battlefield" to Rect(0, 135, 960, 255),
        "stack" to Rect(0, 255, 960, 295),
        "our_battlefield" to Rect(0, 295, 960, 445),
        "our_hand" to Rect(0, 500, 800, 568),
    )

    // Proto zone IDs — must match ZoneIds.kt in matchdoor
    private const val STACK = 27
    private const val BATTLEFIELD = 28
    private const val EXILE = 29
    private const val P1_HAND = 31
    private const val P1_LIBRARY = 32
    private const val P1_GRAVEYARD = 33
    private const val P2_HAND = 35
    private const val P2_LIBRARY = 36
    private const val P2_GRAVEYARD = 37

    // Hand card y-center for OCR correlation
    private const val HAND_Y_CENTER = 530
    private const val HAND_X_MIN = 60
    private const val HAND_X_MAX = 720

    fun run(args: List<String>) {
        val withOcr = "--no-ocr" !in args

        // 1. Fetch match state
        val stateJson = fetch("/api/state")
        if (stateJson == null) {
            System.err.println("Debug API unavailable")
            throw SystemExitException(1)
        }

        val state = json.parseToJsonElement(stateJson).jsonObject
        val matchId = state["matchId"]?.jsonPrimitive?.contentOrNull
        if (matchId == null) {
            // No active match — return minimal state
            println(json.encodeToString(BoardState(match = MatchInfo())))
            return
        }

        // 2. Fetch latest game state snapshot
        val gsJson = fetch("/api/game-states")
        val snapshots = gsJson?.let { json.parseToJsonElement(it) }
            ?.jsonObject?.get("data")?.jsonArray

        val latest = snapshots?.lastOrNull()?.jsonObject

        // 3. Optionally get OCR
        val ocrItems = if (withOcr) runOcr() else emptyList()

        // 4. Build board state
        val board = buildBoard(state, latest, ocrItems)
        println(json.encodeToString(board))
    }

    private fun buildBoard(
        state: JsonObject,
        snapshot: JsonObject?,
        ocrItems: List<OcrItem>,
    ): BoardState {
        val phase = state["phase"]?.jsonPrimitive?.contentOrNull
        val turn = state["turn"]?.jsonPrimitive?.intOrNull ?: 0
        val activePlayer = state["activePlayer"]?.jsonPrimitive?.contentOrNull
        val gameOver = state["gameOver"]?.jsonPrimitive?.toString() == "true"

        // Parse players for life totals
        val players = snapshot?.get("players")?.jsonArray?.map { p ->
            val obj = p.jsonObject
            obj["seatId"]!!.jsonPrimitive.int to obj["life"]!!.jsonPrimitive.int
        }?.toMap() ?: emptyMap()

        // Parse objects grouped by zone
        val objects = snapshot?.get("objects")?.jsonObject?.values?.map { parseObject(it) }
            ?: emptyList()

        // Parse available actions
        val actions = snapshot?.get("actions")?.jsonArray?.map { parseAction(it) }
            ?: emptyList()

        // Seat 1 = human (our seat), Seat 2 = AI
        val ourSeat = 1
        val oppSeat = 2

        // Group objects by zone
        val ourHand = objects.filter { it.zoneId == P1_HAND && it.ownerSeatId == ourSeat }
        val oppHand = objects.filter { it.zoneId == P2_HAND && it.ownerSeatId == oppSeat }
        val battlefield = objects.filter { it.zoneId == BATTLEFIELD }
        val ourBf = battlefield.filter { it.controllerSeatId == ourSeat }
        val oppBf = battlefield.filter { it.controllerSeatId == oppSeat }
        val stack = objects.filter { it.zoneId == STACK }
        val ourGrave = objects.filter { it.zoneId == P1_GRAVEYARD && it.ownerSeatId == ourSeat }
        val oppGrave = objects.filter { it.zoneId == P2_GRAVEYARD && it.ownerSeatId == oppSeat }
        val exile = objects.filter { it.zoneId == EXILE }

        // Correlate hand cards with OCR x-positions
        val handWithCoords = correlateHandCards(ourHand, ocrItems)

        // Annotate battlefield cards with estimated regions
        val ourBfAnnotated = ourBf.map { it.copy(screenRegion = "our_battlefield") }
        val oppBfAnnotated = oppBf.map { it.copy(screenRegion = "opp_battlefield") }

        // Build action index: which cards have available actions
        val actionableIds = actions.map { it.instanceId }.toSet()

        return BoardState(
            match = MatchInfo(
                phase = phase,
                turn = turn,
                activePlayer = activePlayer,
                gameOver = gameOver,
            ),
            life = LifeInfo(ours = players[ourSeat] ?: 0, theirs = players[oppSeat] ?: 0),
            hand = handWithCoords.map { card ->
                card.copy(hasAction = card.instanceId in actionableIds)
            },
            our_battlefield = ourBfAnnotated.map { card ->
                card.copy(hasAction = card.instanceId in actionableIds)
            },
            opp_battlefield = oppBfAnnotated,
            stack = stack,
            our_graveyard = CardCount(ourGrave.size, ourGrave.map { it.name ?: "?" }),
            opp_graveyard = CardCount(oppGrave.size, oppGrave.map { it.name ?: "?" }),
            exile = CardCount(exile.size, exile.map { it.name ?: "?" }),
            opp_hand_count = oppHand.size,
            our_library_count = objects.count { it.zoneId == P1_LIBRARY },
            opp_library_count = objects.count { it.zoneId == P2_LIBRARY },
            actions = actions,
        )
    }

    /**
     * Correlate hand cards (from protocol) with OCR text positions.
     *
     * Strategy: hand cards render left-to-right. OCR detects text (mana costs,
     * P/T) in the hand region. Cluster OCR items by x-coord, assign to hand
     * cards in order. If OCR finds fewer clusters than cards, estimate spacing.
     */
    private fun correlateHandCards(
        handCards: List<CardInfo>,
        ocrItems: List<OcrItem>,
    ): List<CardInfo> {
        if (handCards.isEmpty()) return emptyList()

        // Find OCR items in hand zone region
        val handOcr = ocrItems.filter { it.cy > 490 && it.cx in HAND_X_MIN..HAND_X_MAX }

        // Cluster OCR x-positions (items within 25px are same card)
        val centers = if (handOcr.isNotEmpty()) {
            val xs = handOcr.map { it.cx }.sorted()
            val clusters = mutableListOf(xs.first())
            for (x in xs.drop(1)) {
                if (x - clusters.last() > 25) {
                    clusters.add(x)
                } else {
                    // Average with last cluster center
                    clusters[clusters.lastIndex] = (clusters.last() + x) / 2
                }
            }
            clusters
        } else {
            // Fallback: estimate evenly spaced across hand region
            val count = handCards.size
            val spacing = (HAND_X_MAX - HAND_X_MIN) / (count + 1)
            (1..count).map { HAND_X_MIN + it * spacing }
        }

        // Assign x-coords to hand cards left-to-right
        return handCards.mapIndexed { i, card ->
            val x = if (i < centers.size) {
                centers[i]
            } else {
                // More cards than OCR clusters — estimate
                val lastX = centers.lastOrNull() ?: 400
                lastX + (i - centers.size + 1) * 40
            }
            card.copy(estimatedX = x, estimatedY = HAND_Y_CENTER)
        }
    }

    private fun parseObject(el: JsonElement): CardInfo {
        val obj = el.jsonObject
        return CardInfo(
            instanceId = obj["instanceId"]!!.jsonPrimitive.int,
            grpId = obj["grpId"]?.jsonPrimitive?.intOrNull ?: 0,
            name = obj["name"]?.jsonPrimitive?.contentOrNull,
            type = obj["type"]?.jsonPrimitive?.contentOrNull,
            zoneId = obj["zoneId"]?.jsonPrimitive?.intOrNull ?: 0,
            ownerSeatId = obj["ownerSeatId"]?.jsonPrimitive?.intOrNull ?: 0,
            controllerSeatId = obj["controllerSeatId"]?.jsonPrimitive?.intOrNull ?: 0,
            power = obj["power"]?.jsonPrimitive?.intOrNull,
            toughness = obj["toughness"]?.jsonPrimitive?.intOrNull,
            isTapped = obj["isTapped"]?.jsonPrimitive?.toString() == "true",
            hasSummoningSickness = obj["hasSummoningSickness"]?.jsonPrimitive?.toString() == "true",
            damage = obj["damage"]?.jsonPrimitive?.intOrNull ?: 0,
            loyalty = obj["loyalty"]?.jsonPrimitive?.intOrNull,
            attackState = obj["attackState"]?.jsonPrimitive?.contentOrNull,
            blockState = obj["blockState"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseAction(el: JsonElement): ActionInfo {
        val obj = el.jsonObject
        return ActionInfo(
            actionType = obj["actionType"]!!.jsonPrimitive.toString().trim('"'),
            instanceId = obj["instanceId"]?.jsonPrimitive?.intOrNull ?: 0,
            grpId = obj["grpId"]?.jsonPrimitive?.intOrNull ?: 0,
            name = obj["name"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun runOcr(): List<OcrItem> {
        return try {
            val img = "/tmp/arena/_board_ocr.png"
            java.io.File(img).parentFile?.mkdirs()
            val bounds = Shell.captureWindow(img)
            if (bounds == null) return emptyList()
            val r = Shell.ocr(img, "--json")
            java.io.File(img).delete()
            if (!r.ok) return emptyList()

            val arr = json.parseToJsonElement(r.stdout).jsonArray
            arr.map { el ->
                val obj = el.jsonObject
                OcrItem(
                    text = obj["text"]!!.jsonPrimitive.toString().trim('"'),
                    cx = obj["cx"]!!.jsonPrimitive.int,
                    cy = obj["cy"]!!.jsonPrimitive.int,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetch(path: String): String? = try {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8090$path"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 200) resp.body() else null
    } catch (_: Exception) {
        null
    }

    // --- Data classes ---

    @Serializable
    data class BoardState(
        val match: MatchInfo,
        val life: LifeInfo = LifeInfo(),
        val hand: List<CardInfo> = emptyList(),
        val our_battlefield: List<CardInfo> = emptyList(),
        val opp_battlefield: List<CardInfo> = emptyList(),
        val stack: List<CardInfo> = emptyList(),
        val our_graveyard: CardCount = CardCount(),
        val opp_graveyard: CardCount = CardCount(),
        val exile: CardCount = CardCount(),
        val opp_hand_count: Int = 0,
        val our_library_count: Int = 0,
        val opp_library_count: Int = 0,
        val actions: List<ActionInfo> = emptyList(),
    )

    @Serializable
    data class MatchInfo(
        val phase: String? = null,
        val turn: Int = 0,
        val activePlayer: String? = null,
        val gameOver: Boolean = false,
    )

    @Serializable
    data class LifeInfo(val ours: Int = 0, val theirs: Int = 0)

    @Serializable
    data class CardInfo(
        val instanceId: Int = 0,
        val grpId: Int = 0,
        val name: String? = null,
        val type: String? = null,
        val zoneId: Int = 0,
        val ownerSeatId: Int = 0,
        val controllerSeatId: Int = 0,
        val power: Int? = null,
        val toughness: Int? = null,
        val isTapped: Boolean = false,
        val hasSummoningSickness: Boolean = false,
        val damage: Int = 0,
        val loyalty: Int? = null,
        val attackState: String? = null,
        val blockState: String? = null,
        val estimatedX: Int? = null,
        val estimatedY: Int? = null,
        val screenRegion: String? = null,
        val hasAction: Boolean = false,
    )

    @Serializable
    data class CardCount(val count: Int = 0, val cards: List<String> = emptyList())

    @Serializable
    data class ActionInfo(
        val actionType: String,
        val instanceId: Int = 0,
        val grpId: Int = 0,
        val name: String? = null,
    )

    data class OcrItem(val text: String, val cx: Int, val cy: Int)

    data class Rect(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
}
