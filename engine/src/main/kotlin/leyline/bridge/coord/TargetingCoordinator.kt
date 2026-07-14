package leyline.bridge.coord

import forge.ai.LobbyPlayerAi
import forge.game.GameEntity
import forge.game.ability.AbilityUtils
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.card.CardCollectionView
import forge.game.card.CardView
import forge.game.player.Player
import forge.game.player.PlayerView
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import forge.player.TargetSelectionResult
import forge.util.collect.FCollectionView
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.interaction.ChooseCardsForEffectContext
import leyline.bridge.interaction.ChooseCardsForEffectPlanner
import leyline.bridge.interaction.ChooseEntitiesContext
import leyline.bridge.interaction.ChooseEntitiesPlanner
import leyline.bridge.interaction.ChooseSingleEntityContext
import leyline.bridge.interaction.ChooseSingleEntityPlanner
import leyline.bridge.interaction.ChooseSingleEntityRoutePolicy
import leyline.bridge.interaction.candidateRefs
import leyline.bridge.interaction.shouldAutoResolve
import leyline.bridge.interaction.shouldReturnAll
import leyline.bridge.interaction.sourceEntityId
import leyline.bridge.interaction.unfilteredRefs
import leyline.bridge.types.AbilityKeywordFamily
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.bridge.types.Seating
import leyline.bridge.types.toCandidateRefs
import leyline.game.mapping.PromptIds
import org.apache.commons.lang3.tuple.ImmutablePair
import org.slf4j.LoggerFactory

/**
 * Owns the user-chooses-cards override surface: targeting, entity choice,
 * reveals, discards and sacrifices, and zone ordering (scry/surveil/move-to-zone).
 *
 * Every method emits one or more [PromptRequest]s via [InteractivePromptBridge]
 * and translates the client response back into Forge types. A few methods also
 * record typed [PromptSideEffect]s on the bridge's [leyline.bridge.handoff.PromptJournal]
 * ([PromptSideEffect.LegendVictim],
 * [PromptSideEffect.RevealStarted]/[PromptSideEffect.RevealEnded]) that downstream
 * classes (`GameEventCollector`, `StateMapper`, `TargetingHandler`) consume.
 *
 * PCHuman's `super.<method>` calls stay on `PlayerController` — the
 * overrides that need them pass the call-through as a lambda or perform the
 * super step around the coordinator call. The coordinator itself does not
 * extend `PlayerControllerHuman`.
 *
 * See [leyline.bridge.forge.PlayerController]'s KDoc for the coordinator pattern.
 */
