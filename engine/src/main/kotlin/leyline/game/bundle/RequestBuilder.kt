package leyline.game.bundle

import forge.game.Game
import forge.game.card.Card
import forge.game.combat.CombatUtil
import forge.game.player.Player
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SelectNPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds outbound interactive request protos (targeting, selectN, combat).
 *
 * Pure proto construction from game state — no session state, no sending.
 * [leyline.match.CombatHandler] and [leyline.match.TargetingHandler]
 * handle the inbound responses.
 */
@Suppress("LargeClass") // One object mirrors the interactive request proto surface.
object RequestBuilder {
    private val log = LoggerFactory.getLogger(RequestBuilder::class.java)

    /** Build a [SearchReq] GRE message with populated inner fields for library search.
     *
     *  [sourceInstanceId] — `searchReq.sourceId`.
     *
     *  [hostCardInstanceId] — first `prompt.parameters` CardId. Names the source
     *  card so the picker header can use the card context.
     *
     *  [searchingSeat] — second `prompt.parameters` CardId. Both parameters are
     *  required to anchor the picker header.
     *
     *  [promptId] — picker layout. [PromptIds.SEARCH_TYPECYCLING] for cycling,
     *  typecycling, and basiccycling; [PromptIds.SEARCH] for generic tutors.
     *
     *  [allowCancel] — defaults to `No_a526`; generic tutors with optional
     *  resolution may pass `Abort` instead. */
    @Suppress("LongParameterList")
    fun buildSearchReq(
        msgId: Int,
        gsId: Int,
        systemSeatId: Int,
        sourceInstanceId: Int,
        hostCardInstanceId: Int,
        searchingSeat: Int,
        libraryZoneId: Int,
        allLibraryIds: List<Int>,
        validTargetIds: List<Int>,
        maxFind: Int = 1,
        allowFailToFind: Boolean = true,
        promptId: Int = PromptIds.SEARCH,
        allowCancel: AllowCancel = AllowCancel.No_a526,
    ): GREToClientMessage {
        val searchReq =
            SearchReq
                .newBuilder()
                .setMaxFind(maxFind)
                .addZonesToSearch(libraryZoneId)
                .addAllItemsToSearch(allLibraryIds)
                .addAllItemsSought(validTargetIds)
                .setSourceId(sourceInstanceId)
        if (allowFailToFind) {
            searchReq.setAllowFailToFind(AllowFailToFind.Any)
        }
        return GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.SearchReq_695e)
            .setMsgId(msgId)
            .setGameStateId(gsId)
            .addSystemSeatIds(systemSeatId)
            .setAllowCancel(allowCancel)
            .setPrompt(
                Prompt
                    .newBuilder()
                    .setPromptId(promptId)
                    .addParameters(cardIdPromptParameter(hostCardInstanceId))
                    .addParameters(cardIdPromptParameter(searchingSeat)),
            ).setSearchReq(searchReq)
            .build()
    }

    /**
     * Build a [SelectNReq] from a pending prompt with candidateRefs.
     * Used for residual dynamic resolution prompts.
     *
     * Maps prompt candidate entity IDs to client instanceIds. The client
     * responds with SelectNResp containing selected instanceIds.
     *
     * Context/listType vary by prompt type.
     */
    fun buildSelectNReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        route: SelectNPromptRoute,
    ): SelectNReq {
        check(prompt.request.staticList == null && prompt.request.staticOptionIds.isEmpty()) {
            "Static choices require StaticChoiceWindowMaterializer"
        }
        val shape = route.shape
        val builder =
            SelectNReq
                .newBuilder()
                .setContext(shape.context)
                .setListType(shape.listType)
                .setValidationType(SelectionValidationType.NonRepeatable)
                .setOptionContext(shape.optionContext)
                // Always per spec — INT32 extremes (no weight filtering on resolution picks).
                .setMinWeight(Int.MIN_VALUE)
                .setMaxWeight(Int.MAX_VALUE)
                .setIdType(IdType.InstanceId_ab2c)

        builder.setMinSel(prompt.request.min)
        builder.setMaxSel(prompt.request.max.coerceAtLeast(prompt.request.min))

        builder.addSelectNIds(prompt, bridge)
        route.configureInnerPrompt(builder, prompt, bridge)
        return builder.build()
    }

    fun buildSelectNReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): SelectNReq {
        val route =
            (prompt.request.route as? ResolvedPromptRoute.UnclassifiedEntityChoice)?.descriptor
                ?: error("SelectN builder requires a bound SelectN route")
        return buildSelectNReq(prompt, bridge, route)
    }

    private fun SelectNReq.Builder.addSelectNIds(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ) {
        prompt.request.candidateRefs.forEach { ref ->
            addIds(bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value)
        }
        // unfilteredIds — all viewed cards for residual resolution prompts.
        prompt.request.unfilteredRefs.forEach { ref ->
            addUnfilteredIds(bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value)
        }
    }

    private fun playerDamageRecipient(seatId: SeatId): DamageRecipient =
        DamageRecipient
            .newBuilder()
            .setType(DamageRecType.Player_a0e5)
            .setPlayerSystemSeatId(seatId.opponent.value)
            .build()

    private fun planeswalkerDamageRecipient(
        card: Card,
        bridge: GameBridge,
    ): DamageRecipient =
        DamageRecipient
            .newBuilder()
            .setType(DamageRecType.PlanesWalker)
            .setPlaneswalkerInstanceId(bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value)
            .build()

    private fun legalAttackDamageRecipients(
        player: Player,
        card: Card,
        seatId: SeatId,
        bridge: GameBridge,
    ): List<DamageRecipient> =
        buildList {
            for (defender in CombatUtil.getAllPossibleDefenders(player)) {
                if (!CombatUtil.canAttack(card, defender)) continue
                when (defender) {
                    is Player -> add(playerDamageRecipient(seatId))
                    is Card -> if (defender.isPlaneswalker) add(planeswalkerDamageRecipient(defender, bridge))
                }
            }
        }

    private fun selectedAttackDamageRecipient(
        instanceId: Int,
        seatId: SeatId,
        committedDamageRecipients: Map<Int, DamageRecipient>,
    ): DamageRecipient = committedDamageRecipients[instanceId] ?: playerDamageRecipient(seatId)

    private fun buildAttackerOption(
        instanceId: Int,
        legalRecipients: List<DamageRecipient>,
        alternativeGrpId: Int = 0,
    ): Attacker.Builder =
        Attacker
            .newBuilder()
            .setAttackerInstanceId(instanceId)
            .addAllLegalDamageRecipients(legalRecipients)
            .apply {
                if (alternativeGrpId != 0) setAlternativeGrpId(alternativeGrpId)
            }

    /**
     * Build [DeclareAttackersReq] listing all creatures that can legally attack.
     * Each attacker includes legal damage recipients (opponent player and planeswalkers).
     *
     * @param committedAttackerIds instanceIds of attackers already selected (echo-back).
     *   Committed attackers get [selectedDamageRecipient] set to their chosen recipient.
     * @param committedAttackAlternatives selected attack alternative per attacker; 0 means normal attack.
     *   Initial request passes empty set (no pre-selection).
     */
    fun buildDeclareAttackersReq(
        seatId: SeatId,
        bridge: GameBridge,
        committedAttackerIds: Set<Int> = emptySet(),
        committedAttackAlternatives: Map<Int, Int> = emptyMap(),
        committedDamageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    ): DeclareAttackersReq {
        val player = bridge.getPlayer(seatId) ?: return DeclareAttackersReq.getDefaultInstance()
        val builder = DeclareAttackersReq.newBuilder()

        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canAttack(card)) continue

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val hasEnlist = card.hasKeyword("Enlist")
            val isCommitted = instanceId in committedAttackerIds
            val selectedAlternativeGrpId = committedAttackAlternatives[instanceId] ?: 0
            val legalRecipients = legalAttackDamageRecipients(player, card, seatId, bridge)
            if (legalRecipients.isEmpty()) continue

            val attacker = buildAttackerOption(instanceId, legalRecipients)
            if (isCommitted && selectedAlternativeGrpId == 0) {
                attacker.setSelectedDamageRecipient(selectedAttackDamageRecipient(instanceId, seatId, committedDamageRecipients))
            }
            builder.addAttackers(attacker)

            if (hasEnlist) {
                val enlistAttacker = buildAttackerOption(instanceId, legalRecipients, KeywordAbilityIds.ENLIST)
                if (isCommitted && selectedAlternativeGrpId == KeywordAbilityIds.ENLIST) {
                    enlistAttacker.setSelectedDamageRecipient(selectedAttackDamageRecipient(instanceId, seatId, committedDamageRecipients))
                }
                builder.addAttackers(enlistAttacker)
            }

            // qualifiedAttackers never has selectedDamageRecipient
            builder.addQualifiedAttackers(buildAttackerOption(instanceId, legalRecipients))
            if (hasEnlist) builder.addQualifiedAttackers(buildAttackerOption(instanceId, legalRecipients, KeywordAbilityIds.ENLIST))
        }
        builder.setCanSubmitAttackers(true)
        // Conformance: client expects an empty manaCost entry entry.
        builder.addManaCost(ManaRequirement.getDefaultInstance())

        log.info("buildDeclareAttackersReq: seat={} attackers={} committed={}", seatId, builder.attackersCount, committedAttackerIds.size)
        return builder.build()
    }

    /**
     * Build [DeclareBlockersReq] listing all creatures that can legally block.
     *
     * @param blockerAssignments committed blocker→attacker assignments (instanceIds).
     *   Committed blockers get `selectedAttackerInstanceIds` set and `attackerInstanceIds`
     *   cleared. Uncommitted blockers get `attackerInstanceIds` (available targets).
     */
    fun buildDeclareBlockersReq(
        game: Game,
        seatId: SeatId,
        bridge: GameBridge,
        blockerAssignments: Map<Int, Int> = emptyMap(),
    ): DeclareBlockersReq {
        val player = bridge.getPlayer(seatId) ?: return DeclareBlockersReq.getDefaultInstance()
        val combat = game.phaseHandler.combat ?: return DeclareBlockersReq.getDefaultInstance()
        val builder = DeclareBlockersReq.newBuilder()

        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canBlock(card, combat)) continue

            // Per-attacker legality: only list attackers this creature can legally block
            // (handles flying/reach, menace, protection, etc.)
            val legalAttackers = combat.attackers.filter { CombatUtil.canBlock(it, card) }
            if (legalAttackers.isEmpty()) continue

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val blocker =
                Blocker
                    .newBuilder()
                    .setBlockerInstanceId(instanceId)
                    .setMaxAttackers(1)

            val assignedAttacker = blockerAssignments[instanceId]
            if (assignedAttacker != null) {
                blocker.addSelectedAttackerInstanceIds(assignedAttacker)
            } else {
                val legalAttackerIds = legalAttackers.map { bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value }
                blocker.addAllAttackerInstanceIds(legalAttackerIds)
            }
            builder.addBlockers(blocker)
        }
        // Conformance: client expects empty manaCost
        builder.addManaCost(ManaRequirement.getDefaultInstance())

        log.info("buildDeclareBlockersReq: seat={} blockers={} assigned={}", seatId, builder.blockersCount, blockerAssignments.size)
        return builder.build()
    }
}
