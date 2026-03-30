package leyline.bridge

import forge.LobbyPlayer
import forge.ai.AiCostDecision
import forge.ai.ComputerUtilMana
import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import forge.game.Game
import forge.game.GameEntity
import forge.game.GameObject
import forge.game.ability.AbilityUtils
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.card.CardCollectionView
import forge.game.combat.Combat
import forge.game.combat.CombatUtil
import forge.game.cost.Cost
import forge.game.cost.CostPart
import forge.game.cost.CostPayment
import forge.game.keyword.Keyword
import forge.game.keyword.KeywordInterface
import forge.game.player.DelayedReveal
import forge.game.player.PlaySpellAbility
import forge.game.player.Player
import forge.game.player.PlayerActionConfirmMode
import forge.game.player.PlayerController.BinaryChoiceType
import forge.game.replacement.ReplacementEffect
import forge.game.spellability.AbilitySub
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import forge.player.PlayerControllerHuman
import forge.util.collect.FCollectionView
import leyline.DevCheck
import org.apache.commons.lang3.tuple.ImmutablePair
import org.slf4j.LoggerFactory

/**
 * Web player controller: extends [PlayerControllerHuman] with a [WebGuiGame]
 * adapter so all 157 interactive methods route through [InteractivePromptBridge].
 *
 * Methods that PCHuman implements via desktop-only classes (InputConfirm,
 * InputSelectCardsFromList, FModel, GuiBase) are overridden here with
 * bridge-based implementations. The ~130 methods that use pure getGui() calls
 * work automatically through [WebGuiGame].
 *
 * ## Threading model
 *
 * **Every override runs on the Forge engine thread.** The engine calls these
 * methods synchronously during game loop execution. Methods that need client
 * input block the engine thread via [InteractivePromptBridge.requestChoice]
 * (`CompletableFuture.get()`). The Netty I/O thread unblocks by completing
 * the future. This means:
 *
 * - A missing or broken override → engine thread blocks forever (timeout)
 * - A slow override → entire game loop stalls (no other priority stops fire)
 * - [notifyStateChanged] must be called *before* [GameActionBridge.awaitAction]
 *   so the client sees updated state before being asked for a decision
 *
 * ## Key methods
 *
 * - [chooseSpellAbilityToPlay]: **The engine's main game loop entry point.**
 *   Called repeatedly by Forge's game loop at each priority window. Returns
 *   null = pass priority, non-null = play the spell. Calls [notifyStateChanged]
 *   → [GameActionBridge.awaitAction] to block until the client responds.
 *
 * - [playChosenSpellAbility]: Resolves the chosen spell through Forge's
 *   [PlaySpellAbility] path (costs, targets, mana). **Cannot use
 *   [InteractivePromptBridge] for optional cost decisions here** — the engine
 *   thread is already blocked in this call, and auto-pass can't run until the
 *   engine returns. Auto-accepts optional costs instead.
 *
 * - [declareAttackers] / [declareBlockers]: Same pattern as
 *   [chooseSpellAbilityToPlay] — notify → await → translate → submit.
 *
 * ## Cross-class flag contracts
 *
 * Several overrides set flags on [InteractivePromptBridge] that are consumed
 * by [GameEventCollector][leyline.game.GameEventCollector] to disambiguate
 * zone-change events. All writes and reads happen on the engine thread:
 *
 * - **searchedToHandCards:** Set in [chooseSingleEntityForEffect] when a
 *   search effect (tutor) moves a card Library→Hand. Consumed (and removed)
 *   by [GameEventCollector.isSearchedToHand] during the subsequent
 *   `GameEventCardChangeZone` — produces [TransferCategory.Put] instead of Draw.
 *
 * - **legendRuleVictims:** Set in [chooseSingleEntityForEffect] when resolving
 *   the legend rule SBA. Consumed by [GameEventCollector.isLegendRuleVictim]
 *   during the subsequent `GameEventCardChangeZone` — produces
 *   [TransferCategory.SBA_LegendRule] instead of generic Destroy.
 *
 * - **stashedOptionalCostIndices:** Set by [TargetingHandler] after the
 *   client responds to CastingTimeOptionsReq. Consumed (and nulled) by
 *   [chooseOptionalCosts] during spell resolution.
 *
 * These flags are single-use: consumed on first match, then removed/nulled.
 * If a flag is not consumed (e.g. the zone change never fires), it persists
 * until the next match or is harmlessly ignored.
 *
 * See ADR-007 for architecture details.
 */