@Suppress("LargeClass")
class TargetingCoordinator(
    private val bridge: InteractivePromptBridge,
    private val seating: Seating,
    private val currentSourceEntityId: () -> Int? = { null },
) {
    private val log = LoggerFactory.getLogger(TargetingCoordinator::class.java)

    // -- Entity choice ---------------------------------------------------

    fun <T : GameEntity> chooseSingleEntity(
        optionList: FCollectionView<T>,
        sa: SpellAbility?,
        title: String?,
        isOptional: Boolean,
        hasDelayedReveal: Boolean,
    ): T? {
        if (optionList.isEmpty()) return null
        val reveal = bridge.journal.activeReveal()
        val revealedCards = optionList.filterIsInstance<Card>()
        val plan =
            ChooseSingleEntityPlanner.plan(
                ChooseSingleEntityContext(
                    sa = sa,
                    isOptional = isOptional,
                    hasDelayedReveal = hasDelayedReveal,
                    optionCount = optionList.size,
                    allOptionsAreCards = revealedCards.size == optionList.size,
                    hiddenLibrarySelection = isHiddenLibrarySelection(revealedCards, optionList.size),
                    activeReveal = reveal != null,
                ),
            )
        when (plan.routePolicy) {
            ChooseSingleEntityRoutePolicy.MutateTopCard ->
                if (sa != null) {
                    return chooseMutateTopCard(optionList, sa, title, isOptional)
                }
            ChooseSingleEntityRoutePolicy.ActiveReveal ->
                if (reveal != null) {
                    return chooseSingleEntityFromReveal(revealedCards, isOptional, sa, title, reveal)
                }
            ChooseSingleEntityRoutePolicy.AutoReturnFirst -> return optionList.getFirst()
            ChooseSingleEntityRoutePolicy.Prompt -> Unit
        }

        val labels = optionList.map { it.entityLabel() }
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = title ?: "Choose one",
                options = labels,
                min = plan.min,
                max = plan.max,
                defaultIndex = 0,
                semantic = plan.semantic,
                candidateRefs = plan.candidateRefsPolicy.candidateRefs(buildCandidateRefs(optionList)),
                sourceEntityId = plan.sourceIdPolicy.sourceEntityId(sa),
            )
        val indices = bridge.requestChoice(request)
        val idx = indices.firstOrNull()
        val chosen =
            if (idx != null && idx in 0 until optionList.size) {
                optionList.get(idx)
            } else {
                if (isOptional) null else optionList.getFirst()
            }

        recordLearnRevealIfNeeded(plan.isLearn, chosen)

        // Legend rule: mark all unchosen legendaries as victims for SBA_LegendRule annotation.
        if (plan.isLegendRule && chosen != null) {
            val cards = optionList.filterIsInstance<Card>()
            val victimIds = mutableListOf<ForgeCardId>()
            for (card in cards) {
                if (card !== chosen) {
                    val id = ForgeCardId(card.id)
                    TargetingCoordinator.recordLegendVictim(bridge, id)
                    victimIds += id
                }
            }
            log.info(
                "legend rule: player chose {} (id={}), victims={}",
                (chosen as? Card)?.name,
                (chosen as? Card)?.id,
                victimIds,
            )
        }

        return chosen
    }

    private fun <T : GameEntity> chooseSingleEntityFromReveal(
        revealedCards: List<Card>,
        isOptional: Boolean,
        sa: SpellAbility?,
        title: String?,
        reveal: PromptSideEffect.RevealStarted,
    ): T? {
        val message = revealChoiceMessage(sa, title)
        val chosen =
            chooseCardsViaBridgeForReveal(
                filteredCards = CardCollection(revealedCards),
                min = if (isOptional) 0 else 1,
                max = 1,
                sa = sa,
                reveal = reveal,
                message = message,
                recordExiledUnderSource = isExileUnderSourceRevealChoice(sa, message),
            ).firstOrNull()
        @Suppress("UNCHECKED_CAST")
        return chosen as? T
    }

    private fun recordLearnRevealIfNeeded(
        isLearn: Boolean,
        chosen: GameEntity?,
    ) {
        if (!isLearn || chosen !is Card || !chosen.isInZone(ZoneType.Sideboard)) return

        val ownerSeat = if (chosen.owner.lobbyPlayer is LobbyPlayerAi) seating.familiarSeat else seating.humanSeat
        bridge.recordReveal(listOf(ForgeCardId(chosen.id)), ownerSeat)
    }

    private fun <T : GameEntity> chooseMutateTopCard(
        optionList: FCollectionView<T>,
        sa: SpellAbility,
        title: String?,
        isOptional: Boolean,
    ): T? {
        val labels = optionList.map { it.entityLabel() }
        val targetIndex = optionList.indexOfFirst { entity -> entity is Card && entity.id != sa.hostCard?.id }
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = title ?: "Choose creature to be on top",
                options = labels,
                min = if (isOptional) 0 else 1,
                max = 1,
                defaultIndex = targetIndex.takeIf { it >= 0 } ?: 0,
                semantic = PromptSemantic.MutateTopBottom,
                candidateRefs = buildCandidateRefs(optionList),
                sourceEntityId = sa.hostCard?.id,
            )
        val idx = bridge.requestChoice(request).firstOrNull()
        return if (idx != null && idx in 0 until optionList.size) {
            optionList.get(idx)
        } else {
            if (isOptional) null else optionList.get(request.defaultIndex)
        }
    }

    fun <T : GameEntity> chooseEntities(
        optionList: FCollectionView<T>,
        min: Int,
        max: Int,
        title: String?,
        // Required (no default) — drops out of the SA chain into the wire as
        // sourceEntityId, which becomes SelectNReq.sourceId + the outer
        // prompt's first CardId Number parameter. Without this the panel
        // header degrades to a stub. New callers must thread the SpellAbility
        // through; pass null only if there genuinely is no source spell.
        sa: SpellAbility?,
    ): List<T> {
        if (optionList.isEmpty()) return emptyList()
        val labels = optionList.map { it.entityLabel() }
        val candidateRefs = buildCandidateRefs(optionList)
        val plan =
            ChooseEntitiesPlanner.plan(
                ChooseEntitiesContext(
                    sa = sa,
                    min = min,
                    max = max,
                    optionCount = optionList.size,
                    hiddenLibrarySelection = isHiddenLibrarySelection(optionList.filterIsInstance<Card>(), optionList.size),
                ),
            )
        if (plan.autoReturnPolicy.shouldReturnAll) return optionList.toList()
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = title ?: "Choose cards",
                options = labels,
                min = plan.effectiveMin,
                max = plan.effectiveMax,
                defaultIndex = 0,
                semantic = plan.semantic,
                candidateRefs = plan.candidateRefsPolicy.candidateRefs(candidateRefs),
                unfilteredRefs = plan.candidateRefsPolicy.unfilteredRefs(candidateRefs, plan.semantic),
                sourceEntityId = plan.sourceIdPolicy.sourceEntityId(sa),
            )
        val indices = bridge.requestChoice(request)
        return indices.filter { it in optionList.indices }.map { optionList.get(it) }
    }

    private fun SpellAbility.hasParamValue(
        name: String,
        value: String,
    ): Boolean = hasParam(name) && getParam(name).equals(value, ignoreCase = true)

    private fun isHiddenLibrarySelection(
        cards: List<Card>,
        optionCount: Int,
    ): Boolean = cards.size == optionCount && cards.isNotEmpty() && cards.all { it.zone?.zoneType == ZoneType.Library }

    fun chooseCardsForEffect(
        sourceList: CardCollectionView,
        sa: SpellAbility?,
        title: String?,
        min: Int,
        max: Int,
        isOptional: Boolean,
    ): CardCollectionView {
        if (sourceList.isEmpty()) return CardCollection()
        val reveal = bridge.journal.activeReveal()
        val plan =
            ChooseCardsForEffectPlanner.plan(
                ChooseCardsForEffectContext(
                    sa = sa,
                    hiddenLibrarySelection = isHiddenLibrarySelection(sourceList.filterIsInstance<Card>(), sourceList.size),
                    activeReveal = reveal != null,
                ),
            )
        if (plan.semantic == PromptSemantic.RevealChoose && reveal != null) {
            val effectiveMin = if (isOptional) 0 else min
            return chooseCardsViaBridgeForReveal(sourceList, effectiveMin, max, sa, reveal)
        }
        if (plan.mandatoryChoicePolicy.shouldAutoResolve(isOptional, sourceList.size, min)) return sourceList
        val effectiveMin = if (isOptional) 0 else min
        return chooseCardsViaBridge(
            sourceList,
            effectiveMin,
            max,
            title ?: "Choose cards",
            semantic = plan.semantic,
            candidateRefs = plan.candidateRefsPolicy.candidateRefs(buildCandidateRefs(sourceList)),
            sourceEntityId = plan.sourceIdPolicy.sourceEntityId(sa),
            forcePrompt = plan.forcePrompt,
        )
    }

    fun chooseCardsToRevealFromHand(
        min: Int,
        max: Int,
        valid: CardCollectionView,
    ): CardCollectionView = chooseCardsViaBridge(valid, min, max.coerceAtMost(valid.size), "Choose cards to reveal")

    // -- Discard / sacrifice ---------------------------------------------

    fun choosePermanentsToSacrifice(
        sa: SpellAbility?,
        min: Int,
        max: Int,
        validTargets: CardCollectionView,
        message: String?,
    ): CardCollectionView {
        val semantic =
            if (sa?.isOffering == true || sa?.isEmerge == true) {
                PromptSemantic.SelectNCostSacrifice
            } else {
                PromptSemantic.SelectNSacrificeEffect
            }
        return chooseCardsViaBridge(
            validTargets,
            min,
            max,
            message ?: "Choose permanents to sacrifice",
            semantic = semantic,
            candidateRefs = buildCandidateRefs(validTargets),
            sourceEntityId = sa?.hostCard?.id,
        )
    }

    fun choosePermanentsToDestroy(
        min: Int,
        max: Int,
        validTargets: CardCollectionView,
        message: String?,
    ): CardCollectionView = chooseCardsViaBridge(validTargets, min, max, message ?: "Choose permanents to destroy")

    fun chooseCardsToDiscardFrom(
        sa: SpellAbility?,
        validCards: CardCollection,
        min: Int,
        max: Int,
    ): CardCollection {
        val reveal = bridge.journal.activeReveal()
        if (reveal != null) {
            // Reveal-choose path (Duress, Thoughtseize): validCards is filtered,
            // reveal.allHandCardIds has the full hand for unfilteredIds.
            return chooseCardsViaBridgeForReveal(validCards, min, max, sa, reveal)
        }
        return chooseCardsViaBridge(
            validCards,
            min,
            max,
            "Choose cards to discard",
            semantic = PromptSemantic.SelectNDiscard,
            candidateRefs = buildCandidateRefs(validCards),
            sourceEntityId = sa?.hostCard?.id,
        )
    }

    fun chooseCardsToDiscardFrom(
        discarder: Player,
        sa: SpellAbility?,
        validCards: CardCollection,
        min: Int,
        max: Int,
        visibleToChooser: CardCollectionView,
    ): CardCollection {
        val reveal = bridge.journal.activeReveal() ?: revealFromVisibleHand(discarder, visibleToChooser)
        if (reveal != null) {
            return chooseCardsViaBridgeForReveal(validCards, min, max, sa, reveal)
        }
        return chooseCardsViaBridge(
            validCards,
            min,
            max,
            "Choose cards to discard",
            semantic = PromptSemantic.SelectNDiscard,
            candidateRefs = buildCandidateRefs(validCards),
            sourceEntityId = sa?.hostCard?.id,
        )
    }

    private fun revealFromVisibleHand(
        discarder: Player,
        visibleToChooser: CardCollectionView,
    ): PromptSideEffect.RevealStarted? {
        if (visibleToChooser.isEmpty()) return null
        val visibleIds = visibleToChooser.mapNotNull { (it as? Card)?.let { card -> ForgeCardId(card.id) } }
        if (!revealsWholeCurrentHand(visibleIds, discarder)) return null
        val ownerSeat = if (discarder.lobbyPlayer is LobbyPlayerAi) seating.familiarSeat else seating.humanSeat
        return PromptSideEffect.RevealStarted(visibleIds, ownerSeat)
    }

    fun chooseCardsToDiscardToMaximumHandSize(
        nDiscard: Int,
        hand: CardCollectionView,
    ): CardCollection =
        chooseCardsViaBridge(
            CardCollection(hand),
            nDiscard,
            nDiscard,
            "Discard to hand size (select $nDiscard)",
            semantic = PromptSemantic.SelectNDiscard,
            candidateRefs = buildCandidateRefs(hand),
        )

    fun chooseCardsToDiscardUnlessType(
        min: Int,
        hand: CardCollectionView,
        param: Array<String>,
        sa: SpellAbility,
    ): CardCollectionView {
        val labels =
            hand.map { card ->
                val isMatchingType = card.isValid(param, sa.activatingPlayer, sa.hostCard, sa)
                if (isMatchingType) "${card.name} (${param.joinToString("/")})" else card.name
            }
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose $min card(s) to discard (or pick a ${param.joinToString("/")} to reveal)",
                options = labels,
                min = 1,
                max = min,
                defaultIndex = 0,
            )
        val indices = bridge.requestChoice(request)
        val handList = hand.toList()
        val result = CardCollection()
        for (idx in indices) {
            val card = handList.getOrNull(idx) ?: continue
            result.add(card)
        }
        return result
    }

    // -- Reveal ------------------------------------------------------------

    /**
     * Record revealed card IDs for the annotation pipeline. Whole-hand reveals
     * also arm reveal-choose state so follow-up prompts can emit the full
     * `unfilteredIds` list. The caller is responsible for the super.reveal
     * delegation (GUI display).
     */
    fun captureReveal(
        cards: CardCollectionView,
        zone: ZoneType,
        owner: Player,
    ) {
        if (cards.isEmpty()) return
        val cardIds = cards.mapNotNull { card -> (card as? Card)?.let { ForgeCardId(it.id) } }
        val ownerSeat = if (owner.lobbyPlayer is LobbyPlayerAi) seating.familiarSeat else seating.humanSeat
        bridge.recordReveal(cardIds, ownerSeat)
        if (zone == ZoneType.Hand && revealsWholeCurrentHand(cardIds, owner)) {
            TargetingCoordinator.startReveal(bridge, cardIds, ownerSeat)
        }
    }

    fun captureReveal(
        cards: List<CardView>,
        zone: ZoneType,
        owner: PlayerView,
        players: Iterable<Player>,
    ) {
        if (cards.isEmpty()) return
        val ownerPlayer = players.firstOrNull { owner.isLobbyPlayer(it.lobbyPlayer) } ?: return
        val cardIds = cards.map { ForgeCardId(it.id) }
        val ownerSeat = if (ownerPlayer.lobbyPlayer is LobbyPlayerAi) seating.familiarSeat else seating.humanSeat
        bridge.recordReveal(cardIds, ownerSeat)
        if (zone == ZoneType.Hand && revealsWholeCurrentHand(cardIds, ownerPlayer)) {
            TargetingCoordinator.startReveal(bridge, cardIds, ownerSeat)
        }
    }

    private fun revealsWholeCurrentHand(
        cardIds: List<ForgeCardId>,
        owner: Player,
    ): Boolean {
        val handIds = owner.getZone(ZoneType.Hand).cards.map { ForgeCardId(it.id) }
        return handIds.isNotEmpty() && cardIds.size == handIds.size && cardIds.toSet() == handIds.toSet()
    }

    // -- Zone ordering ----------------------------------------------------

    fun arrangeForScry(topN: CardCollection): ImmutablePair<CardCollection, CardCollection> =
        arrangeTopNCards(
            topN,
            label = "Scry",
            awayZone = "Bottom of library",
            singleAwayPrompt = { name -> "Scry: Put $name on top or bottom?" },
            multiAwayPrompt = "Scry: Select cards to put on bottom of library",
        )

    fun arrangeForSurveil(topN: CardCollection): ImmutablePair<CardCollection, CardCollection> =
        arrangeTopNCards(
            topN,
            label = "Surveil",
            awayZone = "Graveyard",
            singleAwayPrompt = { name -> "Surveil: Put $name on top or into graveyard?" },
            multiAwayPrompt = "Surveil: Select cards to put into graveyard",
        )

    fun orderMoveToZoneList(
        cards: CardCollectionView,
        zone: ZoneType,
        sa: SpellAbility?,
    ): CardCollectionView {
        if (cards.size <= 1) return cards
        val labels = cards.map { it.name }
        val semantic = orderSemantic(zone, sa)
        recordPendingOrderZoneMove(cards, zone, semantic)
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = "Order cards being put into ${zone.name.lowercase()}",
                options = labels,
                min = cards.size,
                max = cards.size,
                defaultIndex = 0,
                semantic = semantic,
                candidateRefs = buildCandidateRefs(cards),
                sourceEntityId = sa?.hostCard?.id,
            )
        val indices = bridge.requestChoice(request)
        val ordered = orderedCards(cards, indices)
        if (semantic == PromptSemantic.OrderForTop && zone.isDeck) {
            return CardCollection(ordered.reversed())
        }
        return ordered
    }

    private fun recordPendingOrderZoneMove(
        cards: CardCollectionView,
        zone: ZoneType,
        semantic: PromptSemantic,
    ) {
        if (semantic != PromptSemantic.OrderForTop || !zone.isDeck) return
        val movedCards = cards.filterIsInstance<Card>()
        if (movedCards.size != cards.size || movedCards.any { !it.isInZone(ZoneType.Hand) }) return
        val owner = movedCards.firstOrNull()?.owner ?: return
        if (movedCards.any { it.owner != owner }) return
        val ownerSeat = if (owner.lobbyPlayer is LobbyPlayerAi) seating.familiarSeat else seating.humanSeat
        bridge.recordPendingOrderZoneMove(
            InteractivePromptBridge.PendingOrderZoneMove(
                seatId = ownerSeat,
                forgeCardIds = movedCards.map { ForgeCardId(it.id) },
                putOnTop = true,
            ),
        )
    }

    private fun orderSemantic(
        zone: ZoneType,
        sa: SpellAbility?,
    ): PromptSemantic =
        when {
            !zone.isDeck -> PromptSemantic.OrderGeneric
            isLibraryBottomOrder(sa) -> PromptSemantic.OrderForBottom
            else -> PromptSemantic.OrderForTop
        }

    private fun isLibraryBottomOrder(sa: SpellAbility?): Boolean {
        val explicitPosition = libraryPosition(sa, "LibraryPosition2") ?: libraryPosition(sa, "LibraryPosition")
        if (explicitPosition != null) return explicitPosition < 0
        return sa?.api == ApiType.Dig
    }

    private fun libraryPosition(
        sa: SpellAbility?,
        param: String,
    ): Int? {
        if (sa == null || !sa.hasParam(param)) return null
        return runCatching { AbilityUtils.calculateAmount(sa.hostCard, sa.getParam(param), sa) }.getOrNull()
    }

    private fun orderedCards(
        cards: CardCollectionView,
        indices: List<Int>,
    ): CardCollection {
        val result = CardCollection()
        for (idx in indices) {
            if (idx in 0 until cards.size) result.add(cards.get(idx))
        }
        for (card in cards) {
            if (card !in result) result.add(card)
        }
        return result
    }

    // -- Interactive target selection ------------------------------------

    fun selectTargets(
        validTargets: List<Card>,
        sa: SpellAbility,
        mandatory: Boolean,
        numTargets: Int?,
        divisionValues: Collection<Int>?,
    ): TargetSelectionResult {
        val tgt = sa.targetRestrictions ?: return TargetSelectionResult(false, true)
        val minTargets = numTargets ?: sa.minTargets
        val maxTargets = numTargets ?: sa.maxTargets

        val allCandidates: List<GameEntity> =
            buildList {
                for (p in sa.activatingPlayer.game.players) {
                    if (sa.canTarget(p)) add(p)
                }
                addAll(validTargets)
            }

        log.info(
            "selectTargetsInteractively: spell={} candidates={} ({}p+{}c) mandatory={} min={} max={}",
            sa.hostCard?.name,
            allCandidates.map { it.name },
            allCandidates.count { it is Player },
            validTargets.size,
            mandatory,
            minTargets,
            maxTargets,
        )

        if (allCandidates.isEmpty()) return TargetSelectionResult(false, true)

        // Auto-resolve: single valid target + mandatory → pick it without prompting.
        if (allCandidates.size == 1 && mandatory && minTargets >= 1) {
            val target = allCandidates[0]
            if (target !is Card || !target.isInZone(ZoneType.Stack)) {
                sa.targets.add(target)
                recordPendingTargetSpec(sa, target)
                return TargetSelectionResult(true, true)
            }
        }

        val labels =
            allCandidates.map { entity ->
                when (entity) {
                    is Card -> {
                        val zone =
                            entity.zone
                                ?.zoneType
                                ?.name
                                .orEmpty()
                        val ctrl = entity.controller?.name.orEmpty()
                        "${entity.name} ($zone, $ctrl)"
                    }
                    is Player -> entity.name
                    else -> entity.toString()
                }
            }
        val candidateRefs = buildCandidateRefs(allCandidates)
        val prompt =
            tgt.vtSelection?.takeIf { it.isNotBlank() }
                ?: "Choose target for ${sa.hostCard?.name ?: sa}"

        val numAlreadyTargeted = sa.targets.size
        val stillNeeded = maxTargets - numAlreadyTargeted
        val minNeeded = (minTargets - numAlreadyTargeted).coerceAtLeast(if (mandatory) 1 else 0)

        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = prompt,
                options = labels,
                min = minNeeded.coerceAtMost(allCandidates.size),
                max = stillNeeded.coerceAtMost(allCandidates.size),
                defaultIndex = 0,
                candidateRefs = candidateRefs,
                sourceEntityId = sa.hostCard?.id,
                isTriggeredAbility = sa.isTrigger,
                forgeAbilityId = if (sa.isTrigger) sa.id else 0,
            )
        val indices = bridge.requestChoice(request, targetingSa = sa)

        if (indices.isEmpty() && mandatory && minTargets > 0) {
            return TargetSelectionResult(false, false)
        }

        for (idx in indices) {
            val entity = allCandidates.getOrNull(idx) ?: continue
            if (entity is Card && sa.isDividedAsYouChoose && divisionValues != null) {
                sa.addDividedAllocation(entity, sa.stillToDivide / (stillNeeded - indices.indexOf(idx)).coerceAtLeast(1))
            }
            sa.targets.add(entity)
            recordPendingTargetSpec(sa, entity)
        }

        val totalTargeted = sa.targets.size
        val done = totalTargeted >= maxTargets || indices.isEmpty()
        val chosen = indices.isNotEmpty() || minTargets == 0
        return TargetSelectionResult(chosen, done)
    }

    // -- Private helpers --------------------------------------------------

    private fun recordPendingTargetSpec(
        sa: SpellAbility,
        target: forge.game.GameEntity,
    ) {
        val spellCard = sa.hostCard ?: return
        val isTrigger = sa.isTrigger
        val (targetCardId, targetSeatId) =
            when (target) {
                is Card -> target.id to null
                is forge.game.player.Player -> {
                    val seat =
                        if (target.lobbyPlayer is forge.ai.LobbyPlayerAi) {
                            seating.familiarSeat
                        } else {
                            seating.humanSeat
                        }
                    null to seat.value
                }
                else -> return
            }
        // Resolve the spell card's iid here, while the spell is still on the
        // stack. Re-deriving from the live bridge at TargetSpec emission time
        // is unsafe for multi-target spells: per-group TargetSpecs are emitted
        // across multiple GSM drains, and the spell's iid changes when it
        // leaves the stack (e.g. Stack→Graveyard at resolve), which would
        // split the per-group entries onto two iids. Triggered abilities use
        // a stack-ability surrogate iid that's stable across drains, resolved
        // at emission time from `forgeAbilityId` (see StateMapper).
        val affectorIid =
            if (isTrigger) {
                0
            } else {
                bridge.forgeIidResolver?.invoke(ForgeCardId(spellCard.id))?.value ?: 0
            }
        val abilityIdentity = bridge.resolveAbilityIdentity(sa)
        val promptId =
            when {
                abilityIdentity?.keywordFamily == AbilityKeywordFamily.Mentor -> PromptIds.MENTOR_TARGET
                sa.isMutate -> PromptIds.MUTATE_TARGET
                else -> null
            }
        bridge.addPendingTargetSpec(
            InteractivePromptBridge.PendingTarget(
                spellForgeCardId = spellCard.id,
                spellName = spellCard.name,
                index = bridge.nextTargetSpecIndex(),
                affectorInstanceIdAtRecord = affectorIid,
                targetForgeCardId = targetCardId,
                targetSeatId = targetSeatId,
                isTriggeredAbility = isTrigger,
                promptId = promptId,
                abilityIdentity = abilityIdentity,
                forgeAbilityId = sa.id,
            ),
        )
    }

    private fun arrangeTopNCards(
        topN: CardCollection,
        label: String,
        awayZone: String,
        singleAwayPrompt: (String) -> String,
        multiAwayPrompt: String,
    ): ImmutablePair<CardCollection, CardCollection> {
        if (topN.isEmpty()) return ImmutablePair.of(null, null)
        val refs = buildCandidateRefs(topN)
        val groupingSemantic =
            when (label) {
                "Surveil" -> PromptSemantic.GroupingSurveil
                else -> PromptSemantic.GroupingScry
            }
        if (topN.size == 1) {
            val request =
                PromptRequest(
                    promptType = "confirm",
                    message = singleAwayPrompt(topN[0].name),
                    options = listOf("Top of library", awayZone),
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                    semantic = groupingSemantic,
                    candidateRefs = refs,
                )
            val result = bridge.requestChoice(request)
            return if (result.firstOrNull() == 1) {
                ImmutablePair.of(null, topN)
            } else {
                ImmutablePair.of(topN, null)
            }
        }
        val labels = topN.map { it.name }
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = multiAwayPrompt,
                options = labels,
                min = 0,
                max = topN.size,
                defaultIndex = 0,
                semantic = groupingSemantic,
                candidateRefs = refs,
            )
        val awayIndices = bridge.requestChoice(request)
        val toAway = CardCollection()
        val toTop = CardCollection()
        for ((i, card) in topN.withIndex()) {
            if (i in awayIndices) toAway.add(card) else toTop.add(card)
        }
        if (toTop.size > 1) {
            val topLabels = toTop.map { it.name }
            val orderReq =
                PromptRequest(
                    promptType = "order",
                    message = "Order cards for top of library (first = top)",
                    options = topLabels,
                    min = toTop.size,
                    max = toTop.size,
                    defaultIndex = 0,
                    semantic = PromptSemantic.OrderForTop,
                    candidateRefs = buildCandidateRefs(toTop),
                )
            val ordering = bridge.requestChoice(orderReq)
            val ordered = orderedCards(toTop, ordering)
            return ImmutablePair.of(ordered, if (toAway.isEmpty()) null else toAway)
        }
        return ImmutablePair.of(
            if (toTop.isEmpty()) null else toTop,
            if (toAway.isEmpty()) null else toAway,
        )
    }

    /**
     * Common bridge-based card-selection prompt. Public for the `chooseCardsForCost`
     * override, which is structurally a "pick N cards from this list" prompt and
     * shares the same protocol shape — but conceptually a cost-payment override, so
     * it lives on [leyline.bridge.forge.PlayerController].
     */
    @Suppress("LongParameterList") // mirrors PromptRequest's cost-selection surface
    fun chooseCardsViaBridge(
        cards: CardCollectionView,
        min: Int,
        max: Int,
        message: String,
        semantic: PromptSemantic = PromptSemantic.Generic,
        candidateRefs: List<PromptCandidateRefDto> = emptyList(),
        sourceEntityId: Int? = null,
        forcePrompt: Boolean = false,
        costSelectionWeights: List<Int> = emptyList(),
        minSelectionWeight: Int? = null,
    ): CardCollection {
        if (cards.isEmpty()) return CardCollection()
        val effectiveMax = max.coerceAtMost(cards.size)
        val effectiveMin = min.coerceAtLeast(0).coerceAtMost(effectiveMax)
        if (!forcePrompt && cards.size <= effectiveMin) return CardCollection(cards)
        val labels = cards.map { it.name }
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = message,
                options = labels,
                min = effectiveMin,
                max = effectiveMax,
                defaultIndex = 0,
                semantic = semantic,
                candidateRefs = candidateRefs,
                sourceEntityId = sourceEntityId,
                costSelectionWeights = costSelectionWeights,
                minSelectionWeight = minSelectionWeight,
            )
        val indices = bridge.requestChoice(request)
        val result = CardCollection()
        for (idx in indices) {
            if (idx in 0 until cards.size) result.add(cards.get(idx))
        }
        return result
    }

    /**
     * Reveal-choose bridge path: builds a prompt with filtered [candidateRefs] (selectable)
     * and unfiltered [unfilteredRefs] (all revealed cards) for the SelectNReq wire shape.
     */
    private fun chooseCardsViaBridgeForReveal(
        filteredCards: CardCollectionView,
        min: Int,
        max: Int,
        sa: SpellAbility?,
        reveal: PromptSideEffect.RevealStarted,
        message: String = revealChoiceMessage(sa, null),
        recordExiledUnderSource: Boolean = false,
    ): CardCollection {
        try {
            val candidateRefs = buildCandidateRefs(filteredCards)
            val unfilteredRefs =
                reveal.allHandCardIds.mapIndexed { idx, forgeCardId ->
                    PromptCandidateRefDto(idx, PromptCandidateKind.Card, forgeCardId.value)
                }
            val effectiveMin = if (filteredCards.isEmpty()) 0 else min.coerceAtLeast(0)
            val effectiveMax = if (filteredCards.isEmpty()) 0 else max.coerceAtMost(filteredCards.size)
            val labels = filteredCards.map { it.name }
            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message = message,
                    options = labels,
                    min = effectiveMin,
                    max = effectiveMax.coerceAtLeast(effectiveMin),
                    defaultIndex = 0,
                    semantic = PromptSemantic.RevealChoose,
                    candidateRefs = candidateRefs,
                    unfilteredRefs = unfilteredRefs,
                    sourceEntityId = sa?.hostCard?.id ?: currentSourceEntityId()?.takeIf { it > 0 },
                )
            val indices = bridge.requestChoice(request)
            if (recordExiledUnderSource) {
                recordRevealChoiceExileSources(indices, candidateRefs, request.sourceEntityId)
            }
            val result = CardCollection()
            for (idx in indices) {
                if (idx in 0 until filteredCards.size) {
                    result.add(filteredCards.get(idx) as Card)
                }
            }
            return result
        } finally {
            TargetingCoordinator.endReveal(bridge)
        }
    }

    private fun recordRevealChoiceExileSources(
        selectedIndices: List<Int>,
        candidateRefs: List<PromptCandidateRefDto>,
        sourceEntityId: Int?,
    ) {
        val source = sourceEntityId?.let(::ForgeCardId) ?: return
        val selectedCardIds =
            selectedIndices.mapNotNull { idx ->
                candidateRefs
                    .firstOrNull { it.index == idx }
                    ?.entityId
                    ?.let(::ForgeCardId)
            }
        selectedCardIds.forEach { cardId -> bridge.journal.record(PromptSideEffect.ExiledUnderSource(cardId, source)) }
    }

    private fun revealChoiceMessage(
        sa: SpellAbility?,
        title: String?,
    ): String =
        when {
            !title.isNullOrBlank() -> title
            sa?.api == ApiType.ChangeZone && sa.hasParamValue("Destination", "Exile") -> "Choose a card to exile"
            else -> "Choose a card to discard"
        }

    private fun isExileUnderSourceRevealChoice(
        sa: SpellAbility?,
        message: String,
    ): Boolean = sa?.let(::isExileUnderSourceChangeZone) ?: message.contains("exile", ignoreCase = true)

    private fun isExileUnderSourceChangeZone(sa: SpellAbility): Boolean =
        sa.api == ApiType.ChangeZone &&
            sa.hasParamValue("Destination", "Exile") &&
            (sa.hasParamValue("Duration", "UntilHostLeavesPlay") || sa.hasParam("IsCurse"))

    private fun buildCandidateRefs(entities: Iterable<GameEntity>): List<PromptCandidateRefDto> = entities.toCandidateRefs()

    private fun GameEntity.entityLabel(): String =
        when (this) {
            is Card -> name
            is Player -> name
            else -> toString()
        }

    companion object {
        fun recordLegendVictim(
            prompt: InteractivePromptBridge,
            cardId: ForgeCardId,
        ) {
            prompt.journal.record(PromptSideEffect.LegendVictim(cardId))
        }

        fun startReveal(
            prompt: InteractivePromptBridge,
            cardIds: List<ForgeCardId>,
            ownerSeat: SeatId,
        ) {
            prompt.journal.record(PromptSideEffect.RevealStarted(cardIds, ownerSeat))
        }

        fun endReveal(prompt: InteractivePromptBridge) {
            prompt.journal.endActiveReveal()
        }
    }
}
