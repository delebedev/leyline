package leyline.copilot

import forge.game.Game
import forge.game.keyword.Keyword
import forge.gamemodes.puzzle.Puzzle
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.game.codes.DetailKeys
import leyline.game.data.CardRepository
import leyline.game.data.EvergreenKeywords
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AttackState
import wotc.mtgo.gre.external.messaging.Messages.CounterType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.SubType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import forge.card.CardType as ForgeCardType
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
 * Fidelity notes (sorcery-speed consults):
 * - carried: zone contents (battlefield/hand/graveyard/exile), tapped,
 *   summoning sickness, counters, marked damage, attachments, life totals,
 *   combat assignments, turn number, active player, and phase; the consult seat's own
 *   in-flight stack cards ride the hand line so target consults can rebuild
 *   their ability
 * - not yet carried: resolved stack objects/triggers, opponent stack cards,
 *   mana pools and delayed triggers
 * - recomputed where possible: dynamic abilities from Forge card scripts;
 *   observable current power/toughness is then reconciled, while effect
 *   provenance and duration remain approximate
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
    private const val FACE_DOWN_CARD_NAME = "Face-down creature"

    /**
     * Hydrate a standalone [GameBridge] from [gsm]. The caller owns the bridge
     * and must call [GameBridge.teardownResources] when done with consults.
     */
    fun hydrate(
        gsm: GameStateMessage,
        consultSeat: Int,
        cardRepository: CardRepository,
        engineSettings: EngineSettings = EngineSettings(),
    ): GameBridge = hydrateWithReport(gsm, consultSeat, cardRepository, engineSettings).bridge

    fun hydrateWithReport(
        gsm: GameStateMessage,
        consultSeat: Int,
        cardRepository: CardRepository,
        engineSettings: EngineSettings = EngineSettings(),
    ): HydratedSnapshot {
        val projection = project(gsm, consultSeat, cardRepository)
        val lines = projection.lines
        log.info("Hydrating snapshot from {} state lines", lines.size)
        GameBootstrap.initializeLocalization()
        val bridge =
            GameBridge(
                engineSettings = engineSettings,
                cardRepository = cardRepository,
            )
        lateinit var puzzle: Puzzle
        try {
            puzzle =
                object : Puzzle(
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
                ) {
                    override fun beforeStateEffects(game: Game) {
                        importCurrentState(gsm, this, bridge, consultSeat, projection)
                    }
                }
            bridge.startStaticPuzzle(
                puzzle,
                controlledSeat = SeatId(consultSeat),
                beforeRuntimeStart = {},
            )
            return HydratedSnapshot(
                bridge = bridge,
                fidelity = verifyFidelity(gsm, puzzle, projection, bridge),
            )
        } catch (failure: Throwable) {
            bridge.teardownResources()
            throw failure
        }
    }

    /** Commit every snapshot-derived mutation before Forge settles the imported place once. */
    private fun importCurrentState(
        gsm: GameStateMessage,
        puzzle: Puzzle,
        bridge: GameBridge,
        consultSeat: Int,
        projection: SnapshotProjection,
    ) {
        val game = checkNotNull(bridge.getGame())
        game.triggerHandler.setSuppressAllTriggers(true)
        game.stack.setResolving(true)
        try {
            rebindInstanceIds(puzzle, bridge)
            reconcileVisibleTypes(gsm, puzzle)
            restoreAttachments(gsm, puzzle, bridge, projection)
            restoreGrantedKeywords(gsm, puzzle)
            game.action.checkStaticAbilities(false)
            reconcilePowerAndToughness(gsm, puzzle)
            enforceTapState(gsm, puzzle)
            restoreMarkedDamage(gsm, puzzle)
            restoreCombatState(gsm, puzzle, bridge, consultSeat)
        } finally {
            game.stack.setResolving(false)
            game.triggerHandler.setSuppressAllTriggers(false)
        }
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
     * Reconcile the disposable advisor's current type and power/toughness to the GSM.
     * Puzzle application already rebuilds printed characteristics, counters,
     * attachments, and static effects. The residual is observable state from
     * effects whose source or duration is not reconstructable from one Full
     * GSM. It is deliberately a current-state boost; the fidelity report keeps
     * dynamic effect semantics classified as approximated.
     */
    private fun reconcileVisibleTypes(
        gsm: GameStateMessage,
        puzzle: Puzzle,
    ) {
        val idToCard = idToCardOf(puzzle)
        for (source in gsm.gameObjectsList) {
            val card = idToCard[source.instanceId] ?: continue
            visibleTypeOf(source)?.let { visibleType ->
                if (!visibleType.matches(card.type)) {
                    card.addChangedCardTypes(
                        visibleType,
                        ForgeCardType(card.type),
                        false,
                        emptySet(),
                        card.game.nextTimestamp,
                        0L,
                        true,
                        false,
                    )
                }
            }
        }
    }

    private fun reconcilePowerAndToughness(
        gsm: GameStateMessage,
        puzzle: Puzzle,
    ) {
        val idToCard = idToCardOf(puzzle)
        for (source in gsm.gameObjectsList) {
            val card = idToCard[source.instanceId] ?: continue
            if (!card.isInPlay || !isCreature(source)) continue
            val powerDelta = if (source.hasPower()) source.power.value - card.netPower else 0
            val toughnessDelta = if (source.hasToughness()) source.toughness.value - card.netToughness else 0
            if (powerDelta != 0 || toughnessDelta != 0) {
                card.addPTBoost(powerDelta, toughnessDelta, card.game.nextTimestamp, 0L)
            }
        }
    }

    private fun restoreAttachments(
        gsm: GameStateMessage,
        puzzle: Puzzle,
        bridge: GameBridge,
        projection: SnapshotProjection,
    ) {
        val cards = idToCardOf(puzzle)
        val playerSeats = gsm.playersList.mapTo(mutableSetOf()) { it.systemSeatNumber }
        for ((sourceId, targetId) in projection.allAttachmentTargetsByIid) {
            val source = cards[sourceId] ?: continue
            val target =
                if (targetId in projection.projectedIds) {
                    cards[targetId]
                } else {
                    targetId.takeIf { it in playerSeats }?.let { bridge.getPlayer(SeatId(it)) }
                }
            if (target != null && source.entityAttachedTo !== target) {
                if (source.hasKeyword(Keyword.RECONFIGURE)) {
                    // Import the existing relation without replaying Reconfigure's attach-time ability path.
                    source.setEntityAttachedTo(target)
                    target.addAttachedCard(source)
                } else {
                    source.attachToEntity(target, null, true)
                }
            }
        }
    }

    /** Restore recognized temporary survival grants before marked damage is committed. */
    private fun restoreGrantedKeywords(
        gsm: GameStateMessage,
        puzzle: Puzzle,
    ) {
        val cards = idToCardOf(puzzle)
        for (annotation in gsm.persistentAnnotationsList) {
            if (AnnotationType.AddAbility_af5a !in annotation.typeList) continue
            val keywords =
                annotation.detailsList
                    .filter { it.key == DetailKeys.GRPID }
                    .flatMap { it.valueInt32List }
                    .mapNotNull(EvergreenKeywords::fromAbilityId)
            if (keywords.isEmpty()) continue
            for (affectedId in annotation.affectedIdsList) {
                val card = cards[affectedId] ?: continue
                card.addChangedCardKeywords(keywords, null, false, card.game.nextTimestamp, null)
            }
        }
    }

    private fun restoreMarkedDamage(
        gsm: GameStateMessage,
        puzzle: Puzzle,
    ) {
        val cards = idToCardOf(puzzle)
        for (source in gsm.gameObjectsList) {
            if (source.damage > 0) cards[source.instanceId]?.damage = source.damage
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
    ): List<String> = project(gsm, consultSeat, cardRepository).lines

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One projection transaction keeps shared identity sets local.
    private fun project(
        gsm: GameStateMessage,
        consultSeat: Int,
        cardRepository: CardRepository,
    ): SnapshotProjection {
        val zonesById = gsm.zonesList.associateBy { it.zoneId }
        val objects =
            gsm.gameObjectsList.filter {
                it.type == GameObjectType.Card || it.type == GameObjectType.Token
            }

        fun zoneTypeOf(obj: GameObjectInfo): ZoneType? = zonesById[obj.zoneId]?.type

        fun zoneOwnerOf(obj: GameObjectInfo): Int = zonesById[obj.zoneId]?.ownerSeatId ?: 0

        fun prefix(seat: Int): String = "p${seat - 1}"

        val countersByIid = counterSpecsByInstanceId(gsm)
        val playerSeats = gsm.playersList.mapTo(mutableSetOf()) { it.systemSeatNumber }
        val battlefieldIds =
            objects
                .filter { zoneTypeOf(it) == ZoneType.Battlefield }
                .mapTo(mutableSetOf()) { it.instanceId }
        val allAttachmentTargetsByIid =
            gsm.persistentAnnotationsList
                .filter { AnnotationType.Attachment in it.typeList }
                .mapNotNull { ann ->
                    val source = ann.affectorId.takeIf { it in battlefieldIds } ?: return@mapNotNull null
                    val target = ann.affectedIdsList.singleOrNull() ?: return@mapNotNull null
                    target
                        .takeIf { it in battlefieldIds || it in playerSeats }
                        ?.let { source to it }
                }.toMap()
        val resolvableIds =
            objects
                .filter {
                    it.instanceId in battlefieldIds &&
                        (it.isFacedown || cardRepository.findNameByGrpId(it.grpId) != null)
                }.mapTo(mutableSetOf()) { it.instanceId }
        val attachmentTargetsByIid =
            allAttachmentTargetsByIid.filter { (sourceId, targetId) ->
                sourceId in resolvableIds && (targetId in resolvableIds || targetId in playerSeats)
            }
        val unresolvedIds = mutableSetOf<Int>()
        val projectedIds = mutableSetOf<Int>()

        fun tokenEntry(
            obj: GameObjectInfo,
            name: String,
        ): String {
            val types =
                (obj.superTypesList + obj.cardTypesList + obj.subtypesList)
                    .map { it.name.substringBefore('_') }
                    .filterNot { it.startsWith("None") }
                    .ifEmpty { listOf("Creature") }
            val color =
                obj.colorList
                    .singleOrNull()
                    ?.name
                    ?.substringBefore('_')
            val keywords = if (SubType.Role in obj.subtypesList) "Enchant:Creature" else ""
            return buildString {
                append("t:").append(name.replace(',', ' '))
                append(",P:").append(obj.power.value)
                append(",T:").append(obj.toughness.value)
                append(",Cost:0")
                color?.let { append(",Color:").append(it) }
                append(",Types:").append(types.joinToString("-"))
                append(",Keywords:").append(keywords).append(",Image:")
            }
        }

        fun cardEntry(obj: GameObjectInfo): String? {
            if (obj.type == GameObjectType.Token && obj.isCopy && SubType.Room in obj.subtypesList) {
                unresolvedIds += obj.instanceId
                log.warn("Copied Room token iid={} cannot be represented safely", obj.instanceId)
                return null
            }
            val entry =
                if (obj.isFacedown) {
                    tokenEntry(obj, FACE_DOWN_CARD_NAME)
                } else {
                    cardRepository.findNameByGrpId(obj.grpId)?.let { name ->
                        if (obj.type == GameObjectType.Token) tokenEntry(obj, name) else name
                    }
                }
            if (entry == null) {
                unresolvedIds += obj.instanceId
                log.warn(
                    "No card name for grpId={} (iid={}), dropping from snapshot",
                    obj.grpId,
                    obj.instanceId,
                )
                return null
            }
            projectedIds += obj.instanceId
            return buildString {
                append(entry)
                append("|Id:").append(obj.instanceId)
                if (obj.isTapped) append("|Tapped")
                if (obj.hasSummoningSickness) append("|SummonSick")
                if (obj.attackState == AttackState.Attacking) {
                    append("|Attacking")
                    obj.attackInfo.targetId
                        .takeIf { it in battlefieldIds }
                        ?.let { append(":").append(it) }
                }
                countersByIid[obj.instanceId]?.let { append("|Counters:").append(it.joinToString(",")) }
                // Combat initializes before the late import hook, so resolvable card attachments must exist here too.
                attachmentTargetsByIid[obj.instanceId]
                    ?.takeIf { it in resolvableIds && SubType.Aura in obj.subtypesList }
                    ?.let { append("|AttachedTo:").append(it) }
            }
        }

        fun zoneLine(
            key: String,
            entries: List<String>,
        ): String? = if (entries.isEmpty()) null else "$key=${entries.joinToString(";")}"

        val lines = mutableListOf<String>()

        val activeSeat =
            sequenceOf(gsm.turnInfo.activePlayer, gsm.turnInfo.decisionPlayer, consultSeat)
                .firstOrNull { it in playerSeats }
                ?: playerSeats.first()
        lines += "ActivePlayer=P${activeSeat - 1}"
        val projectedPhase = mainPhaseOf(gsm)
        lines += "ActivePhase=$projectedPhase"
        lines += "Turn=${gsm.turnInfo.turnNumber.coerceAtLeast(1)}"
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

        val dynamicEffectCount =
            gsm.persistentAnnotationsList.count { ann ->
                ann.typeList.any {
                    it == AnnotationType.AddAbility_af5a ||
                        it == AnnotationType.RemoveAbility ||
                        it == AnnotationType.LayeredEffect
                }
            }
        val manaPoolCount = gsm.playersList.sumOf { it.manaPoolCount }
        val stackCount = objects.count { zoneTypeOf(it) == ZoneType.Stack }
        val combat =
            gsm.turnInfo.phase.name
                .contains("Combat", ignoreCase = true)
        val exactPhase =
            gsm.turnInfo.phase.name
                .contains("Main", ignoreCase = true) ||
                projectedPhase.startsWith("COMBAT_")
        return SnapshotProjection(
            lines = lines,
            projectedIds = projectedIds,
            allAttachmentTargetsByIid = allAttachmentTargetsByIid,
            attachmentTargetsByIid = attachmentTargetsByIid,
            unresolvedIds = unresolvedIds,
            dynamicEffectCount = dynamicEffectCount,
            manaPoolCount = manaPoolCount,
            stackCount = stackCount,
            combat = combat,
            exactPhase = exactPhase,
        )
    }

    private fun verifyFidelity(
        gsm: GameStateMessage,
        puzzle: Puzzle,
        projection: SnapshotProjection,
        bridge: GameBridge,
    ): SnapshotFidelityReport {
        val idToCard = idToCardOf(puzzle)
        val damageSources = gsm.gameObjectsList.filter { it.instanceId in projection.projectedIds && it.damage > 0 }
        val damageMismatchIds =
            damageSources
                .filter { source -> idToCard[source.instanceId]?.damage != source.damage }
                .map { it.instanceId }
        val attachmentMismatchIds =
            buildList {
                addAll(projection.allAttachmentTargetsByIid.keys - projection.attachmentTargetsByIid.keys)
                addAll(
                    projection.attachmentTargetsByIid
                        .filter { (sourceId, targetId) ->
                            val source = idToCard[sourceId]
                            val target =
                                if (targetId in projection.projectedIds) {
                                    idToCard[targetId]
                                } else {
                                    bridge.getPlayer(SeatId(targetId))
                                }
                            source == null || target == null || source.entityAttachedTo !== target
                        }.keys,
                )
            }.distinct()
        val characteristicMismatchIds =
            gsm.gameObjectsList
                .filter { source ->
                    val card = idToCard[source.instanceId] ?: return@filter false
                    hasCharacteristicMismatch(source, card)
                }.map { it.instanceId }

        fun verifiedFeature(
            feature: String,
            count: Int,
            mismatchIds: List<Int>,
        ) = SnapshotFidelityFeature(
            feature = feature,
            status = if (mismatchIds.isEmpty()) "carried" else "mismatch",
            count = count,
            detail = mismatchIds.takeIf { it.isNotEmpty() }?.let { "${it.size} failed post-hydration verification" },
            instanceIds = mismatchIds,
        )
        val features =
            listOf(
                verifiedFeature("marked_damage", damageSources.size, damageMismatchIds),
                verifiedFeature("attachments", projection.allAttachmentTargetsByIid.size, attachmentMismatchIds),
                verifiedFeature("characteristics", idToCard.size, characteristicMismatchIds),
                SnapshotFidelityFeature(
                    "unresolved_cards",
                    if (projection.unresolvedIds.isEmpty()) "carried" else "missing",
                    projection.unresolvedIds.size,
                    instanceIds = projection.unresolvedIds.sorted(),
                ),
                SnapshotFidelityFeature(
                    "dynamic_effects",
                    if (projection.dynamicEffectCount == 0) "carried" else "approximated",
                    projection.dynamicEffectCount,
                    projection.dynamicEffectCount.takeIf { it > 0 }?.let {
                        "recomputed from Forge card scripts where possible; characteristics are verified separately"
                    },
                ),
                SnapshotFidelityFeature(
                    "mana_pool",
                    if (projection.manaPoolCount == 0) "carried" else "missing",
                    projection.manaPoolCount,
                    projection.manaPoolCount.takeIf { it > 0 }?.let { "floating mana is not projected" },
                ),
                SnapshotFidelityFeature(
                    "stack",
                    if (projection.stackCount == 0) "carried" else "approximated",
                    projection.stackCount,
                    projection.stackCount.takeIf { it > 0 }?.let { "consult-seat stack cards are reconstructed as in-flight hand cards" },
                ),
                SnapshotFidelityFeature(
                    "combat_state",
                    "carried",
                    if (projection.combat) 1 else 0,
                    projection.combat.takeIf { it }?.let { "attackers and blockers are restored from object combat state" },
                ),
                SnapshotFidelityFeature(
                    "phase",
                    if (projection.exactPhase) "carried" else "approximated",
                    1,
                    if (projection.exactPhase) null else "source phase collapses to MAIN1",
                ),
                SnapshotFidelityFeature("library_order", "unknown", 2, "hidden libraries use filler cards"),
            )
        return SnapshotFidelityReport(grade = "ungraded", features = features)
    }

    private fun hasCharacteristicMismatch(
        source: GameObjectInfo,
        card: forge.game.card.Card,
    ): Boolean =
        visibleTypeOf(source)?.matches(card.type) == false ||
            (isCreature(source) && source.hasPower() && card.netPower != source.power.value) ||
            (isCreature(source) && source.hasToughness() && card.netToughness != source.toughness.value)

    private data class SnapshotProjection(
        val lines: List<String>,
        val projectedIds: Set<Int>,
        val allAttachmentTargetsByIid: Map<Int, Int>,
        val attachmentTargetsByIid: Map<Int, Int>,
        val unresolvedIds: Set<Int>,
        val dynamicEffectCount: Int,
        val manaPoolCount: Int,
        val stackCount: Int,
        val combat: Boolean,
        val exactPhase: Boolean,
    )

    private fun visibleTypeOf(source: GameObjectInfo): ForgeCardType? {
        val names =
            (source.superTypesList + source.cardTypesList + source.subtypesList)
                .map { it.name.substringBefore('_') }
                .filterNot { it.startsWith("None") }
        return names.takeIf { it.isNotEmpty() }?.let { ForgeCardType(it, false) }
    }

    private fun isCreature(source: GameObjectInfo): Boolean =
        wotc.mtgo.gre.external.messaging.Messages.CardType.Creature in source.cardTypesList

    private fun ForgeCardType.matches(other: forge.card.CardTypeView): Boolean =
        coreTypes.toSet() == other.coreTypes.toSet() &&
            supertypes.toSet() == other.supertypes.toSet() &&
            subtypes.toSet() == other.subtypes.toSet()

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
            val forgeName = protoName?.substringBefore('_')?.uppercase()
            val forgeType = forgeName?.let { name -> runCatching { ForgeCounterType.getType(name) }.getOrNull() }
            if (forgeType == null) {
                log.warn("Unknown counter type {} on iid={}, dropping", typeNumber, iid)
                continue
            }
            result.getOrPut(iid) { mutableListOf() } += "${forgeType.name}=$count"
        }
        return result
    }

    /**
     * Project the GSM phase/step onto Forge's puzzle phase vocabulary. The
     * A combat step with committed attackers hydrates in combat so priority
     * decisions see those roles. An empty declare-attackers prompt stays on
     * Main1 because the AI's "would I attack" judgement is main-phase shaped.
     * Anything else not recognisably Main2 hydrates as Main1.
     */
    private fun mainPhaseOf(gsm: GameStateMessage): String =
        when {
            gsm.turnInfo.step.name
                .startsWith("DeclareBlock") -> "COMBAT_DECLARE_BLOCKERS"
            gsm.turnInfo.step.name
                .startsWith("DeclareAttack") &&
                gsm.gameObjectsList.any { it.attackState == AttackState.Attacking } -> "COMBAT_DECLARE_ATTACKERS"
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
            bridge.bindInstanceId(ForgeCardId(card.id), InstanceId(sourceIid))
        }
        log.debug("Rebound {} instanceIds to source ids", idToCard.size)
    }

    /** Restore committed blocks and the acting priority seat for combat consultation. */
    private fun restoreCombatState(
        gsm: GameStateMessage,
        puzzle: Puzzle,
        bridge: GameBridge,
        consultSeat: Int,
    ) {
        val assignments =
            gsm.gameObjectsList.flatMap { blocker ->
                blocker.blockInfo.attackerIdsList.map { attackerId -> blocker.instanceId to attackerId }
            }
        if (assignments.isEmpty()) return

        val seat = SeatId(consultSeat)
        val game = bridge.getGame() ?: return
        val player = bridge.getPlayer(seat) ?: return
        val combat = game.combat ?: return
        val cards = idToCardOf(puzzle)
        for ((blockerId, attackerId) in assignments) {
            val blocker = cards[blockerId] ?: continue
            val attacker = cards[attackerId] ?: continue
            if (attacker !in combat.getAttackersBlockedBy(blocker)) combat.addBlocker(attacker, blocker)
        }
        game.phaseHandler.setPriority(player)
        game.players.forEach { it.setHasPriority(it === player) }
        game.updateCombatForView()
    }

    private fun idToCardOf(puzzle: Puzzle): Map<Int, forge.game.card.Card> {
        val field = forge.game.GameState::class.java.getDeclaredField("idToCard")
        field.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        return field.get(puzzle) as Map<Int, forge.game.card.Card>
    }
}
