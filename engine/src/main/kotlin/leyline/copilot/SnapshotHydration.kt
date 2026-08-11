package leyline.copilot

import forge.gamemodes.puzzle.Puzzle
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.config.MatchConfig
import leyline.game.codes.DetailKeys
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CounterType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import forge.game.card.CounterType as ForgeCounterType

/**
 * Hydrates a fresh headless Forge game from a serialized [GameStateMessage]
 * so the copilot decision stack can be consulted about a game this process is
 * not hosting.
 *
 * The GSM is projected into Forge's puzzle-format state text, applied via
 * [GameBridge.startPuzzle], and every hydrated card is rebound to the SOURCE
 * game's instanceId through [leyline.game.state.InstanceIdRegistry.bind].
 * After that, [CopilotProposalService.propose] answers the source game's own
 * pending prompt in the source game's id space — no translation layer.
 *
 * Fidelity notes (v0, sorcery-speed consults):
 * - carried: zone contents (battlefield/hand/graveyard/exile), tapped,
 *   summoning sickness, counters (from Counter annotations), life totals,
 *   turn number, active player, main phase; the consult seat's own in-flight
 *   stack cards ride the hand line so target consults can rebuild their ability
 * - not yet carried: resolved stack objects/triggers, opponent stack cards,
 *   mana pools, attack state, marked damage
 * - never carried (recomputed or unknowable): until-end-of-turn effects from
 *   already-resolved spells, dynamically granted abilities, delayed triggers
 * - libraries are placeholder cards: contents are hidden information and no
 *   consult family implemented so far depends on library order
 *
 * A lossy snapshot degrades one proposal, never a game: the next consult
 * re-hydrates from the then-current state.
 */
object SnapshotHydration {
    private val log = LoggerFactory.getLogger(SnapshotHydration::class.java)

    private const val LIBRARY_FILLER_CARD = "Mountain"
    private const val LIBRARY_FILLER_COUNT = 5

    /**
     * Hydrate a standalone [GameBridge] from [gsm]. The caller owns the bridge
     * and must call [GameBridge.teardownResources] when done with consults.
     */
    fun hydrate(
        gsm: GameStateMessage,
        consultSeat: Int,
        cardRepository: CardRepository,
        matchConfig: MatchConfig = MatchConfig(),
    ): GameBridge {
        val lines = toPuzzleLines(gsm, consultSeat, cardRepository)
        log.info("SnapshotHydration: hydrating from {} state lines", lines.size)
        GameBootstrap.initializeLocalization()
        val puzzle =
            Puzzle(
                mapOf(
                    "metadata" to
                        listOf(
                            "Name:Snapshot Consult",
                            "Goal:Win",
                            "Turns:99",
                            "Difficulty:Easy",
                            "Description:Hydrated from a serialized game state.",
                        ),
                    "state" to lines,
                ),
            )
        val bridge =
            GameBridge(
                matchConfig = matchConfig,
                cardRepository = cardRepository,
            )
        bridge.startPuzzle(puzzle)
        rebindInstanceIds(puzzle, bridge)
        enforceTapState(gsm, puzzle)
        return bridge
    }

    /**
     * Re-assert each battlefield card's tap state from the GSM after puzzle
     * application. Placement replays enters-the-battlefield replacements
     * (e.g. a pay-life-or-enter-tapped land) whose unanswered prompt defaults
     * can tap a card the source game shows untapped — the GSM is the truth.
     */
    private fun enforceTapState(
        gsm: GameStateMessage,
        puzzle: Puzzle,
    ) {
        val tappedByIid = gsm.gameObjectsList.associate { it.instanceId to it.isTapped }
        for ((sourceIid, card) in idToCardOf(puzzle)) {
            val tapped = tappedByIid[sourceIid] ?: continue
            if (card.isInPlay && card.isTapped != tapped) card.isTapped = tapped
        }
    }

