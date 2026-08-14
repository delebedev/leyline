package leyline.game.bundle

import forge.card.mana.ManaCostShard
import forge.game.Game
import forge.game.card.Card
import forge.game.combat.CombatUtil
import forge.game.player.Player
import leyline.bridge.coord.ConvokeShardAssigner
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SelectNEnvelopeKind
import leyline.bridge.handoff.SelectNPromptRoute
import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ManaColorMapping
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

    private const val WATERBEND_PAYMENT_ABILITY_GRP_ID = 384
    private const val MANA_SOURCE_MANA_ID_BASE = 50_000

    private enum class ManaSourcePaymentKind(
        val keywordAbilityId: Int,
        val paymentAbilityGrpId: Int,
        val includeSourceAbilityOnCost: Boolean,
    ) {
        Convoke(KeywordAbilityIds.CONVOKE, KeywordAbilityIds.CONVOKE_PAYMENT, false),
        Improvise(KeywordAbilityIds.IMPROVISE, KeywordAbilityIds.IMPROVISE, false),
        Waterbend(KeywordAbilityIds.WATERBEND, WATERBEND_PAYMENT_ABILITY_GRP_ID, true),
    }

    /** Build an [OrderReq] plus its outer prompt envelope. */
    fun buildOrderReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        kind: OrderRouteKind,
    ): Pair<OrderReq, Prompt> {
        val ids =
            prompt.request.candidateRefs
                .filter { it.isCard() }
                .map { bridge.getOrAllocInstanceId(ForgeCardId(it.entityId)).value }
        val orderReq =
            OrderReq
                .newBuilder()
                .addAllIds(ids)
                .apply {
                    if (kind == OrderRouteKind.Bottom) {
                        setOrderingContext(OrderingContext.OrderingForBottom)
                    }
                }.build()
        val sourceInstanceId = orderSourceInstanceId(prompt, bridge)
        val promptProto =
            promptWithCardId(orderPromptId(kind), sourceInstanceId)
        return orderReq to promptProto
    }

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

    private fun orderPromptId(kind: OrderRouteKind): Int =
        when (kind) {
            OrderRouteKind.Bottom -> PromptIds.ORDER_LIBRARY_BOTTOM
            OrderRouteKind.Top -> PromptIds.ORDER_LIBRARY_TOP
        }

    private fun orderSourceInstanceId(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Int =
        PromptSourceResolver
            .resolve(
                prompt,
                bridge,
                fallbackSourceEntityId =
                    bridge
                        .getGame()
                        ?.stack
                        ?.firstOrNull()
                        ?.id,
            ).sourceCardInstanceId

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
        route: SelectNPromptRoute,
    ): SelectNReq {
        val staticList = prompt.request.staticList
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
                .apply {
                    if (staticList == null) {
                        setIdType(IdType.InstanceId_ab2c)
                    } else {
                        setStaticList(staticList)
                    }
                }

        // For reveal-choose with empty ids (no valid target), omit minSel/maxSel (defaults to 0).
        val hasValidChoices = prompt.request.candidateRefs.isNotEmpty()
        if (route.envelopeKind != SelectNEnvelopeKind.RevealChoose || hasValidChoices) {
            builder.setMinSel(selectNMinSel(prompt, route))
            builder.setMaxSel(prompt.request.max.coerceAtLeast(prompt.request.min))
        }

        builder.addSelectNIds(prompt, bridge, route)
        route.configureInnerPrompt(builder, prompt, bridge)
        return builder.build()
    }

    fun buildSelectNReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): SelectNReq {
        val route =
            (prompt.request.route as? ResolvedPromptRoute.SelectN)?.descriptor
                ?: error("SelectN builder requires a bound SelectN route")
        return buildSelectNReq(prompt, bridge, route)
    }

    private fun selectNMinSel(
        prompt: InteractivePromptBridge.PendingPrompt,
        route: SelectNPromptRoute,
    ): Int =
        if (route.envelopeKind == SelectNEnvelopeKind.LearnLesson && prompt.request.candidateRefs.isNotEmpty()) {
            1
        } else {
            prompt.request.min
        }

    private fun SelectNReq.Builder.addSelectNIds(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        route: SelectNPromptRoute,
    ) {
        if (prompt.request.staticList != null) {
            if (route.staticChoice?.kind == StaticChoiceKind.Subtype) {
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

    fun buildTeamworkCostPayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> {
        val sourceInstanceId =
            prompt.request.sourceEntityId?.let {
                bridge.getOrAllocInstanceId(ForgeCardId(it)).value
            } ?: 0
        val threshold =
            requireNotNull(prompt.request.minSelectionWeight) {
                "Teamwork cost requires a minimum total power"
            }
        val weights = prompt.request.costSelectionWeights.map { it.coerceAtLeast(0) }
        require(weights.size == prompt.request.candidateRefs.size) {
            "Teamwork cost weights must match candidate count"
        }
        val selection =
            SelectNReq
                .newBuilder()
                .setMinSel(threshold)
                .setMaxSel(Int.MAX_VALUE)
                .setContext(SelectionContext.NonManaPayment)
                .setOptionContext(OptionContext.Payment)
                .setListType(SelectionListType.Dynamic)
                .setIdType(IdType.InstanceId_ab2c)
                .setValidationType(SelectionValidationType.NonRepeatable)
                .setMinWeight(Int.MIN_VALUE)
                .setMaxWeight(Int.MAX_VALUE)

        for ((idx, ref) in prompt.request.candidateRefs.withIndex()) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            selection.addIds(instanceId)
            selection.addWeights(weights.getOrElse(idx) { 1 })
        }

        val req =
            PayCostsReq
                .newBuilder()
                .setPaymentActions(ActionsAvailableReq.newBuilder().build())
                .setEffectCostReq(
                    EffectCostReq
                        .newBuilder()
                        .setEffectCostType(EffectCostType.Select_a59c)
                        .setCostSelection(selection),
                ).build()

        return req to promptWithCardId(PromptIds.TEAMWORK_TAP_COST, sourceInstanceId)
    }

    fun buildWaterbendCostPayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> = buildManaSourceCostPayCostsReq(prompt, bridge, ManaSourcePaymentKind.Waterbend)

    fun buildConvokeCostPayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> = buildManaSourceCostPayCostsReq(prompt, bridge, ManaSourcePaymentKind.Convoke)

    fun buildImproviseCostPayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> = buildManaSourceCostPayCostsReq(prompt, bridge, ManaSourcePaymentKind.Improvise)

    private fun buildManaSourceCostPayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        kind: ManaSourcePaymentKind,
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
                bridge.cardRepository.findKeywordAbilityGrpId(sourceGrpId, kind.keywordAbilityId) ?: 0
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
                            .apply {
                                if (kind.includeSourceAbilityOnCost && waterbendAbilityGrpId != 0) {
                                    setAbilityGrpId(waterbendAbilityGrpId)
                                }
                            }.build()
                    },
                ).setPaymentActions(buildManaSourcePaymentActions(prompt, bridge, kind))
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

    private fun buildManaSourcePaymentActions(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        kind: ManaSourcePaymentKind,
    ): ActionsAvailableReq {
        val builder = ActionsAvailableReq.newBuilder()
        val convokeShards = if (kind == ManaSourcePaymentKind.Convoke) convokePaymentShards(prompt, bridge) else emptyMap()
        for ((idx, ref) in prompt.request.candidateRefs.withIndex()) {
            val forgeId = ForgeCardId(ref.entityId)
            val card = bridge.findCard(forgeId) ?: continue
            val iid = bridge.getOrAllocInstanceId(forgeId).value
            val grpId = bridge.resolveGrpId(card, iid)
            builder.addActions(manaSourcePaymentAction(iid, grpId, card.isCreature, idx, card.color, prompt, kind, convokeShards[forgeId]))
        }
        return builder.build()
    }

    private fun convokePaymentShards(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Map<ForgeCardId, ManaCostShard> {
        val candidates =
            prompt.request.candidateRefs.mapNotNull { ref ->
                val forgeId = ForgeCardId(ref.entityId)
                val card = bridge.findCard(forgeId) ?: return@mapNotNull null
                forgeId to card
            }
        return ConvokeShardAssigner
            .assign(candidates, ManaColorMapping.paymentShardCounts(prompt.request.waterbendManaCost)) { (_, card) -> card.color }
            .associate { (entry, shard) -> entry.first to shard }
    }

    private fun manaSourcePaymentAction(
        instanceId: Int,
        grpId: Int,
        fromCreature: Boolean,
        index: Int,
        cardColor: forge.card.ColorSet,
        prompt: InteractivePromptBridge.PendingPrompt,
        kind: ManaSourcePaymentKind,
        convokeShard: ManaCostShard?,
    ): Action {
        val manaInfo =
            ManaInfo
                .newBuilder()
                .setManaId(MANA_SOURCE_MANA_ID_BASE + index)
                .setColor(paymentActionColor(cardColor, prompt, kind, convokeShard))
                .setSrcInstanceId(instanceId)
                .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                .apply {
                    if (fromCreature || kind == ManaSourcePaymentKind.Convoke) {
                        addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.FromCreature))
                    }
                }.addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.ManaSubstitution))
                .setAbilityGrpId(kind.paymentAbilityGrpId)
                .setCount(1)
                .build()
        return Action
            .newBuilder()
            .setActionType(ActionType.MakePayment)
            .setGrpId(grpId)
            .setInstanceId(instanceId)
            .setFacetId(instanceId)
            .setAbilityGrpId(kind.paymentAbilityGrpId)
            .addManaPaymentOptions(ManaPaymentOption.newBuilder().addMana(manaInfo))
            .build()
    }

    private fun paymentActionColor(
        cardColor: forge.card.ColorSet,
        prompt: InteractivePromptBridge.PendingPrompt,
        kind: ManaSourcePaymentKind,
        convokeShard: ManaCostShard?,
    ): ManaColor {
        if (kind == ManaSourcePaymentKind.Waterbend || kind == ManaSourcePaymentKind.Improvise) return ManaColor.Colorless_afc9
        convokeShard?.let { return ManaColorMapping.paymentWireColor(it) }
        val colorsNeeded = prompt.request.waterbendManaCost.toMap()
        return when {
            cardColor.hasWhite() && colorsNeeded.getOrDefault(ManaColor.White_afc9, 0) > 0 -> ManaColor.White_afc9
            cardColor.hasBlue() && colorsNeeded.getOrDefault(ManaColor.Blue_afc9, 0) > 0 -> ManaColor.Blue_afc9
            cardColor.hasBlack() && colorsNeeded.getOrDefault(ManaColor.Black_afc9, 0) > 0 -> ManaColor.Black_afc9
            cardColor.hasRed() && colorsNeeded.getOrDefault(ManaColor.Red_afc9, 0) > 0 -> ManaColor.Red_afc9
            cardColor.hasGreen() && colorsNeeded.getOrDefault(ManaColor.Green_afc9, 0) > 0 -> ManaColor.Green_afc9
            else -> ManaColor.Colorless_afc9
        }
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
            promptWithCardId(promptId, sourceInstanceId)
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
}
