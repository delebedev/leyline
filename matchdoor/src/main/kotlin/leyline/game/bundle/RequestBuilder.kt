package leyline.game.bundle

import forge.game.Game
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.combat.CombatUtil
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
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

    private const val WATERBEND_PAYMENT_ABILITY_GRP_ID = 384
    private const val WATERBEND_MANA_ID_BASE = 50_000

    /**
     * Build a [SelectTargetsReq] from an [InteractivePromptBridge.PendingPrompt].
     *
     * Maps prompt candidate refs to client instanceIds:
     * - `kind="card"` → normal card instanceId via [GameBridge.getOrAllocInstanceId]
     * - `kind="player"` → seatId (1 or 2) as instanceId (Arena convention:
     *   players use their seatId as instanceId in target selection)
     *
     * [chooserSeatId] is the seat choosing targets — used for highlights:
     * opponent = Hot (suggested), everything else = Cold.
     *
     */
    fun buildSelectTargetsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        chooserSeatId: Int = 1,
    ): SelectTargetsReq {
        val opponentSeatId = if (chooserSeatId == 1) 2 else 1
        val builder = SelectTargetsReq.newBuilder()
        val selBuilder = TargetSelection.newBuilder()
        selBuilder.setTargetIdx(1)
        selBuilder.setTargetingPlayer(chooserSeatId)

        // sourceId: map the spell's entity ID to its client instanceId
        val sourceInstanceId = sourceInstanceId(prompt, bridge)
        if (sourceInstanceId != 0) {
            builder.setSourceId(sourceInstanceId)
        }
        val sourceEntityId = prompt.request.sourceEntityId
        val sourceCard = sourceEntityId?.let { bridge.findCard(ForgeCardId(it)) }
        val sourceGrpId = sourceCard?.let { bridge.resolveGrpId(it, sourceInstanceId) } ?: 0
        if (sourceGrpId != 0) builder.setAbilityGrpId(sourceGrpId)
        applyTargetSelectionMetadata(selBuilder, prompt, bridge, sourceInstanceId, sourceGrpId, chooserSeatId)
        applyTargetPromptShape(prompt, bridge, builder, selBuilder, sourceInstanceId)

        for (ref in prompt.request.candidateRefs) {
            val (instanceId, highlight) = resolveRefToIidAndHighlight(ref, bridge, opponentSeatId) ?: continue
            selBuilder.addTargets(
                wotc.mtgo.gre.external.messaging.Messages.Target
                    .newBuilder()
                    .setTargetInstanceId(instanceId)
                    .setLegalAction(SelectAction.Select_a1ad)
                    .setHighlight(highlight),
            )
        }
        selBuilder.setMinTargets(prompt.request.min)
        selBuilder.setMaxTargets(prompt.request.max)
        builder.addTargets(selBuilder)
        return builder.build()
    }

    /**
     * Build a re-prompt [SelectTargetsReq] reflecting the current selection.
     *
     * Shape:
     * - Already-selected targets: `legalAction=Unselect`, no highlight.
     * - Remaining legal candidates: `legalAction=Select_a1ad`, `highlight=Tepid`
     *   (Hot for opponent player targets, Cold for own-player).
     * - Illegal-after-selection candidates omitted entirely. Legality uses
     *   Forge's [SpellAbility.canTarget] with hypothetical selections applied
     *   via clone-and-swap on `sa.targets` (safe because the engine thread is
     *   blocked inside `requestChoice` between phase-1 and phase-2).
     * - `selectedTargets` set to selection count; minTargets/maxTargets preserved.
     *
     * When [InteractivePromptBridge.PendingPrompt.targetingSa] is null, falls back
     * to emitting all non-selected candidates as Select — unblocks the client
     * without a legality filter.
     */
    @Suppress("CyclomaticComplexMethod")
    fun buildSelectTargetsRePrompt(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        selectedInstanceIds: List<Int>,
        chooserSeatId: Int = 1,
    ): SelectTargetsReq {
        val builder = SelectTargetsReq.newBuilder()
        val selBuilder = TargetSelection.newBuilder()
        selBuilder.setTargetIdx(1)
        selBuilder.setTargetingPlayer(chooserSeatId)
        selBuilder.setMinTargets(prompt.request.min)
        selBuilder.setMaxTargets(prompt.request.max)
        selBuilder.setSelectedTargets(selectedInstanceIds.size)

        val sourceInstanceId = sourceInstanceId(prompt, bridge)
        if (sourceInstanceId != 0) builder.setSourceId(sourceInstanceId)
        val sourceEntityId = prompt.request.sourceEntityId
        val sourceCard = sourceEntityId?.let { bridge.findCard(ForgeCardId(it)) }
        val sourceGrpId = sourceCard?.let { bridge.resolveGrpId(it, sourceInstanceId) } ?: 0
        if (sourceGrpId != 0) builder.setAbilityGrpId(sourceGrpId)
        applyTargetSelectionMetadata(selBuilder, prompt, bridge, sourceInstanceId, sourceGrpId, chooserSeatId)
        applyTargetPromptShape(prompt, bridge, builder, selBuilder, sourceInstanceId)

        val selectedSet = selectedInstanceIds.toSet()
        val opponentSeatId = if (chooserSeatId == 1) 2 else 1
        val sa = prompt.targetingSa
        val game = bridge.getGame()
        val hypotheticalSelections: List<GameEntity> =
            if (sa != null && game != null) {
                selectedInstanceIds.mapNotNull { resolveEntityByInstanceId(it, bridge, game) }
            } else {
                emptyList()
            }

        // When all target slots are filled (e.g. min=max=1 after one pick), the
        // re-prompt omits Select entries — only the Unselect echo remains.
        val slotsRemaining = selectedInstanceIds.size < prompt.request.max

        for (ref in prompt.request.candidateRefs) {
            val (instanceId, highlight) = resolveRefToIidAndHighlight(ref, bridge, opponentSeatId) ?: continue
            if (instanceId == 0) continue

            if (instanceId in selectedSet) {
                selBuilder.addTargets(
                    wotc.mtgo.gre.external.messaging.Messages.Target
                        .newBuilder()
                        .setTargetInstanceId(instanceId)
                        .setLegalAction(SelectAction.Unselect),
                )
                continue
            }

            // Remaining candidate — only emit if more slots are open and still legal.
            if (!slotsRemaining) continue
            val stillLegal =
                if (sa != null && game != null) {
                    val candidate = resolveEntityByRef(ref, bridge, game)
                    candidate == null || canTargetWithHypothetical(sa, candidate, hypotheticalSelections)
                } else {
                    true
                }
            if (!stillLegal) continue

            selBuilder.addTargets(
                wotc.mtgo.gre.external.messaging.Messages.Target
                    .newBuilder()
                    .setTargetInstanceId(instanceId)
                    .setLegalAction(SelectAction.Select_a1ad)
                    .setHighlight(highlight),
            )
        }

        builder.addTargets(selBuilder)
        return builder.build()
    }

    private fun sourceInstanceId(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Int {
        if (prompt.request.isTriggeredAbility && prompt.request.forgeAbilityId != 0) {
            return bridge.getOrAllocInstanceId(FrameIdResolver.triggerStackAbilityForgeId(prompt.request.forgeAbilityId)).value
        }
        val sourceEntityId = prompt.request.sourceEntityId ?: return 0
        return bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId)).value
    }

    /** Build an [OrderReq] plus its outer prompt envelope. */
    fun buildOrderReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<OrderReq, Prompt> {
        val ids =
            prompt.request.candidateRefs
                .filter { it.kind == "card" }
                .map { bridge.getOrAllocInstanceId(ForgeCardId(it.entityId)).value }
        val orderReq =
            OrderReq
                .newBuilder()
                .addAllIds(ids)
                .apply {
                    if (prompt.request.semantic == PromptSemantic.OrderForBottom) {
                        setOrderingContext(OrderingContext.OrderingForBottom)
                    }
                }.build()
        val sourceInstanceId = orderSourceInstanceId(prompt, bridge)
        val promptProto =
            Prompt
                .newBuilder()
                .setPromptId(orderPromptId(prompt.request.semantic))
                .addParameters(
                    PromptParameter
                        .newBuilder()
                        .setParameterName("CardId")
                        .setType(ParameterType.Number)
                        .setNumberValue(sourceInstanceId),
                ).build()
        return orderReq to promptProto
    }

    private fun orderPromptId(semantic: PromptSemantic): Int =
        if (semantic == PromptSemantic.OrderForBottom) {
            PromptIds.ORDER_LIBRARY_BOTTOM
        } else if (semantic == PromptSemantic.OrderForTop) {
            PromptIds.ORDER_LIBRARY_TOP
        } else {
            error("Not an order semantic: $semantic")
        }

    private fun orderSourceInstanceId(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Int {
        val sourceEntityId =
            prompt.request.sourceEntityId ?: bridge
                .getGame()
                ?.stack
                ?.firstOrNull()
                ?.id ?: return 0
        return bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId)).value
    }

    private fun applyTargetPromptShape(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        builder: SelectTargetsReq.Builder,
        selection: TargetSelection.Builder,
        sourceInstanceId: Int,
    ) {
        val shape = targetPromptShape(prompt, bridge) ?: return
        if (shape.outerAbilityGrpId != 0) builder.setAbilityGrpId(shape.outerAbilityGrpId)
        if (shape.targetingAbilityGrpId != 0) selection.setTargetingAbilityGrpId(shape.targetingAbilityGrpId)
        if (shape.targetSourceZoneId != 0) selection.setTargetSourceZoneId(shape.targetSourceZoneId)
        if (shape.promptId != null && sourceInstanceId != 0) {
            selection.setPrompt(
                Prompt
                    .newBuilder()
                    .setPromptId(shape.promptId)
                    .addParameters(
                        PromptParameter
                            .newBuilder()
                            .setParameterName("CardId")
                            .setType(ParameterType.Number)
                            .setNumberValue(sourceInstanceId),
                    ),
            )
        }
    }

    private data class TargetPromptShape(
        val outerAbilityGrpId: Int,
        val targetingAbilityGrpId: Int,
        val promptId: Int? = null,
        val targetSourceZoneId: Int = 0,
    )

    private fun targetPromptShape(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): TargetPromptShape? {
        val sa = prompt.targetingSa ?: return null
        if (isMentorTrigger(sa)) {
            return TargetPromptShape(
                outerAbilityGrpId = KeywordAbilityIds.MENTOR,
                targetingAbilityGrpId = KeywordAbilityIds.MENTOR,
                promptId = PromptIds.MENTOR_TARGET,
            )
        }
        val cardName = sa.hostCard?.name ?: return null
        val grpId = bridge.cardRepository.findGrpIdByName(cardName) ?: return null
        return when {
            sa.isMutate ->
                TargetPromptShape(
                    outerAbilityGrpId = KeywordAbilityIds.MUTATE,
                    targetingAbilityGrpId = bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.MUTATE) ?: 0,
                    promptId = PromptIds.MUTATE_TARGET,
                    targetSourceZoneId = ZoneIds.BATTLEFIELD,
                )
            isBackupTrigger(sa) ->
                bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.BACKUP)?.let { backupGrpId ->
                    TargetPromptShape(
                        outerAbilityGrpId = backupGrpId,
                        targetingAbilityGrpId = KeywordAbilityIds.BACKUP,
                    )
                }
            else -> null
        }
    }

    private fun isBackupTrigger(sa: forge.game.spellability.SpellAbility): Boolean =
        sa.isBackup || sa.trigger?.getParam("TriggerDescription")?.startsWith("Backup ") == true

    private fun isMentorTrigger(sa: forge.game.spellability.SpellAbility): Boolean =
        sa.trigger?.getParam("TriggerDescription")?.startsWith("Mentor") == true

    /**
     * Check whether [candidate] is still a legal target for [sa] given that
     * [hypothetical] are already selected. Applied via clone-and-swap on
     * `sa.targets` — never mutates the caller-visible TargetChoices.
     */
    private fun canTargetWithHypothetical(
        sa: SpellAbility,
        candidate: GameEntity,
        hypothetical: List<GameEntity>,
    ): Boolean {
        val original = sa.targets
        val clone = original.clone()
        for (e in hypothetical) clone.add(e)
        return try {
            sa.setTargets(clone)
            sa.canTarget(candidate)
        } finally {
            sa.setTargets(original)
        }
    }

    /** Map a client instanceId (seatId for player targets, card iid otherwise) back to a Forge [GameEntity]. */
    private fun resolveEntityByInstanceId(
        instanceId: Int,
        bridge: GameBridge,
        game: Game,
    ): GameEntity? {
        bridge.getPlayer(SeatId(instanceId))?.let { return it }
        val cardId = bridge.getForgeCardId(InstanceId(instanceId)) ?: return null
        return game.findById(cardId.value)
    }

    /** Resolve a [candidateRef] (player- or card-kind) to a Forge [GameEntity]. */
    private fun resolveEntityByRef(
        ref: PromptCandidateRefDto,
        bridge: GameBridge,
        game: Game,
    ): GameEntity? {
        if (ref.kind == "player") {
            val seatId = playerEntityIdToSeatId(ref.entityId, bridge) ?: return null
            return bridge.getPlayer(SeatId(seatId))
        }
        return game.findById(ref.entityId)
    }

    /**
     * Resolve a prompt [candidateRef] to its client-facing `(instanceId, highlight)` tuple.
     * Players map to seatId with Hot/Cold highlight by friend/foe; cards map to their allocated
     * instanceId with Tepid. Returns null when the entityId doesn't resolve.
     */
    private fun resolveRefToIidAndHighlight(
        ref: PromptCandidateRefDto,
        bridge: GameBridge,
        opponentSeatId: Int,
    ): Pair<Int, HighlightType>? {
        if (ref.kind == "player") {
            val seatId = playerEntityIdToSeatId(ref.entityId, bridge) ?: return null
            val hl = if (seatId == opponentSeatId) HighlightType.Hot else HighlightType.Cold
            return seatId to hl
        }
        val iid = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
        return iid to HighlightType.Tepid
    }

    private fun applyTargetSelectionMetadata(
        selBuilder: TargetSelection.Builder,
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        sourceInstanceId: Int,
        sourceGrpId: Int,
        chooserSeatId: Int,
    ) {
        if (sourceInstanceId != 0) {
            selBuilder.prompt =
                Prompt
                    .newBuilder()
                    .setPromptId(PromptIds.SELECT_TARGETS)
                    .addParameters(
                        PromptParameter
                            .newBuilder()
                            .setParameterName("CardId")
                            .setType(ParameterType.Number)
                            .setNumberValue(sourceInstanceId),
                    ).build()
        }
        val targetingAbilityGrpId = resolveTargetingAbilityGrpId(prompt.targetingSa, sourceGrpId, bridge)
        if (targetingAbilityGrpId != 0) selBuilder.targetingAbilityGrpId = targetingAbilityGrpId
        val sourceZoneId = targetSourceZoneId(prompt.request.candidateRefs, bridge, chooserSeatId)
        if (sourceZoneId != 0) selBuilder.targetSourceZoneId = sourceZoneId
    }

    private fun resolveTargetingAbilityGrpId(
        sa: SpellAbility?,
        sourceGrpId: Int,
        bridge: GameBridge,
    ): Int {
        val host = sa?.hostCard ?: return 0
        val data = sourceGrpId.takeIf { it != 0 }?.let { bridge.cardRepository.findByGrpId(it) }
        bridge
            .abilityRegistryFor(host, data)
            ?.forSpellAbility(sa.id)
            ?.takeIf { it != 0 }
            ?.let { return it }
        return data
            ?.abilityIds
            ?.firstOrNull { (abilityGrpId, _) ->
                bridge.cardRepository.findAbilityInfo(abilityGrpId)?.category == 4
            }?.first
            ?: data
                ?.abilityIds
                ?.firstOrNull()
                ?.first
            ?: 0
    }

    private fun targetSourceZoneId(
        refs: List<PromptCandidateRefDto>,
        bridge: GameBridge,
        chooserSeatId: Int,
    ): Int {
        val ref = refs.firstOrNull { it.kind == "card" && it.zone != null } ?: return 0
        val card = bridge.findCard(ForgeCardId(ref.entityId))
        val ownerSeat = card?.owner?.let { owner -> if (owner == bridge.getPlayer(SeatId(1))) 1 else 2 } ?: chooserSeatId
        return when (ref.zone) {
            "Battlefield" -> ZoneIds.BATTLEFIELD
            "Exile" -> ZoneIds.EXILE
            "Stack" -> ZoneIds.STACK
            "Graveyard" -> ZoneIds.graveyardOf(SeatId(ownerSeat))
            "Hand" -> ZoneIds.handOf(SeatId(ownerSeat))
            "Library" -> ZoneIds.libraryOf(SeatId(ownerSeat))
            else -> 0
        }
    }

    /**
     * Build a [SelectNReq] from a pending prompt with candidateRefs.
     * Used for "choose N cards" prompts (discard, sacrifice, legend rule, reveal-choose, etc.).
     *
     * Maps prompt candidate entity IDs to client instanceIds. The client
     * responds with SelectNResp containing selected instanceIds.
     *
     * Context/listType vary by prompt type:
     * - `legend_rule`: context=Resolution, listType=Dynamic
     * - `choose_cards` (discard): context=Discard, listType=Static
     * - `reveal_choose`: context=Resolution, listType=Dynamic, +unfilteredIds +sourceId
     */
    fun buildSelectNReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): SelectNReq {
        val semantic = prompt.request.semantic
        val staticList = prompt.request.staticList
        val shape = selectNShape(semantic)
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
                .apply {
                    if (staticList == null) {
                        setIdType(IdType.InstanceId_ab2c)
                    } else {
                        setStaticList(staticList)
                    }
                }

        // For reveal-choose with empty ids (no valid target), omit minSel/maxSel (defaults to 0).
        val hasValidChoices = prompt.request.candidateRefs.isNotEmpty()
        if (semantic != PromptSemantic.RevealChoose || hasValidChoices) {
            builder.setMinSel(selectNMinSel(prompt, semantic))
            builder.setMaxSel(prompt.request.max.coerceAtLeast(prompt.request.min))
        }

        builder.addSelectNIds(prompt, bridge)
        builder.configureSelectNPrompt(prompt, bridge, semantic)
        return builder.build()
    }

    private fun selectNShape(semantic: PromptSemantic): SelectNShape =
        SelectNPromptRoutes.staticChoice(semantic)?.shape ?: selectNShapeBySemantic(semantic)

    private fun selectNShapeBySemantic(semantic: PromptSemantic): SelectNShape =
        when {
            semantic == PromptSemantic.SelectNDiscard ->
                SelectNShape(
                    SelectionContext.Discard_a163,
                    SelectionListType.Static,
                    OptionContext.Payment,
                )
            else ->
                SelectNShape(
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                )
        }

    private fun SelectNReq.Builder.configureSelectNPrompt(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        semantic: PromptSemantic,
    ) {
        when {
            semantic == PromptSemantic.StaticColorChoice ||
                semantic == PromptSemantic.StaticSubtypeChoice ||
                semantic == PromptSemantic.StaticParityChoice -> {
                setSourceIdIfPresent(prompt, bridge)
                setPrompt(Prompt.newBuilder())
            }
            semantic == PromptSemantic.SelectNLegendRule -> {
                // Empty inner prompt; the real promptId goes on the outer GRE message.
                setPrompt(Prompt.newBuilder())
                setSourceId(PromptIds.SELECT_N_LEGEND_RULE_SOURCE)
            }
            semantic == PromptSemantic.SelectNDiscard -> {
                setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DISCARD_COST))
            }
            semantic == PromptSemantic.RevealChoose -> {
                setSourceIdIfPresent(prompt, bridge)
                setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N))
            }
            semantic == PromptSemantic.SelectNResolution -> {
                // Look-and-pick inner prompt carries a PromptId parameter; the
                // outer GRE message carries the card-specific prompt.
                setSourceIdIfPresent(prompt, bridge)
                setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
            }
            semantic == PromptSemantic.SelectNLibraryPutback -> {
                setSourceIdIfPresent(prompt, bridge)
                setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
            }
            semantic == PromptSemantic.MutateTopBottom -> {
                setSourceIdIfPresent(prompt, bridge)
                setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N))
            }
            semantic == PromptSemantic.LearnLesson -> {
                setSourceIdIfPresent(prompt, bridge)
                setSelectNInnerPrompt(PromptIds.SELECT_N_LEARN_INNER_PARAMETER)
            }
            else -> {
                setSourceIdIfPresent(prompt, bridge)
                setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N))
            }
        }
    }

    private fun SelectNReq.Builder.setSelectNInnerPrompt(promptId: Int) {
        setPrompt(
            Prompt
                .newBuilder()
                .addParameters(
                    PromptParameter
                        .newBuilder()
                        .setParameterName("Parameter")
                        .setType(ParameterType.PromptId)
                        .setPromptId(promptId),
                ),
        )
    }

    private fun selectNMinSel(
        prompt: InteractivePromptBridge.PendingPrompt,
        semantic: PromptSemantic,
    ): Int =
        if (semantic == PromptSemantic.LearnLesson && prompt.request.candidateRefs.isNotEmpty()) {
            1
        } else {
            prompt.request.min
        }

    private fun SelectNReq.Builder.addSelectNIds(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ) {
        if (prompt.request.staticList != null) {
            if (prompt.request.semantic == PromptSemantic.StaticSubtypeChoice) {
                prompt.request.staticOptionIds.forEach { addIds(it) }
            }
            return
        }
        prompt.request.candidateRefs.forEach { ref ->
            addIds(bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value)
        }
        // unfilteredIds — all revealed cards (superset of ids) for reveal-choose prompts.
        prompt.request.unfilteredRefs.forEach { ref ->
            addUnfilteredIds(bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value)
        }
    }

    private fun SelectNReq.Builder.setSourceIdIfPresent(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ) {
        val sourceInstanceId = sourceInstanceId(prompt, bridge)
        if (sourceInstanceId != 0) setSourceId(sourceInstanceId)
    }

    fun buildSacrificePayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> = buildSelectCostPayCostsReq(prompt, bridge, PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE)

    fun buildStationTapCostPayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> = buildSelectCostPayCostsReq(prompt, bridge, PromptIds.STATION_TAP_COST)

    fun buildEnlistCostPayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> = buildSelectCostPayCostsReq(prompt, bridge, PromptIds.ENLIST_TAP_COST)

    fun buildWaterbendCostPayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> {
        val sourceInstanceId =
            prompt.request.sourceEntityId?.let {
                bridge.getOrAllocInstanceId(ForgeCardId(it)).value
            } ?: 0
        val sourceGrpId =
            prompt.request.sourceEntityId
                ?.let { bridge.findCard(ForgeCardId(it)) }
                ?.let { card ->
                    bridge.resolveGrpId(card, sourceInstanceId).takeIf { it != 0 }
                        ?: bridge.cardRepository.findGrpIdByName(card.name)
                }
                ?: prompt.request.sourceCardName?.let { bridge.cardRepository.findGrpIdByName(it) }
                ?: 0
        val waterbendAbilityGrpId =
            if (sourceGrpId != 0) {
                bridge.cardRepository.findKeywordAbilityGrpId(sourceGrpId, KeywordAbilityIds.WATERBEND) ?: 0
            } else {
                0
            }
        val req =
            PayCostsReq
                .newBuilder()
                .addAllManaCost(
                    prompt.request.waterbendManaCost.map { (color, count) ->
                        ManaRequirement
                            .newBuilder()
                            .addColor(color)
                            .setCount(count)
                            .setObjectId(sourceInstanceId)
                            .apply { if (waterbendAbilityGrpId != 0) setAbilityGrpId(waterbendAbilityGrpId) }
                            .build()
                    },
                ).setPaymentActions(buildWaterbendPaymentActions(prompt, bridge))
                .setPaymentSelection(
                    SelectNReq
                        .newBuilder()
                        .setContext(SelectionContext.ManaPool)
                        .setOptionContext(OptionContext.Payment)
                        .setListType(SelectionListType.Dynamic)
                        .setIdType(IdType.ManaId)
                        .setValidationType(SelectionValidationType.NonRepeatable)
                        .setMinWeight(Int.MIN_VALUE)
                        .setMaxWeight(Int.MAX_VALUE),
                ).build()
        val promptProto =
            Prompt
                .newBuilder()
                .setPromptId(PromptIds.PAY_COSTS)
                .apply {
                    prompt.request.waterbendCostString?.let { cost ->
                        addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("Cost")
                                .setType(ParameterType.NonLocalizedString)
                                .setStringValue(cost),
                        )
                    }
                }.build()
        return req to promptProto
    }

    private fun buildWaterbendPaymentActions(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): ActionsAvailableReq {
        val builder = ActionsAvailableReq.newBuilder()
        for ((idx, ref) in prompt.request.candidateRefs.withIndex()) {
            val forgeId = ForgeCardId(ref.entityId)
            val card = bridge.findCard(forgeId) ?: continue
            val iid = bridge.getOrAllocInstanceId(forgeId).value
            val grpId = bridge.resolveGrpId(card, iid)
            builder.addActions(waterbendPaymentAction(iid, grpId, card.isCreature, idx))
        }
        return builder.build()
    }

    private fun waterbendPaymentAction(
        instanceId: Int,
        grpId: Int,
        fromCreature: Boolean,
        index: Int,
    ): Action {
        val manaInfo =
            ManaInfo
                .newBuilder()
                .setManaId(WATERBEND_MANA_ID_BASE + index)
                .setColor(ManaColor.Colorless_afc9)
                .setSrcInstanceId(instanceId)
                .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                .apply {
                    if (fromCreature) addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.FromCreature))
                }.addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.ManaSubstitution))
                .setAbilityGrpId(WATERBEND_PAYMENT_ABILITY_GRP_ID)
                .setCount(1)
                .build()
        return Action
            .newBuilder()
            .setActionType(ActionType.MakePayment)
            .setGrpId(grpId)
            .setInstanceId(instanceId)
            .setFacetId(instanceId)
            .setAbilityGrpId(WATERBEND_PAYMENT_ABILITY_GRP_ID)
            .addManaPaymentOptions(ManaPaymentOption.newBuilder().addMana(manaInfo))
            .build()
    }

    /**
     * Build a `PayCostsReq` for an additional cost paid by selecting N cards
     * (sacrifice, exile-from-grave, etc). Builder is uniform —
     * `EffectCostType.Select` + `SelectionContext.NonManaPayment` — only the
     * [promptId] differs per cost flavor.
     */
    fun buildSelectCostPayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        promptId: Int,
        mandatory: Boolean = true,
    ): Pair<PayCostsReq, Prompt> {
        val sourceInstanceId =
            prompt.request.sourceEntityId?.let {
                bridge.getOrAllocInstanceId(ForgeCardId(it)).value
            } ?: 0

        // Non-mana cost selections are assumed mandatory: pay exactly N.
        // Some upstream call sites pass min=0 (Forge's "non-mandatory" flag,
        // which doesn't apply to keyword-cost additional payment) — coerce to
        // max so the client treats the picker as a fixed-N payment, not a
        // variable range.
        //
        // TODO: when a "pay up to N" cost arrives (e.g. variable additional
        // costs on activated abilities), thread a `mandatory: Boolean` flag
        // through call sites so this coercion can opt out.
        val maxSel =
            prompt.request.max
                .coerceAtLeast(prompt.request.min)
                .coerceAtLeast(1)
        val minSel = if (mandatory) maxSel else prompt.request.min.coerceAtLeast(0)
        val selection =
            SelectNReq
                .newBuilder()
                .setMinSel(minSel)
                .setMaxSel(maxSel)
                .setContext(SelectionContext.NonManaPayment)
                .setOptionContext(OptionContext.Payment)
                .setListType(SelectionListType.Dynamic)
                .setIdType(IdType.InstanceId_ab2c)
                .setValidationType(SelectionValidationType.NonRepeatable)
                // Canonical envelope for non-mana cost selection: client
                // expects min/max weight extremes set explicitly (proto3
                // defaults are 0, which the client treats as "no candidates
                // selectable").
                .setMinWeight(Int.MIN_VALUE)
                .setMaxWeight(Int.MAX_VALUE)

        for (ref in prompt.request.candidateRefs) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            selection.addIds(instanceId)
            selection.addWeights(1)
        }

        val req =
            PayCostsReq
                .newBuilder()
                // paymentActions is required as an empty ActionsAvailableReq on the client.
                // Without it, the picker renders but treats every card as
                // non-selectable (greyed out).
                .setPaymentActions(ActionsAvailableReq.newBuilder().build())
                .setEffectCostReq(
                    EffectCostReq
                        .newBuilder()
                        .setEffectCostType(EffectCostType.Select_a59c)
                        .setCostSelection(selection),
                ).build()

        val promptProto =
            Prompt
                .newBuilder()
                .setPromptId(promptId)
                .addParameters(
                    PromptParameter
                        .newBuilder()
                        .setParameterName("CardId")
                        .setType(ParameterType.Number)
                        .setNumberValue(sourceInstanceId),
                ).build()
        return req to promptProto
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

    /**
     * Map a Forge [forge.game.player.Player.id] to the Arena seatId (1=human, 2=AI).
     * Returns null if the entityId doesn't match either player.
     */
    private fun playerEntityIdToSeatId(
        entityId: Int,
        bridge: GameBridge,
    ): Int? {
        val p1 = bridge.getPlayer(SeatId(1))
        val p2 = bridge.getPlayer(SeatId(2))
        return when (entityId) {
            p1?.id -> 1
            p2?.id -> 2
            else -> null
        }
    }
}