    /**
     * Project [gsm] into Forge puzzle-format state lines. Exposed for tests
     * and fidelity debugging.
     */
    fun toPuzzleLines(
        gsm: GameStateMessage,
        consultSeat: Int,
        cardRepository: CardRepository,
    ): List<String> {
        val zonesById = gsm.zonesList.associateBy { it.zoneId }
        val objects =
            gsm.gameObjectsList.filter {
                it.type == GameObjectType.Card || it.type == GameObjectType.Token
            }

        fun zoneTypeOf(obj: GameObjectInfo): ZoneType? = zonesById[obj.zoneId]?.type

        fun zoneOwnerOf(obj: GameObjectInfo): Int = zonesById[obj.zoneId]?.ownerSeatId ?: 0

        fun prefix(seat: Int): String = if (seat == 1) "human" else "ai"

        val countersByIid = counterSpecsByInstanceId(gsm)

        fun cardEntry(obj: GameObjectInfo): String? {
            val name = cardRepository.findNameByGrpId(obj.grpId)
            if (name == null) {
                log.warn(
                    "SnapshotHydration: no card name for grpId={} (iid={}), dropping from snapshot",
                    obj.grpId,
                    obj.instanceId,
                )
                return null
            }
            return buildString {
                append(name)
                append("|Id:").append(obj.instanceId)
                if (obj.isTapped) append("|Tapped")
                if (obj.hasSummoningSickness) append("|SummonSick")
                countersByIid[obj.instanceId]?.let { append("|Counters:").append(it.joinToString(",")) }
            }
        }

        fun zoneLine(
            key: String,
            entries: List<String>,
        ): String? = if (entries.isEmpty()) null else "$key=${entries.joinToString(";")}"

        val lines = mutableListOf<String>()

        val activeSeat = gsm.turnInfo.activePlayer
        lines += "ActivePlayer=${if (activeSeat == 1) "Human" else "AI"}"
        lines += "ActivePhase=${mainPhaseOf(gsm)}"
        lines += "Turn=${gsm.turnInfo.turnNumber}"
        for (player in gsm.playersList) {
            lines += "${prefix(player.systemSeatNumber).replaceFirstChar { it.uppercase() }}Life=${player.lifeTotal}"
        }

        // Battlefield is a shared zone — group by controller.
        for (seat in listOf(1, 2)) {
            val battlefield =
                objects
                    .filter { zoneTypeOf(it) == ZoneType.Battlefield && it.controllerSeatId == seat }
                    .mapNotNull(::cardEntry)
            zoneLine("${prefix(seat)}battlefield", battlefield)?.let { lines += it }
        }

        // Hand: only the consulting seat's hand is visible information. The
        // seat's own in-flight stack cards ride along in the hand line — a
        // spell awaiting targets is still mid-cast from hand in Forge terms,
        // and carrying it lets target consults rebuild its ability instead of
        // degrading to the required-target fallback.
        val hand =
            objects
                .filter {
                    (zoneTypeOf(it) == ZoneType.Hand && zoneOwnerOf(it) == consultSeat) ||
                        (zoneTypeOf(it) == ZoneType.Stack && it.controllerSeatId == consultSeat)
                }.mapNotNull(::cardEntry)
        zoneLine("${prefix(consultSeat)}hand", hand)?.let { lines += it }

        // Graveyards and exile are public information for both seats.
        for (seat in listOf(1, 2)) {
            for ((zoneType, key) in listOf(ZoneType.Graveyard to "graveyard", ZoneType.Exile to "exile")) {
                val cards =
                    objects
                        .filter { zoneTypeOf(it) == zoneType && zoneOwnerOf(it) == seat }
                        .mapNotNull(::cardEntry)
                zoneLine("${prefix(seat)}$key", cards)?.let { lines += it }
            }
        }

        // Libraries: hidden information — placeholder cards keep draws legal.
        val filler = List(LIBRARY_FILLER_COUNT) { LIBRARY_FILLER_CARD }.joinToString(";")
        lines += "humanlibrary=$filler"
        lines += "ailibrary=$filler"

        return lines
    }

    /**
     * Counter state rides Counter annotations (state tier), one annotation per
     * (object, counter type). Emitted as `TYPE=N` specs the puzzle-format
     * counter parser accepts; counter types Forge doesn't know are dropped
     * with a log line rather than failing the whole hydration.
     */
    private fun counterSpecsByInstanceId(gsm: GameStateMessage): Map<Int, List<String>> {
        val result = mutableMapOf<Int, MutableList<String>>()
        for (ann in gsm.annotationsList + gsm.persistentAnnotationsList) {
            if (AnnotationType.Counter_803b !in ann.typeList) continue
            val count =
                ann.detailsList
                    .firstOrNull { it.key == DetailKeys.COUNT }
                    ?.valueInt32List
                    ?.firstOrNull() ?: continue
            val typeNumber =
                ann.detailsList
                    .firstOrNull { it.key == DetailKeys.COUNTER_TYPE }
                    ?.valueInt32List
                    ?.firstOrNull() ?: continue
            val iid = ann.affectedIdsList.firstOrNull() ?: continue
            if (count <= 0) continue
            val protoName = CounterType.forNumber(typeNumber)?.name
            val forgeType = protoName?.let { name -> runCatching { ForgeCounterType.getType(name) }.getOrNull() }
            if (forgeType == null) {
                log.warn("SnapshotHydration: unknown counter type {} on iid={}, dropping", typeNumber, iid)
                continue
            }
            result.getOrPut(iid) { mutableListOf() } += "$protoName=$count"
        }
        return result
    }

    /**
     * Project the GSM phase/step onto Forge's puzzle phase vocabulary. The
     * declare-blockers step hydrates as its combat phase so block evaluation
     * runs with the phase context the live game has. Attack consults stay on
     * Main1 deliberately: the AI's "would I attack" judgement is main-phase
     * shaped, and a game hydrated already inside declare-attackers declines
     * every attack. Anything else not recognisably Main2 hydrates as Main1
     * (sorcery-speed consults).
     */
    private fun mainPhaseOf(gsm: GameStateMessage): String =
        when {
            gsm.turnInfo.step.name
                .startsWith("DeclareBlock") -> "COMBAT_DECLARE_BLOCKERS"
            gsm.turnInfo.phase.name
                .contains("Main2", ignoreCase = true) -> "MAIN2"
            else -> "MAIN1"
        }

    /**
     * Rebind every hydrated card carrying an `Id:` cross-reference to the
     * source game's instanceId. GameState keeps that mapping in its private
     * `idToCard`; puzzle application is already reflection-based upstream of
     * here, so reading it reflectively follows the established seam.
     */
    private fun rebindInstanceIds(
        puzzle: Puzzle,
        bridge: GameBridge,
    ) {
        val idToCard = idToCardOf(puzzle)
        for ((sourceIid, card) in idToCard) {
            bridge.ids.bind(ForgeCardId(card.id), InstanceId(sourceIid))
        }
        log.info("SnapshotHydration: rebound {} instanceIds to source ids", idToCard.size)
    }

    private fun idToCardOf(puzzle: Puzzle): Map<Int, forge.game.card.Card> {
        val field = forge.game.GameState::class.java.getDeclaredField("idToCard")
        field.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        return field.get(puzzle) as Map<Int, forge.game.card.Card>
    }
}