class WebPlayerController(
    game: Game,
    player: Player,
    lobbyPlayer: LobbyPlayer,
    private val bridge: InteractivePromptBridge,
    private val actionBridge: GameActionBridge? = null,
    private val mulliganBridge: MulliganBridge? = null,
    private val phaseStopProfile: PhaseStopProfile? = null,
    private val onStateChanged: (() -> Unit)? = null,
    val smartPhaseSkip: Boolean = true,
    autoPassState: ClientAutoPassState? = null,
) : PlayerControllerHuman(game, player, lobbyPlayer) {

    @Volatile
    private var autoPassState: ClientAutoPassState? = autoPassState

    /**
     * Pending damage assignment prompt. Set by [assignCombatDamage] when the engine
     * needs manual damage distribution. The auto-pass loop detects this via
     * [CombatHandler.checkPendingDamageAssignment] and sends AssignDamageReq.
     * Completed by [CombatHandler.onAssignDamage] when the client responds.
     *
     * Uses a dedicated [CompletableFuture] instead of [GameActionBridge] to avoid
     * the auto-pass loop racing to auto-pass the pending action. Future engine-
     * initiated prompts (DistributionReq, NumericInputReq, SelectReplacementReq,
     * OptionalActionMessage, OrderReq) may benefit from the same approach if
     * they hit similar timing issues with the action bridge.
     */
    @Volatile var pendingDamageAssignment: DamageAssignmentPrompt? = null

    /** Cache for batched responses — subsequent attackers in Forge's per-attacker loop. */
    val damageAssignCache: MutableMap<ForgeCardId, MutableMap<Card?, Int>> = mutableMapOf()

    data class DamageAssignmentPrompt(
        val attacker: Card,
        val blockers: CardCollectionView,
        val damageDealt: Int,
        val defender: GameEntity?,
        val hasDeathtouch: Boolean,
        val hasTrample: Boolean,
        val future: java.util.concurrent.CompletableFuture<MutableMap<Card?, Int>>,
    )

    /** Set client auto-pass state (called by MatchSession after bridge connection). */
    fun setAutoPassState(state: ClientAutoPassState) {
        autoPassState = state
    }

    init {
        setGui(WebGuiGame(bridge, actionBridge))
    }

    companion object {
        private val log = LoggerFactory.getLogger(WebPlayerController::class.java)
        private const val MAX_DECISIONS = 200
    }

    /** Recent priority decisions for debug observability. */
    private val recentDecisions = ArrayDeque<PriorityDecisionEntry>()

    data class PriorityDecisionEntry(
        val ts: Long,
        val phase: String?,
        val turn: Int,
        val decision: PriorityDecision,
    )

    /** Snapshot of recent decisions for the debug API. */
    fun decisionLog(): List<PriorityDecisionEntry> = synchronized(recentDecisions) {
        recentDecisions.toList()
    }

    private fun recordDecision(decision: PriorityDecision) {
        val entry = PriorityDecisionEntry(
            ts = System.currentTimeMillis(),
            phase = game.phaseHandler.phase?.name,
            turn = game.phaseHandler.turn,
            decision = decision,
        )
        synchronized(recentDecisions) {
            recentDecisions.addLast(entry)
            while (recentDecisions.size > MAX_DECISIONS) recentDecisions.removeFirst()
        }
    }

    override fun isAI(): Boolean = false

    // ═══════════════════════════════════════════════════════════════════
    // Overrides for PCHuman methods that use desktop-only classes.
    // Methods using only getGui() calls are inherited and work via WebGuiGame.
    // ═══════════════════════════════════════════════════════════════════

    // -- Scry / Surveil ------------------------------------------------
    // PCHuman uses FModel.getPreferences + GuiBase + InputConfirm

    override fun arrangeForScry(topN: CardCollection): ImmutablePair<CardCollection, CardCollection> =
        arrangeTopNCards(
            topN,
            label = "Scry",
            awayZone = "Bottom of library",
            singleAwayPrompt = { name -> "Scry: Put $name on top or bottom?" },
            multiAwayPrompt = "Scry: Select cards to put on bottom of library",
        )

    override fun arrangeForSurveil(topN: CardCollection): ImmutablePair<CardCollection, CardCollection> =
        arrangeTopNCards(
            topN,
            label = "Surveil",
            awayZone = "Graveyard",
            singleAwayPrompt = { name -> "Surveil: Put $name on top or into graveyard?" },
            multiAwayPrompt = "Surveil: Select cards to put into graveyard",
        )

    private fun arrangeTopNCards(
        topN: CardCollection,
        label: String,
        awayZone: String,
        singleAwayPrompt: (String) -> String,
        multiAwayPrompt: String,
    ): ImmutablePair<CardCollection, CardCollection> {
        if (topN.isEmpty()) return ImmutablePair.of(null, null)
        val refs = buildCandidateRefs(topN)
        val groupingSemantic = when (label) {
            "Surveil" -> PromptSemantic.GroupingSurveil
            else -> PromptSemantic.GroupingScry
        }
        if (topN.size == 1) {
            val request = PromptRequest(
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
        val request = PromptRequest(
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
            val orderReq = PromptRequest(
                promptType = "order",
                message = "Order cards for top of library (first = top)",
                options = topLabels,
                min = toTop.size,
                max = toTop.size,
                defaultIndex = 0,
            )
            val ordering = bridge.requestChoice(orderReq)
            val ordered = CardCollection()
            for (idx in ordering) {
                if (idx in 0 until toTop.size) ordered.add(toTop[idx])
            }
            for (card in toTop) {
                if (card !in ordered) ordered.add(card)
            }
            return ImmutablePair.of(ordered, if (toAway.isEmpty()) null else toAway)
        }
        return ImmutablePair.of(
            if (toTop.isEmpty()) null else toTop,
            if (toAway.isEmpty()) null else toAway,
        )
    }

    // -- Reveal ---------------------------------------------------------------
    // PCHuman delegates to getGui().reveal() (desktop popup). We intercept
    // at controller level to capture forge card IDs for annotation pipeline.

    override fun reveal(
        cards: CardCollectionView,
        zone: ZoneType,
        owner: Player,
        messagePrefix: String?,
        addMsgSuffix: Boolean,
    ) {
        // Capture card IDs for annotation pipeline
        if (!cards.isEmpty()) {
            val cardIds = cards.mapNotNull { card ->
                // CardCollectionView items are Card objects (not CardView)
                (card as? Card)?.let { ForgeCardId(it.id) }
            }
            val ownerSeat = if (owner.lobbyPlayer is forge.ai.LobbyPlayerAi) SeatId(2) else SeatId(1)
            bridge.recordReveal(cardIds, ownerSeat)
            // Only set activeReveal for hand reveals (Duress, Revealing Eye, Thoughtseize).
            // Library reveals (Explore, search) should NOT trigger proxy synthesis.
            if (zone == ZoneType.Hand) {
                bridge.activeReveal = InteractivePromptBridge.ActiveReveal(cardIds, ownerSeat)
            }
        }
        // Delegate to parent for GUI display (WebGuiGame no-op log)
        super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix)
    }

    // -- Sacrifice / Destroy ----------------------------------------------
    // PCHuman uses InputSelectCardsFromList

    override fun choosePermanentsToSacrifice(
        sa: SpellAbility?,
        min: Int,
        max: Int,
        validTargets: CardCollectionView,
        message: String?,
    ): CardCollectionView = chooseCardsViaBridge(
        validTargets,
        min,
        max,
        message ?: "Choose permanents to sacrifice",
    )

    override fun choosePermanentsToDestroy(
        sa: SpellAbility?,
        min: Int,
        max: Int,
        validTargets: CardCollectionView,
        message: String?,
    ): CardCollectionView = chooseCardsViaBridge(
        validTargets,
        min,
        max,
        message ?: "Choose permanents to destroy",
    )

    // -- Discard -----------------------------------------------------------
    // PCHuman uses InputSelectCardsFromList

    override fun chooseCardsToDiscardFrom(
        p: Player,
        sa: SpellAbility?,
        validCards: CardCollection,
        min: Int,
        max: Int,
    ): CardCollection {
        val reveal = bridge.activeReveal
        if (reveal != null) {
            // Reveal-choose path (Duress, Thoughtseize): validCards is filtered,
            // reveal.allHandCardIds has the full hand for unfilteredIds.
            return chooseCardsViaBridgeForReveal(validCards, min, max, sa, reveal)
        }
        return chooseCardsViaBridge(validCards, min, max, "Choose cards to discard")
    }

    override fun chooseCardsToDiscardToMaximumHandSize(nDiscard: Int): CardCollection {
        // PCHuman uses GuiBase + InputSelectCardsFromList
        val hand = player.getZone(ZoneType.Hand).cards
        return chooseCardsViaBridge(
            CardCollection(hand),
            nDiscard,
            nDiscard,
            "Discard to hand size (select $nDiscard)",
        )
    }

    override fun chooseCardsToRevealFromHand(min: Int, max: Int, valid: CardCollectionView): CardCollectionView {
        // PCHuman uses InputSelectCardsFromList
        return chooseCardsViaBridge(valid, min, max.coerceAtMost(valid.size), "Choose cards to reveal")
    }

    // -- Generic choose cards for effect -----------------------------------
    // PCHuman uses useSelectCardsInput → InputSelectCardsFromList

    override fun chooseCardsForEffect(
        sourceList: CardCollectionView,
        sa: SpellAbility?,
        title: String?,
        min: Int,
        max: Int,
        isOptional: Boolean,
        params: MutableMap<String, Any>?,
    ): CardCollectionView {
        if (sourceList.isEmpty()) return CardCollection()
        val reveal = bridge.activeReveal
        if (reveal != null) {
            // Reveal-choose path (Revealing Eye chained SubAbility).
            val effectiveMin = if (isOptional) 0 else min
            return chooseCardsViaBridgeForReveal(sourceList, effectiveMin, max, sa, reveal)
        }
        if (!isOptional && sourceList.size <= min) return sourceList
        val effectiveMin = if (isOptional) 0 else min
        return chooseCardsViaBridge(sourceList, effectiveMin, max, title ?: "Choose cards")
    }

    // -- Choose single entity ----------------------------------------------
    // PCHuman uses useSelectCardsInput → InputSelectEntitiesFromList

    override fun <T : GameEntity> chooseSingleEntityForEffect(
        optionList: FCollectionView<T>,
        delayedReveal: DelayedReveal?,
        sa: SpellAbility?,
        title: String?,
        isOptional: Boolean,
        targetedPlayer: Player?,
        params: MutableMap<String, Any>?,
    ): T? {
        if (delayedReveal != null) reveal(delayedReveal)
        if (optionList.isEmpty()) return null
        if (optionList.size == 1 && !isOptional) return optionList.getFirst()

        // Legend rule SBA: prompt the player to choose which legendary to keep.
        // Real server sends SelectNReq (context=Resolution, min=1, max=1).
        val isLegendRule = sa?.api == ApiType.InternalLegendaryRule

        // Library search: ChangeZone effects (tutors) or any prompt that reveals
        // a hidden zone via DelayedReveal (library contents shown to searching player).
        val isSearch = sa?.api == ApiType.ChangeZone || delayedReveal != null

        val semantic = when {
            isLegendRule -> PromptSemantic.SelectNLegendRule
            isSearch -> PromptSemantic.Search
            else -> PromptSemantic.Generic
        }

        val labels = optionList.map { it.entityLabel() }
        val request = PromptRequest(
            promptType = "choose_cards",
            message = title ?: "Choose one",
            options = labels,
            min = if (isOptional) 0 else 1,
            max = 1,
            defaultIndex = 0,
            semantic = semantic,
            candidateRefs = buildCandidateRefs(optionList),
        )
        val indices = bridge.requestChoice(request)
        val idx = indices.firstOrNull()
        val chosen = if (idx != null && idx in 0 until optionList.size) {
            optionList.get(idx)
        } else {
            if (isOptional) null else optionList.getFirst()
        }

        // Search: mark chosen card so GameEventCollector emits CardSearchedToHand (Put category).
        if (isSearch && chosen is Card) {
            bridge.searchedToHandCards.add(ForgeCardId(chosen.id))
            log.debug("search to hand: marked card {} (id={})", chosen.name, chosen.id)
        }

        // Legend rule: mark all unchosen legendaries as victims for SBA_LegendRule annotation.
        if (isLegendRule && chosen != null) {
            val cards = optionList.filterIsInstance<Card>()
            for (card in cards) {
                if (card !== chosen) {
                    bridge.legendRuleVictims.add(ForgeCardId(card.id))
                }
            }
            log.info(
                "legend rule: player chose {} (id={}), victims={}",
                (chosen as? Card)?.name,
                (chosen as? Card)?.id,
                bridge.legendRuleVictims,
            )
        }

        return chosen
    }

    // chooseSingleCardForZoneChange — inherited from PCHuman, which delegates
    // to our overridden chooseSingleEntityForEffect. No override needed.

    // -- Choose multiple entities ------------------------------------------
    // PCHuman uses useSelectCardsInput → InputSelectEntitiesFromList

    override fun <T : GameEntity> chooseEntitiesForEffect(
        optionList: FCollectionView<T>,
        min: Int,
        max: Int,
        delayedReveal: DelayedReveal?,
        sa: SpellAbility?,
        title: String?,
        targetedPlayer: Player?,
        params: MutableMap<String, Any>?,
    ): List<T> {
        if (delayedReveal != null) reveal(delayedReveal)
        if (optionList.isEmpty()) return emptyList()
        val effectiveMax = max.coerceAtMost(optionList.size)
        val effectiveMin = min.coerceAtLeast(0).coerceAtMost(effectiveMax)
        if (optionList.size <= effectiveMin) return optionList.toList()
        val labels = optionList.map { it.entityLabel() }
        val request = PromptRequest(
            promptType = "choose_cards",
            message = title ?: "Choose cards",
            options = labels,
            min = effectiveMin,
            max = effectiveMax,
            defaultIndex = 0,
            candidateRefs = buildCandidateRefs(optionList),
        )
        val indices = bridge.requestChoice(request)
        return indices.filter { it in optionList.indices }.map { optionList.get(it) }
    }

    // -- Targeting ---------------------------------------------------------
    // Seam 2: chooseTargetsFor is inherited from PCHuman, which uses
    // TargetSelection → selectTargetsInteractively() (overridden below
    // in the ADR-010 Seam overrides section). This gives us MustTarget
    // filtering, divided-as-you-choose, multi-part targeting recursion,
    // random targets, and auto-target for single-candidate triggers.

    // -- Confirm -----------------------------------------------------------
    // PCHuman uses InputConfirm (desktop-only)

    override fun confirmAction(
        sa: SpellAbility?,
        mode: PlayerActionConfirmMode?,
        message: String?,
        options: MutableList<String>?,
        cardToShow: Card?,
        params: MutableMap<String, Any>?,
    ): Boolean {
        val displayMessage = message ?: "Confirm action?"
        val displayOptions = if (options.isNullOrEmpty()) {
            listOf("Yes", "No")
        } else {
            options.toList()
        }
        val request = PromptRequest(
            promptType = "confirm",
            message = displayMessage,
            options = displayOptions,
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() == 0
    }

    override fun confirmTrigger(wrapper: WrappedAbility): Boolean {
        // PCHuman uses FModel + GuiBase + InputConfirm
        if (wrapper.isMandatory) return true
        val source = wrapper.hostCard?.name ?: "Unknown"
        val description = wrapper.stackDescription?.takeIf { it.isNotBlank() }
            ?: "Triggered ability"
        val request = PromptRequest(
            promptType = "confirm",
            message = "$source: $description — Use this ability?",
            options = listOf("Yes", "No"),
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() == 0
    }

    override fun confirmPayment(costPart: CostPart?, question: String, sa: SpellAbility): Boolean {
        // PCHuman's version uses InputConfirm (desktop-only). Route through bridge.
        val request = PromptRequest(
            promptType = "confirm",
            message = question,
            options = listOf("Yes", "No"),
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() == 0
    }

    override fun confirmReplacementEffect(
        replacementEffect: ReplacementEffect,
        sa: SpellAbility?,
        affected: GameEntity?,
        prompt: String?,
    ): Boolean {
        // PCHuman uses GuiBase + InputConfirm
        val message = prompt ?: replacementEffect.toString()
        val request = PromptRequest(
            promptType = "confirm",
            message = message,
            options = listOf("Yes", "No"),
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() == 0
    }

    override fun chooseBinary(
        sa: SpellAbility?,
        question: String?,
        kindOfChoice: BinaryChoiceType?,
        defaultVal: Boolean?,
    ): Boolean {
        // PCHuman uses InputConfirm
        val labels = when (kindOfChoice) {
            BinaryChoiceType.HeadsOrTails -> listOf("Heads", "Tails")
            BinaryChoiceType.TapOrUntap -> listOf("Tap", "Untap")
            BinaryChoiceType.OddsOrEvens -> listOf("Odds", "Evens")
            BinaryChoiceType.UntapOrLeaveTapped -> listOf("Untap", "Leave Tapped")
            BinaryChoiceType.PlayOrDraw -> listOf("Play", "Draw")
            BinaryChoiceType.LeftOrRight -> listOf("Left", "Right")
            BinaryChoiceType.AddOrRemove -> listOf("Add Counter", "Remove Counter")
            BinaryChoiceType.IncreaseOrDecrease -> listOf("Increase", "Decrease")
            else -> listOf("Yes", "No")
        }
        val request = PromptRequest(
            promptType = "confirm",
            message = question ?: "Choose one",
            options = labels,
            min = 1,
            max = 1,
            defaultIndex = if (defaultVal != false) 0 else 1,
        )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() == 0
    }

    override fun chooseColor(message: String, sa: SpellAbility?, colors: forge.card.ColorSet): Byte {
        val cntColors = colors.countColors()
        if (cntColors == 0) return 0
        if (cntColors == 1) return colors.color
        // PCHuman uses InputConfirm.confirm → showAndWait (desktop-only).
        // Route through our prompt bridge instead.
        val colorOptions = colors.orderedColors.map { it.translatedName }
        val request = PromptRequest(
            promptType = "choose_one",
            message = message,
            options = colorOptions,
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        log.debug("chooseColor: options={}", colorOptions)
        val indices = bridge.requestChoice(request)
        val idx = indices.firstOrNull() ?: return 0
        if (idx >= colorOptions.size) return 0
        return colors.orderedColors.toList()[idx].colorMask
    }

    override fun willPutCardOnTop(c: Card): Boolean {
        // PCHuman uses InputConfirm
        val request = PromptRequest(
            promptType = "confirm",
            message = "Put ${c.name} on top or bottom of library?",
            options = listOf("Top", "Bottom"),
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() == 0
    }

    // -- Zone ordering ----------------------------------------------------
    // PCHuman uses FModel.getPreferences + ForgeConstants

    override fun orderMoveToZoneList(
        cards: CardCollectionView,
        zone: ZoneType,
        sa: SpellAbility?,
    ): CardCollectionView {
        if (cards.size <= 1) return cards
        // Always prompt for ordering (skip FModel preference check)
        val labels = cards.map { it.name }
        val request = PromptRequest(
            promptType = "choose_cards",
            message = "Order cards being put into ${zone.name.lowercase()}",
            options = labels,
            min = cards.size,
            max = cards.size,
            defaultIndex = 0,
        )
        val indices = bridge.requestChoice(request)
        val result = CardCollection()
        for (idx in indices) {
            if (idx in 0 until cards.size) result.add(cards.get(idx))
        }
        // Add any cards not in the response
        for (card in cards) {
            if (card !in result) result.add(card)
        }
        return result
    }

    // -- Mana payment ------------------------------------------------------
    // Upstream now routes cost payment through PlayerController.payManaCost /
    // applyManaToCost. We override those newer entry points below and keep one
    // auto-pay path instead of carrying older HumanPlay-era seams.

    // -- Convoke / Improvise -----------------------------------------------
    // PCHuman uses InputSelectCardsForConvokeOrImprovise (desktop-only, hangs).
    // Delegate to AI tap-selection for now.  Refs meeting 2026-02-08 Tier 1.

    override fun chooseCardsForConvokeOrImprovise(
        sa: SpellAbility,
        manaCost: ManaCost,
        untappedCards: CardCollectionView,
        artifacts: Boolean,
        creatures: Boolean,
        maxReduction: Int?,
    ): Map<Card, ManaCostShard> {
        val options = untappedCards.map { it.name }
        if (options.isEmpty()) return emptyMap()

        val keyword = if (artifacts) "improvise" else "convoke"
        val request = PromptRequest(
            promptType = "choose_cards",
            message = "Choose cards to tap for $keyword",
            options = options,
            min = 0,
            max = options.size.coerceAtMost(maxReduction ?: options.size),
            defaultIndex = 0,
        )
        val indices = bridge.requestChoice(request)
        if (indices.isEmpty()) return emptyMap()

        // Map selected cards to mana cost shards.
        // TODO: delegate shard assignment to ComputerUtilMana when implementing convoke support
        // Greedy WUBRG-order assignment can be suboptimal for multi-color
        // creatures vs costs with mixed colored/generic.
        // Track remaining colored/generic counts to avoid over-assigning.
        val colorShardCounts = mutableMapOf<ManaCostShard, Int>()
        for (shard in listOf(ManaCostShard.WHITE, ManaCostShard.BLUE, ManaCostShard.BLACK, ManaCostShard.RED, ManaCostShard.GREEN)) {
            val count = manaCost.getShardCount(shard)
            if (count > 0) colorShardCounts[shard] = count
        }
        var genericRemaining = manaCost.genericCost

        val cardList = untappedCards.toList()
        val result = mutableMapOf<Card, ManaCostShard>()
        for (idx in indices) {
            val card = cardList.getOrNull(idx) ?: continue
            val shard = pickShardForConvoke(card, colorShardCounts, genericRemaining)
            if (shard != null) {
                result[card] = shard
                if (shard == ManaCostShard.GENERIC) {
                    genericRemaining--
                } else {
                    val remaining = (colorShardCounts[shard] ?: 1) - 1
                    if (remaining <= 0) colorShardCounts.remove(shard) else colorShardCounts[shard] = remaining
                }
            }
        }
        return result
    }

    private fun pickShardForConvoke(
        card: Card,
        colorCounts: Map<ManaCostShard, Int>,
        genericRemaining: Int,
    ): ManaCostShard? {
        val colors = card.color
        // Try colored shards first
        if (colors.hasWhite() && (colorCounts[ManaCostShard.WHITE] ?: 0) > 0) return ManaCostShard.WHITE
        if (colors.hasBlue() && (colorCounts[ManaCostShard.BLUE] ?: 0) > 0) return ManaCostShard.BLUE
        if (colors.hasBlack() && (colorCounts[ManaCostShard.BLACK] ?: 0) > 0) return ManaCostShard.BLACK
        if (colors.hasRed() && (colorCounts[ManaCostShard.RED] ?: 0) > 0) return ManaCostShard.RED
        if (colors.hasGreen() && (colorCounts[ManaCostShard.GREEN] ?: 0) > 0) return ManaCostShard.GREEN
        // Fall back to generic
        if (genericRemaining > 0) return ManaCostShard.GENERIC
        return null
    }

    // -- Pay cost to prevent effect ----------------------------------------
    // payCostToPreventEffect is inherited from PCHuman. Upstream now resolves
    // card-picking/mana payment for these flows through chooseCardsForCost and
    // payManaCost/applyManaToCost, so the older extra seam callbacks are dead.

    // -- Discard unless type -----------------------------------------------
    // PCHuman uses InputSelectEntitiesFromList (desktop-only, hangs).
    // Bridge as a card selection prompt.  Refs meeting 2026-02-08 Tier 1.

    override fun chooseCardsToDiscardUnlessType(
        min: Int,
        hand: CardCollectionView,
        param: Array<String>,
        sa: SpellAbility,
    ): CardCollectionView {
        val labels = hand.map { card ->
            val isMatchingType = card.isValid(
                param,
                sa.activatingPlayer,
                sa.hostCard,
                sa,
            )
            if (isMatchingType) "${card.name} (${param.joinToString("/")})" else card.name
        }
        val request = PromptRequest(
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

    // -- Simultaneous triggered abilities ----------------------------------
    // Parent's HumanPlay.playSpellAbility routes targeting through the
    // player controller (→ bridge) and works for triggers without costs.
    // A full headless override needs prepareSingleSa-style targeting
    // (like the AI does) to avoid silently dropping triggers.
    // Defer to parent for now — only triggers with explicit costs would
    // need a web-safe override.  Refs meeting 2026-02-08 Tier 1.

    // ═══════════════════════════════════════════════════════════════════
    // Active controller overrides on the current upstream surface.
    // ═══════════════════════════════════════════════════════════════════

    // -- Cost Decision -----------------------------------------------------
    // WebCostDecision routes interactive cost choices through the bridge.
    override fun getCostDecisionMaker(
        player: Player,
        ability: SpellAbility,
        effect: Boolean,
        prompt: String?,
    ): forge.game.cost.CostDecisionMakerBase =
        WebCostDecision(
            this,
            player,
            ability,
            effect,
            bridge,
            ability.hostCard,
            PlaySpellAbility.getOrStringFromCost(ability, prompt),
        )

    // -- Target Selection --------------------------------------------------
    // Bridge-based interactive target selection.
    // TargetSelection validates candidates and zones; this method handles
    // the user interaction portion.

    override fun selectTargetsInteractively(
        validTargets: List<Card>,
        sa: SpellAbility,
        mandatory: Boolean,
        numTargets: Int?,
        divisionValues: Collection<Int>?,
        filter: java.util.function.Predicate<forge.game.GameObject>?,
        mustTargetFiltered: Boolean,
    ): forge.player.TargetSelectionResult {
        val tgt = sa.targetRestrictions ?: return forge.player.TargetSelectionResult(false, true)
        val minTargets = numTargets ?: sa.minTargets
        val maxTargets = numTargets ?: sa.maxTargets

        // Build the full candidate list: players + cards (engine's getAllCandidates
        // returns List<GameEntity> with players first, then cards).
        // validTargets is List<Card> only — we merge in player targets here.
        val allCandidates: List<forge.game.GameEntity> = buildList {
            // Add player targets from the engine (getAllCandidates checks canTarget)
            for (player in sa.activatingPlayer.game.players) {
                if (sa.canTarget(player)) add(player)
            }
            // Add card targets (already filtered by the engine's CardUtil.getValidCardsToTarget)
            addAll(validTargets)
        }

        log.info(
            "selectTargetsInteractively: spell={} candidates={} ({}p+{}c) mandatory={} min={} max={}",
            sa.hostCard?.name,
            allCandidates.map { it.name },
            allCandidates.count { it is forge.game.player.Player },
            validTargets.size,
            mandatory,
            minTargets,
            maxTargets,
        )

        if (allCandidates.isEmpty()) {
            return forge.player.TargetSelectionResult(false, true)
        }

        // Auto-resolve: single valid target + mandatory → pick it without prompting.
        if (allCandidates.size == 1 && mandatory && minTargets >= 1) {
            sa.targets.add(allCandidates[0])
            return forge.player.TargetSelectionResult(true, true)
        }

        val labels = allCandidates.map { entity ->
            when (entity) {
                is forge.game.card.Card -> {
                    val zone = entity.zone?.zoneType?.name ?: ""
                    val ctrl = entity.controller?.name ?: ""
                    "${entity.name} ($zone, $ctrl)"
                }
                is forge.game.player.Player -> entity.name
                else -> entity.toString()
            }
        }
        val candidateRefs = buildCandidateRefs(allCandidates)

        val prompt = tgt.vtSelection?.takeIf { it.isNotBlank() }
            ?: "Choose target for ${sa.hostCard?.name ?: sa}"

        val numAlreadyTargeted = sa.targets.size
        val stillNeeded = maxTargets - numAlreadyTargeted
        val minNeeded = (minTargets - numAlreadyTargeted).coerceAtLeast(if (mandatory) 1 else 0)

        val request = PromptRequest(
            promptType = "choose_cards",
            message = prompt,
            options = labels,
            min = minNeeded.coerceAtMost(allCandidates.size),
            max = stillNeeded.coerceAtMost(allCandidates.size),
            defaultIndex = 0,
            candidateRefs = candidateRefs,
            sourceEntityId = sa.hostCard?.id,
        )
        val indices = bridge.requestChoice(request)

        if (indices.isEmpty() && mandatory && minTargets > 0) {
            return forge.player.TargetSelectionResult(false, false)
        }

        for (idx in indices) {
            val entity = allCandidates.getOrNull(idx) ?: continue
            if (entity is forge.game.card.Card && sa.isDividedAsYouChoose && divisionValues != null) {
                sa.addDividedAllocation(entity, sa.stillToDivide / (stillNeeded - indices.indexOf(idx)).coerceAtLeast(1))
            }
            sa.targets.add(entity)
        }

        val totalTargeted = sa.targets.size
        val done = totalTargeted >= maxTargets || indices.isEmpty()
        val chosen = indices.isNotEmpty() || minTargets == 0
        return forge.player.TargetSelectionResult(chosen, done)
    }

    // -- Mana Payment ------------------------------------------------------
    override fun payManaCost(
        toPay: forge.card.mana.ManaCost,
        costPartMana: forge.game.cost.CostPartMana,
        sa: SpellAbility,
        prompt: String?,
        matrix: forge.game.mana.ManaConversionMatrix?,
        effect: Boolean,
    ): Boolean = PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect)

    override fun applyManaToCost(
        toPay: forge.game.mana.ManaCostBeingPaid,
        ability: SpellAbility,
        prompt: String?,
        matrix: forge.game.mana.ManaConversionMatrix?,
        effect: Boolean,
    ): Boolean {
        log.debug("applyManaToCost [AI]: {} for {}", toPay, ability.hostCard?.name)
        return ComputerUtilMana.payManaCost(toPay, ability, player, effect)
    }

    override fun chooseCardsForCost(
        optionList: forge.game.card.CardCollectionView,
        sa: SpellAbility,
        cpl: forge.game.cost.CostPartWithList,
        amount: Int,
        isOptional: Boolean,
        prompt: String,
    ): forge.game.card.CardCollectionView {
        val min = if (isOptional) 0 else amount
        return chooseCardsViaBridge(optionList, min, amount, prompt)
    }

    // -- Seam 5: chooseNumberForKeywordCost ----------------------------------
    // PCHuman uses InputConfirm.confirm() when max==1 (desktop-only, hangs on
    // web) and getGui().getInteger() for max>1 (bridged, works fine).
    // Override only the max==1 path to route through the bridge confirm prompt.

    override fun chooseNumberForKeywordCost(
        sa: SpellAbility,
        cost: Cost,
        keyword: KeywordInterface,
        prompt: String,
        max: Int,
    ): Int {
        if (max <= 0) return 0
        if (max == 1) {
            val request = PromptRequest(
                promptType = "confirm",
                message = prompt,
                options = listOf("Yes", "No"),
                min = 1,
                max = 1,
                defaultIndex = 0,
            )
            val indices = bridge.requestChoice(request)
            return if (indices.firstOrNull() == 0) 1 else 0
        }
        // max > 1: getGui().getInteger() is bridged through WebGuiGame, safe to inherit
        return super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max)
    }

    /**
     * Choose optional additional costs (kicker, buyback, etc.).
     *
     * Routes through [InteractivePromptBridge] as a multi-select choice.
     * The engine thread blocks here — same pattern as [chooseNumberForKeywordCost].
     * Client would see this as a CastingTimeOptionsReq with Kicker type.
     *
     * For now, presents as a confirm prompt per optional cost. Each cost is
     * offered individually with Yes/No. This matches how the engine structures
     * the choice (list of OptionalCostValue, choose 0..N).
     */
    /**
     * Choose optional additional costs (kicker, buyback, etc.).
     *
     * Reads the stashed decision from [InteractivePromptBridge.stashedOptionalCostIndices],
     * set by [TargetingHandler] after the client responds to CastingTimeOptionsReq.
     * Falls back to auto-accept if no stashed decision (e.g. MatchHarness tests).
     */
    override fun chooseOptionalCosts(
        chosenSa: SpellAbility,
        optionalCosts: MutableList<forge.game.spellability.OptionalCostValue>,
    ): MutableList<forge.game.spellability.OptionalCostValue> {
        val stashed = bridge.stashedOptionalCostIndices
        if (stashed != null) {
            bridge.stashedOptionalCostIndices = null
            val chosen = stashed.mapNotNull { optionalCosts.getOrNull(it) }.toMutableList()
            log.info("chooseOptionalCosts: using stashed decision — chose {} of {} for {}", chosen.size, optionalCosts.size, chosenSa.hostCard?.name)
            return chosen
        }
        // Fallback: auto-accept all (MatchHarness path, no prior CastingTimeOptionsReq)
        log.info("chooseOptionalCosts: auto-accepting {} optional costs for {}", optionalCosts.size, chosenSa.hostCard?.name)
        return optionalCosts
    }

    // -- Play spell --------------------------------------------------------
    // PCHuman uses HumanPlay + HumanPlaySpellAbility (desktop Input classes)

    override fun playChosenSpellAbility(chosenSa: SpellAbility): Boolean {
        // Use the upstream PlaySpellAbility path so cost decisions, optional
        // costs, rollback, splice, and mana conversion all stay centralized.
        //
        // Targets may be pre-set by chooseSpellAbilityToPlay() when the client
        // supplies them upfront (web UI path). When targets are NOT pre-set and
        // the spell uses targeting, we pass mayChooseTargets=true so the engine
        // invokes selectTargetsInteractively() → InteractivePromptBridge, which
        // lets the Arena/leyline path collect targets via SelectTargetsReq/Resp.
        chosenSa.setActivatingPlayer(player)

        if (chosenSa.isLandAbility) {
            if (chosenSa.canPlay()) chosenSa.resolve()
            return true
        }

        // Auto-apply optional additional costs (kicker, buyback, etc.) BEFORE
        // the spell hits the stack. Can't use InteractivePromptBridge here (deadlock —
        // engine thread is blocked, auto-pass can't run until engine returns).
        // Auto-accept all optional costs; engine checks mana during payment.
        var sa = chosenSa
        val optionalCosts = forge.game.GameActionUtil.getOptionalCostValues(sa)
        if (optionalCosts.isNotEmpty()) {
            log.info("playChosenSpellAbility: auto-accepting {} optional costs for {}", optionalCosts.size, sa.hostCard?.name)
            sa = forge.game.GameActionUtil.addOptionalCosts(sa, optionalCosts)
        }

        sa.hostCard?.setSplitStateToPlayAbility(sa)

        val needsTargeting = sa.usesTargeting() && sa.targets.isEmpty()
        val req = PlaySpellAbility(this, sa)
        return req.playAbility(needsTargeting, false, false)
    }

    override fun playSpellAbilityNoStack(effectSA: SpellAbility, mayChoseNewTargets: Boolean) {
        // Direct resolve — this is called by the engine for triggered abilities,
        // replacement effects, and other no-stack effects.
        // Must use AbilityUtils.resolve (not raw effectSA.resolve()) so that
        // chained sub-abilities execute — e.g. CharmEffect chains the chosen
        // mode as a sub, and the sub must resolve after the parent no-op.
        effectSA.activatingPlayer = player
        forge.game.ability.AbilityUtils.resolve(effectSA)
    }

    override fun chooseModeForAbility(
        sa: SpellAbility,
        possible: MutableList<AbilitySub>,
        min: Int,
        num: Int,
        allowRepeat: Boolean,
    ): List<AbilitySub> {
        if (!allowRepeat && min == num && num == possible.size) return possible
        if (possible.isEmpty()) return emptyList()

        val labels = possible.map { it.description ?: it.toString() }
        val request = PromptRequest(
            promptType = if (num == 1) "choose_one" else "choose_cards",
            message = "Choose mode for ${sa.hostCard.translatedName}",
            options = labels,
            min = min,
            max = num,
            defaultIndex = 0,
            semantic = PromptSemantic.ModalChoice,
            modalSourceCardName = sa.hostCard.name,
            sourceEntityId = sa.hostCard.id,
        )
        val result = bridge.requestChoice(request)
        return result.mapNotNull { idx -> possible.getOrNull(idx) }
    }

    // -- Mulligan / starting player ----------------------------------------
    // The engine's MulliganService calls these on the game thread.
    // When a MulliganBridge is wired, they block until the client
    // submits a decision. Without a bridge (tests, AI), they auto-decide.

    override fun mulliganKeepHand(mulliganingPlayer: Player, cardsToReturn: Int): Boolean {
        val mb = mulliganBridge ?: run {
            log.debug("mulliganKeepHand: no bridge, auto-keep for {}", player.name)
            return true
        }
        return mb.awaitKeepDecision(player.id, cardsToReturn)
    }

    override fun tuckCardsViaMulligan(hand: CardCollectionView, cardsToReturn: Int): CardCollectionView {
        if (cardsToReturn <= 0) return CardCollection()
        val mb = mulliganBridge ?: run {
            log.debug("tuckCardsViaMulligan: no bridge, auto-tuck {} for {}", cardsToReturn, player.name)
            val toReturn = CardCollection()
            for (i in 0 until cardsToReturn.coerceAtMost(hand.size)) {
                toReturn.add(hand[i])
            }
            return toReturn
        }
        val cards = mb.awaitTuckDecision(player.id, cardsToReturn, hand)
        return CardCollection(cards)
    }

    override fun chooseStartingPlayer(isFirstGame: Boolean): Player {
        // Engine determines starting player via coin flip in GameAction.startGame().
        // This is only called in specific variants; auto-choose self.
        log.debug("chooseStartingPlayer: auto-choose self ({})", player.name)
        return player
    }

    // ═══════════════════════════════════════════════════════════════════
    // Game-loop overrides (active only when actionBridge is set)
    // These are web-specific, not present in desktop PlayerControllerHuman.
    // ═══════════════════════════════════════════════════════════════════

    private var lastSeenTurn: Int = -1

    /**
     * Engine's main game loop entry point — called at every priority window.
     *
     * Flow: [notifyStateChanged] (sends GSM to client) → [GameActionBridge.awaitAction]
     * (blocks engine thread) → translate client response → return spell or null (pass).
     * Mana abilities loop without re-passing priority (they don't use the stack).
     */
    override fun chooseSpellAbilityToPlay(): List<SpellAbility>? {
        val ab = actionBridge ?: return super.chooseSpellAbilityToPlay()

        val handler = game.phaseHandler

        val currentTurn = handler.turn
        if (currentTurn != lastSeenTurn) {
            lastSeenTurn = currentTurn
            ab.setAutoPassUntilEndOfTurn(false)
        }

        if (ab.autoPassUntilEndOfTurn) {
            recordDecision(PriorityDecision.Skip(AutoPassReason.EndTurnFlag))
            return null
        }

        // Full control: skip all engine-side auto-pass, always return priority to session layer
        val fullControl = autoPassState?.isFullControl ?: false

        // Smart phase skip (ADR-008): auto-pass when player has no meaningful actions.
        // Only on own turn — on opponent's turn the player needs priority at their
        // phase stops to cast instants (e.g. Kill Shot during combat).
        // Never skip when stack has items — player should see stack state.
        // Never skip right after a prompt resolved — player needs to see the result.
        // Never skip when full control is on.
        if (!fullControl &&
            smartPhaseSkip &&
            !bridge.consumePromptResolved() &&
            handler.playerTurn?.id == player.id &&
            game.stack.isEmpty &&
            !PlayableActionQuery.hasPlayableNonManaAction(game, player)
        ) {
            recordDecision(PriorityDecision.Skip(AutoPassReason.SmartPhaseSkip))
            return null
        }

        val profile = phaseStopProfile
        val isOwnTurn = handler.playerTurn?.id == player.id
        // Phase stop check only applies on human's own turn.
        // During opponent's turn, the session layer (advanceOrWait) handles
        // opponent-turn stops separately — engine-side AI_DEFAULTS are for
        // the AI's own combat logic, not for gating human priority.
        if (!fullControl &&
            isOwnTurn &&
            profile != null &&
            !profile.isEnabled(player.id, handler.phase)
        ) {
            recordDecision(PriorityDecision.Skip(AutoPassReason.PhaseNotStopped(handler.phase?.name ?: "UNKNOWN")))
            return null
        }

        // Loop so that mana abilities (which don't use the stack) keep priority
        // with the player instead of immediately passing.  Refs #11
        while (true) {
            notifyStateChanged()

            val state = PendingActionState(
                phase = handler.phase?.name ?: "UNKNOWN",
                turn = handler.turn,
                activePlayerId = handler.playerTurn?.id ?: -1,
                priorityPlayerId = player.id,
            )
            when (val action = ab.awaitAction(state)) {
                is PlayerAction.PassPriority -> return null
                is PlayerAction.EndTurn -> {
                    ab.setAutoPassUntilEndOfTurn(true)
                    return null
                }
                is PlayerAction.CastSpell -> return executeCastSpell(action.cardId, action.abilityId, action.targets)
                is PlayerAction.ActivateAbility -> return executeActivateAbility(action.cardId, action.abilityId, action.targets)
                is PlayerAction.ActivateMana -> {
                    // Mana abilities don't use the stack — player retains priority.
                    // Activate and loop back to await the next action.
                    if (!executeActivateMana(action.cardId)) {
                        log.debug("Mana activation failed for card {}", action.cardId.value)
                    }
                    continue
                }
                is PlayerAction.PlayLand -> return executePlayLand(action.cardId)
                else -> return null
            }
        }
    }

    override fun declareAttackers(attacker: Player, combat: Combat) {
        val ab = actionBridge ?: return super.declareAttackers(attacker, combat)
        log.info("declareAttackers: waiting for {}", attacker.name)

        notifyStateChanged()

        val state = PendingActionState(
            phase = "COMBAT_DECLARE_ATTACKERS",
            turn = game.phaseHandler.turn,
            activePlayerId = attacker.id,
            priorityPlayerId = attacker.id,
        )
        when (val action = ab.awaitAction(state)) {
            is PlayerAction.DeclareAttackers -> {
                val resolvedDefender = resolveAttackDefender(game, attacker, action.defender)
                for (cardId in action.attackerIds) {
                    val card = findCard(cardId) ?: continue
                    if (!CombatUtil.canAttack(card)) continue
                    val defender = resolvedDefender ?: combat.defenders.firstOrNull() ?: continue
                    combat.addAttacker(card, defender)
                }
            }
            is PlayerAction.PassPriority -> {}
            else -> {}
        }
    }

    override fun declareBlockers(defender: Player, combat: Combat) {
        val ab = actionBridge ?: return super.declareBlockers(defender, combat)
        log.info("declareBlockers: waiting for {}", defender.name)

        notifyStateChanged()

        val state = PendingActionState(
            phase = "COMBAT_DECLARE_BLOCKERS",
            turn = game.phaseHandler.turn,
            activePlayerId = defender.id,
            priorityPlayerId = defender.id,
        )
        when (val action = ab.awaitAction(state)) {
            is PlayerAction.DeclareBlockers -> {
                for ((blockerCardId, attackerCardId) in action.blockAssignments) {
                    val blocker = findCard(blockerCardId) ?: continue
                    val attackerCard = findCard(attackerCardId) ?: continue
                    if (combat.isAttacking(attackerCard)) {
                        combat.addBlocker(attackerCard, blocker)
                    }
                }
            }
            is PlayerAction.PassPriority -> {}
            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Static application confirmations
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Auto-decline "assign damage as though unblocked" for trample creatures.
     * The real Arena server never sends this prompt — it always uses
     * AssignDamageReq for manual damage distribution. Forge's desktop UI
     * offers this as a convenience shortcut, but we suppress it to match
     * Arena behavior.
     */
    override fun confirmStaticApplication(
        hostCard: Card,
        mode: PlayerActionConfirmMode,
        message: String,
        logic: String?,
    ): Boolean {
        if (mode == PlayerActionConfirmMode.AlternativeDamageAssignment) {
            log.info("confirmStaticApplication: auto-declining AlternativeDamageAssignment for {}", hostCard.name)
            return false
        }
        return super.confirmStaticApplication(hostCard, mode, message, logic ?: "")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Combat damage assignment
    // ═══════════════════════════════════════════════════════════════════

    override fun assignCombatDamage(
        attacker: Card,
        blockers: CardCollectionView,
        remaining: CardCollectionView?,
        damageDealt: Int,
        defender: GameEntity?,
        overrideOrder: Boolean,
    ): MutableMap<Card?, Int>? {
        // Check cache — CombatHandler may have pre-filled from a batched response
        val cached = damageAssignCache.remove(ForgeCardId(attacker.id))
        if (cached != null) {
            log.info("assignCombatDamage: cache hit for {} (id={})", attacker.name, attacker.id)
            return cached
        }

        // Single blocker + no trample → auto-assign, no UI needed
        val needsManualAssign = blockers.size > 1 ||
            (attacker.hasKeyword(Keyword.TRAMPLE) && defender != null)
        if (!needsManualAssign) {
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder)
        }

        // Clear stale cache entries from a previous damage step
        damageAssignCache.clear()

        log.info(
            "assignCombatDamage: prompting for {} (id={}, damage={}, blockers={})",
            attacker.name,
            attacker.id,
            damageDealt,
            blockers.size,
        )

        // Block the engine thread on a dedicated future. The auto-pass loop
        // detects this via CombatHandler.checkPendingDamageAssignment and
        // sends AssignDamageReq. CombatHandler.onAssignDamage completes the future.
        val future = java.util.concurrent.CompletableFuture<MutableMap<Card?, Int>>()
        pendingDamageAssignment = DamageAssignmentPrompt(
            attacker = attacker,
            blockers = blockers,
            damageDealt = damageDealt,
            defender = defender,
            hasDeathtouch = attacker.hasKeyword(Keyword.DEATHTOUCH),
            hasTrample = attacker.hasKeyword(Keyword.TRAMPLE),
            future = future,
        )
        // Signal priority so awaitPriority() detects us
        actionBridge?.prioritySignal?.signal()

        return try {
            val timeout = actionBridge?.getTimeoutMs() ?: 5_000L
            future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            log.warn("assignCombatDamage: timed out, auto-assigning for {}", attacker.name)
            DevCheck.failOnAutoPass { "assignCombatDamage timed out for ${attacker.name}" }
            super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder)
        } catch (ex: Exception) {
            log.warn("assignCombatDamage: error {}, auto-assigning", ex.message)
            DevCheck.failOnAutoPass { "assignCombatDamage error: ${ex.message}" }
            super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder)
        } finally {
            pendingDamageAssignment = null
            damageAssignCache.clear()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun GameEntity.entityLabel(): String = when (this) {
        is Card -> name
        is Player -> name
        else -> toString()
    }

    private fun buildCandidateRefs(entities: Iterable<GameEntity>): List<PromptCandidateRefDto> =
        entities.mapIndexedNotNull { idx, entity ->
            when (entity) {
                is Card -> PromptCandidateRefDto(idx, "card", entity.id, entity.zone?.zoneType?.name)
                is Player -> PromptCandidateRefDto(idx, "player", entity.id)
                else -> null
            }
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
        reveal: InteractivePromptBridge.ActiveReveal,
    ): CardCollection {
        try {
            val candidateRefs = filteredCards.mapIndexedNotNull { idx, card ->
                (card as? Card)?.let {
                    PromptCandidateRefDto(idx, "card", it.id, it.zone?.zoneType?.name)
                }
            }
            val unfilteredRefs = reveal.allHandCardIds.mapIndexed { idx, forgeCardId ->
                PromptCandidateRefDto(idx, "card", forgeCardId.value)
            }
            val effectiveMin = if (filteredCards.isEmpty()) 0 else min.coerceAtLeast(0)
            val effectiveMax = if (filteredCards.isEmpty()) 0 else max.coerceAtMost(filteredCards.size)
            val labels = filteredCards.map { it.name }
            val request = PromptRequest(
                promptType = "choose_cards",
                message = "Choose a card to discard",
                options = labels,
                min = effectiveMin,
                max = effectiveMax.coerceAtLeast(effectiveMin),
                defaultIndex = 0,
                semantic = PromptSemantic.RevealChoose,
                candidateRefs = candidateRefs,
                unfilteredRefs = unfilteredRefs,
                sourceEntityId = sa?.hostCard?.id,
            )
            val indices = bridge.requestChoice(request)
            val result = CardCollection()
            for (idx in indices) {
                if (idx in 0 until filteredCards.size) {
                    result.add(filteredCards.get(idx) as Card)
                }
            }
            return result
        } finally {
            bridge.activeReveal = null
        }
    }

    /** Common bridge-based card selection for sacrifice/discard/choose/reveal. */
    private fun chooseCardsViaBridge(
        cards: CardCollectionView,
        min: Int,
        max: Int,
        message: String,
    ): CardCollection {
        if (cards.isEmpty()) return CardCollection()
        val effectiveMax = max.coerceAtMost(cards.size)
        val effectiveMin = min.coerceAtLeast(0).coerceAtMost(effectiveMax)
        if (cards.size <= effectiveMin) return CardCollection(cards)
        val labels = cards.map { it.name }
        val request = PromptRequest(
            promptType = "choose_cards",
            message = message,
            options = labels,
            min = effectiveMin,
            max = effectiveMax,
            defaultIndex = 0,
        )
        val indices = bridge.requestChoice(request)
        val result = CardCollection()
        for (idx in indices) {
            if (idx in 0 until cards.size) result.add(cards.get(idx))
        }
        return result
    }

    private fun executeCastSpell(cardId: ForgeCardId, abilityId: Int?, targets: List<Target>): List<SpellAbility>? {
        val card = findCard(cardId) ?: return null
        val candidates = getAllCastableAbilities(card)
        if (candidates.isEmpty()) return null
        val sa = if (abilityId != null && abilityId < candidates.size) {
            candidates[abilityId]
        } else {
            candidates.first()
        }
        applyTargets(sa, targets)
        return listOf(sa)
    }

    private fun executeActivateAbility(cardId: ForgeCardId, abilityId: Int, targets: List<Target>): List<SpellAbility>? {
        val card = findCard(cardId) ?: return null
        val abilities = getNonManaActivatedAbilities(card)
        val sa = abilities.getOrNull(abilityId) ?: return null
        applyTargets(sa, targets)
        return listOf(sa)
    }

    private fun applyTargets(sa: SpellAbility, targets: List<Target>) {
        if (targets.isEmpty() || !sa.usesTargeting()) return
        sa.resetTargets()
        for (t in targets) {
            val obj: GameObject? = resolveTarget(game, t)
            if (obj != null) sa.targets.add(obj)
        }
    }

    /**
     * Activate a mana ability: tap the permanent, choose color if needed, produce mana.
     * Returns true on success.  Refs #11 (color choice), fixes infinite-mana bug.
     */
    private fun executeActivateMana(cardId: ForgeCardId): Boolean {
        val card = findCard(cardId) ?: return false
        val playableAbilities = card.manaAbilities.filter { it.canPlay() }
        if (playableAbilities.isEmpty()) return false
        log.debug("executeActivateMana: {} ({} abilities)", card.name, playableAbilities.size)

        val manaAbility = if (playableAbilities.size == 1) {
            playableAbilities.first()
        } else {
            // Multiple distinct mana abilities — prompt to pick which one
            val labels = playableAbilities.map { ability ->
                ability.manaPart?.origProduced ?: "?"
            }
            val optionsWithCancel = labels + "Cancel"
            val request = PromptRequest(
                promptType = "choose_one",
                message = "Choose mana ability for ${card.name}",
                options = optionsWithCancel,
                min = 1,
                max = 1,
                defaultIndex = 0,
            )
            val indices = bridge.requestChoice(request)
            val idx = indices.firstOrNull() ?: return false
            if (idx >= labels.size) return false // Cancel
            playableAbilities[idx]
        }

        manaAbility.setActivatingPlayer(player)

        // Pay costs via CostPayment (handles tap, sac, exile, etc.) then resolve.
        // For Combo mana (e.g. "W B"), resolve() triggers engine's chooseColor()
        // callback through WebGuiBase → InteractivePromptBridge — one prompt, no duplication.
        val costs = manaAbility.payCosts
        if (costs != null) {
            val payment = CostPayment(costs, manaAbility)
            if (!payment.payComputerCosts(AiCostDecision(player, manaAbility, false))) return false
        }
        try {
            manaAbility.resolve()
        } catch (ex: Exception) {
            log.error("executeActivateMana: resolve() failed for {}: {}", card.name, ex.message, ex)
            return false
        }
        log.debug("executeActivateMana: {} resolved, pool={}", card.name, player.manaPool)
        return true
    }

    private fun executePlayLand(cardId: ForgeCardId): List<SpellAbility>? {
        val card = findCard(cardId) ?: return null
        if (!card.isLand) return null
        val landAbility = LandAbility(card, card.currentState)
        landAbility.activatingPlayer = player
        return listOf(landAbility)
    }

    /** All playable spell abilities including alternative costs (Overload, Flashback, etc.). */
    private fun getAllCastableAbilities(card: Card): List<SpellAbility> =
        getAllCastableAbilities(card, player)

    private fun getNonManaActivatedAbilities(card: Card): List<SpellAbility> =
        getNonManaActivatedAbilities(card, player)

    private fun findCard(cardId: ForgeCardId): Card? = findCard(game, cardId)

    private fun notifyStateChanged() {
        if (onStateChanged != null) {
            try {
                onStateChanged.invoke()
            } catch (ex: Exception) {
                log.debug("State notification failed: ${ex.message}")
            }
        }
    }
}
