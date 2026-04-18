package leyline.bridge

import forge.LobbyPlayer
import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import forge.game.Game
import forge.game.GameEntity
import forge.game.GameObject
import forge.game.ability.AbilityUtils
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.card.CardCollectionView
import forge.game.combat.Combat
import forge.game.cost.Cost
import forge.game.cost.CostPart
import forge.game.keyword.Keyword
import forge.game.keyword.KeywordInterface
import forge.game.player.DelayedReveal
import forge.game.player.PlaySpellAbility
import forge.game.player.Player
import forge.game.player.PlayerActionConfirmMode
import forge.game.player.PlayerController.BinaryChoiceType
import forge.game.replacement.ReplacementEffect
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import forge.player.PlayerControllerHuman
import forge.util.collect.FCollectionView
import org.apache.commons.lang3.tuple.ImmutablePair
import org.slf4j.LoggerFactory

/**
 * The single integration point between Forge's rules engine and our session layer.
 * Extends [PlayerControllerHuman] so all 157 interactive methods route through
 * [InteractivePromptBridge] via [WebGuiGame]; the ~42 methods PCHuman implements
 * with desktop-only classes (InputConfirm, InputSelectCardsFromList, FModel,
 * GuiBase) are overridden here.
 *
 * ## Single-inheritance constraint
 *
 * Forge calls `controller.chooseSpellAbilityToPlay()`, `chooseSingleEntityForEffect(...)`,
 * etc. via a [forge.game.player.PlayerController] field on [forge.game.player.Player].
 * That field holds a concrete subclass; virtual dispatch is the only mechanism.
 * There is no composition escape hatch, no delegation annotation reaching PCHuman's
 * protected state, no handler-map registration. **The class shape is non-negotiable**:
 * every override must stay on this class. The implementation behind each override
 * is not — and should not — live inside the class.
 *
 * Composition replacement is a non-goal. PCHuman does useful work for ~130 methods
 * we do not override; breaking the chain would require re-implementing all of them.
 *
 * ## Coordinators and helpers
 *
 * An override body longer than ~5 lines moves into a **coordinator** (slice of the
 * override surface — multiple related overrides grouped by our concern) or a
 * **helper** (single shared lifecycle or pure-function cluster). Both are plain
 * classes; the override becomes a thin delegation.
 *
 * Current coordinators:
 * - [PriorityLoopCoordinator] — `chooseSpellAbilityToPlay`, combat declarations,
 *   combat-damage assignment, decision logging. Uses [GameActionBridge].
 * - [TargetingCoordinator] — single-entity and multi-entity choice, targeting,
 *   reveals, discards, sacrifices, zone ordering. Writes bridge flag-contract fields.
 * - [CostPaymentCoordinator] — convoke/improvise, keyword-cost binary, optional
 *   costs, shock-land pay-life, AI mana payment.
 *
 * Current helpers:
 * - [SpellExecutor] — the `executeCastSpell` / `executeActivateAbility` /
 *   `executeActivateMana` / `executePlayLand` cluster. Called only from
 *   `chooseSpellAbilityToPlay` inside [PriorityLoopCoordinator].
 * - [OptionalActionGate] — owns the [pendingOptionalAction] future lifecycle
 *   shared by `confirmTrigger`, `playSaFromPlayEffect`, and `payCostToPreventEffect`.
 *
 * ## State ownership
 *
 * Five mutable fields stay on this class because external classes read them
 * through the public field path. Moving any of them into a coordinator would
 * require a forwarding property with zero benefit.
 *
 * - [pendingDamageAssignment] — written by [PriorityLoopCoordinator.promptForCombatDamage];
 *   read by `GameBridge.hasPendingInteraction`, `CombatHandler`, and
 *   [assignCombatDamage] (completed future).
 * - [pendingOptionalAction] — written by [OptionalActionGate.await]; read by
 *   `GameBridge.hasPendingInteraction`, `OptionalActionHandler`, `MatchFlowHarness`.
 * - [damageAssignCache] — written by `CombatHandler.onAssignDamage`; read by
 *   [assignCombatDamage].
 * - [autoPassState] — written via [setAutoPassState] (called by
 *   `MatchSession.connectBridge`); read by [PriorityLoopCoordinator.chooseSpellAbility].
 * - `decisionLog()` / `recentDecisions` — written by [recordDecision]; read by
 *   `DebugServer.servePriorityTrace`.
 *
 * Coordinators read and write these through [OwnerContext]; external callers use
 * the public field path. Prompt side-effects (reveal lifecycle, legend-rule
 * victims, searched-to-hand cards, optional-cost stash) flow through the typed
 * [PromptJournal] on [InteractivePromptBridge]; the priority-loop "prompt just
 * resolved" flag lives on [PrioritySignal].
 *
 * ## Anti-patterns (enforced)
 *
 * - **No coordinator-to-coordinator calls.** Inter-coordinator communication goes
 *   through [OwnerContext] or Forge engine state.
 * - **No reflection or dispatch tables.** Route override → coordinator via direct
 *   Kotlin calls.
 * - **No `suspend` conversion.** `CompletableFuture<Boolean>` is the wire contract
 *   with `MatchSession`.
 * - **No per-mechanic split** (`MadnessOverrides`, `FlashbackOverrides` etc.) —
 *   fractures the surface along the wrong axis.
 * - **No generic `ChoiceHandler<T>` abstraction.** Forge's signatures diverge too
 *   much; coordinators should look like "a class full of related methods."
 * - **No mechanical method-count splitting.** A coordinator's one-sentence purpose
 *   test is the gate.
 * - **No over-extraction.** A 2-method coordinator with no shared helpers is
 *   ceremony, not structure — the confirmation cluster and mulligan overrides
 *   were deliberately left on this class for that reason.
 *
 * ## Adding a new override
 *
 * When Forge adds a callback, or when a mechanic forces us to override a PCHuman
 * method we previously inherited:
 *
 * 1. **Trivial body (≤ 5 lines, direct `bridge.requestChoice` or `super` call)?**
 *    Keep it here. Update [WebPlayerControllerStructureTest] and the override
 *    table in `matchdoor/CLAUDE.md` in the same commit.
 * 2. **Fits an existing coordinator's concern?** Add a method there, delegate.
 * 3. **Shares a lifecycle pattern with other overrides** (e.g. a future dance)?
 *    Extract a shared helper before adding the override — see [OptionalActionGate].
 * 4. **New concern that does not fit any existing coordinator?** Propose a new
 *    coordinator; justify it against the anti-patterns above.
 *
 * The structure test is the guardrail: it fails when the override count drifts,
 * forcing the table and the class to stay in sync.
 *
 * ## Threading
 *
 * Every override runs on the Forge engine thread, synchronously during game-loop
 * execution. Methods that need client input block the engine thread via
 * [InteractivePromptBridge.requestChoice] (`CompletableFuture.get()`); the Netty
 * I/O thread unblocks by completing the future. Consequences for every coordinator:
 *
 * - A missing or slow override blocks the entire game loop.
 * - [PriorityLoopCoordinator.notifyStateChanged] must fire before
 *   [GameActionBridge.awaitAction] so the client sees updated state before being
 *   asked for a decision.
 * - Coordinators must not acquire locks, do I/O, or block on any other thread.
 *
 * See `docs/bridge-threading.md` for the full two-thread contract.
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
) : PlayerControllerHuman(game, player, lobbyPlayer),
    OwnerContext {

    @Volatile
    override var autoPassState: ClientAutoPassState? = autoPassState
        private set

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
    @Volatile override var pendingDamageAssignment: DamageAssignmentPrompt? = null

    /**
     * Pending "you may" trigger decision. Set by [confirmTrigger] when an optional
     * trigger fires (Forge's [WrappedAbility] with [OptionalDecider]).
     * Detected by OptionalActionHandler in the auto-pass loop, which sends
     * [OptionalActionMessage] to the client. Completed when the client responds
     * with [OptionalResp] (Allow_Yes → true, Cancel_No → false).
     *
     * Same dedicated-future pattern as [pendingDamageAssignment].
     */
    @Volatile override var pendingOptionalAction: OptionalActionPrompt? = null

    /** Cache for batched responses — subsequent attackers in Forge's per-attacker loop. */
    override val damageAssignCache: MutableMap<ForgeCardId, MutableMap<Card?, Int>> = mutableMapOf()

    data class DamageAssignmentPrompt(
        val attacker: Card,
        val blockers: CardCollectionView,
        val damageDealt: Int,
        val defender: GameEntity?,
        val hasDeathtouch: Boolean,
        val hasTrample: Boolean,
        val future: java.util.concurrent.CompletableFuture<MutableMap<Card?, Int>>,
    )

    data class OptionalActionPrompt(
        /** Retained for leyline-x25: targeting order fix needs ability details. Null for non-trigger prompts (e.g. shock land ETB). */
        val wrapper: WrappedAbility?,
        val hostCard: Card?,
        val future: java.util.concurrent.CompletableFuture<Boolean>,
        /** When true, force a full state snapshot before emitting the prompt.
         *  Needed for mid-resolution prompts (e.g. Madness's playSaFromPlayEffect)
         *  where the engine hasn't sent the post-replacement state (card in exile)
         *  before blocking on the choice. Without this, the client sees the prompt
         *  before it sees the discard-to-exile transition. */
        val forceSnapshotBeforePrompt: Boolean = false,
    )

    /** Set client auto-pass state (called by MatchSession after bridge connection). */
    fun setAutoPassState(state: ClientAutoPassState) {
        autoPassState = state
    }

    private val optionalActionGate = OptionalActionGate(this, actionBridge)
    private val spellExecutor = SpellExecutor(game, player, bridge)
    private val targetingCoordinator = TargetingCoordinator(bridge)
    private val costPaymentCoordinator = CostPaymentCoordinator(bridge, player, optionalActionGate)
    private val priorityLoopCoordinator: PriorityLoopCoordinator? = actionBridge?.let { ab ->
        PriorityLoopCoordinator(
            owner = this,
            game = game,
            player = player,
            actionBridge = ab,
            phaseStopProfile = phaseStopProfile,
            smartPhaseSkip = smartPhaseSkip,
            spellExecutor = spellExecutor,
        )
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

    override fun recordDecision(decision: PriorityDecision) {
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
        targetingCoordinator.arrangeForScry(topN)

    override fun arrangeForSurveil(topN: CardCollection): ImmutablePair<CardCollection, CardCollection> =
        targetingCoordinator.arrangeForSurveil(topN)

    override fun reveal(
        cards: CardCollectionView,
        zone: ZoneType,
        owner: Player,
        messagePrefix: String?,
        addMsgSuffix: Boolean,
    ) {
        targetingCoordinator.captureReveal(cards, zone, owner)
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
    ): CardCollectionView = targetingCoordinator.choosePermanentsToSacrifice(min, max, validTargets, message)

    override fun choosePermanentsToDestroy(
        sa: SpellAbility?,
        min: Int,
        max: Int,
        validTargets: CardCollectionView,
        message: String?,
    ): CardCollectionView = targetingCoordinator.choosePermanentsToDestroy(min, max, validTargets, message)

    // -- Discard -----------------------------------------------------------
    // PCHuman uses InputSelectCardsFromList

    override fun chooseCardsToDiscardFrom(
        p: Player,
        sa: SpellAbility?,
        validCards: CardCollection,
        min: Int,
        max: Int,
    ): CardCollection = targetingCoordinator.chooseCardsToDiscardFrom(sa, validCards, min, max)

    override fun chooseCardsToDiscardToMaximumHandSize(nDiscard: Int): CardCollection =
        targetingCoordinator.chooseCardsToDiscardToMaximumHandSize(nDiscard, player.getZone(ZoneType.Hand).cards)

    override fun chooseCardsToRevealFromHand(min: Int, max: Int, valid: CardCollectionView): CardCollectionView =
        targetingCoordinator.chooseCardsToRevealFromHand(min, max, valid)

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
    ): CardCollectionView = targetingCoordinator.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional)

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
        return targetingCoordinator.chooseSingleEntity(
            optionList,
            sa,
            title,
            isOptional,
            hasDelayedReveal = delayedReveal != null,
        )
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
        return targetingCoordinator.chooseEntities(optionList, min, max, title)
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
        if (wrapper.isMandatory) return true
        // Route through OptionalActionGate → pendingOptionalAction → OptionalActionMessage
        // (GRE type 45). Auto-accept on timeout is safe: the ability resolves normally.
        return optionalActionGate.await(
            wrapper = wrapper,
            hostCard = wrapper.hostCard,
            defaultOnTimeout = true,
            logContext = "confirmTrigger",
        )
    }

    /**
     * "Do you want to cast this spell that was given to you?" — fires from
     * Forge's PlayEffect for Madness, Discover, Cascade-into-cast, and similar
     * optional-cast paths. The default inherited behavior (PlayerControllerHuman)
     * routes through PlaySpellAbility which bypasses the Arena client entirely;
     * we need to surface the choice as an OptionalActionMessage so the player can
     * Accept or Decline through the normal client UI. On Accept, delegate to
     * `super.playSaFromPlayEffect(tgtSA)` which drives the real cast flow via
     * PlaySpellAbility (targeting, mana payment, stack placement — our alt-cost
     * rail emits CastingTimeOption + UAT alternativeGrpId along the way). On
     * Decline, return false so Forge's PlayEffect SubAbility fires the
     * "otherwise put in graveyard" branch (Exile→GY category=Put via our heuristic).
     *
     * SHORTCUT vs production-client behavior: the client already knows how to
     * render this moment from an `ActionsAvailableReq` with exactly one Cast and
     * one Pass action for the exiled card. Leyline shortcuts via
     * OptionalActionMessage (Take Action / Decline UI) because the existing
     * plumbing handles Accept/Decline uniformly. To align with the client's
     * native rendering path: skip this override entirely, let the trigger
     * resolve as a decline (returns false), and have ActionMapper offer Cast
     * for the exile-resident madness-eligible card during the next priority
     * window. Deferred because it requires broader ActionMapper +
     * priority-flow changes.
     */
    override fun playSaFromPlayEffect(tgtSA: SpellAbility): Boolean {
        val hostCard = tgtSA.hostCard
        log.info(
            "playSaFromPlayEffect: prompting for optional cast of {} (alt-cost={})",
            hostCard?.name,
            tgtSA.getAlternativeCost(),
        )
        // Decline on timeout — safer than surprise-casting. On accept, super drives
        // the real cast flow (targeting, mana payment, stack placement). On decline,
        // Forge's PlayEffect SubAbility fires the "otherwise put in graveyard" branch.
        val accepted = optionalActionGate.await(
            hostCard = hostCard,
            forceSnapshotBeforePrompt = true,
            defaultOnTimeout = false,
            logContext = "playSaFromPlayEffect",
        )
        return if (accepted) super.playSaFromPlayEffect(tgtSA) else false
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
    ): CardCollectionView = targetingCoordinator.orderMoveToZoneList(cards, zone)

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
    ): Map<Card, ManaCostShard> =
        costPaymentCoordinator.chooseCardsForConvokeOrImprovise(manaCost, untappedCards, artifacts, maxReduction)

    // -- Pay cost to prevent effect ----------------------------------------

    override fun payCostToPreventEffect(
        cost: Cost,
        sa: SpellAbility,
        alreadyPaid: Boolean,
        allPayers: FCollectionView<Player>,
    ): Boolean {
        // Shock land (single CostPayLife part) gets the OptionalActionMessage path;
        // everything else (echo, cumulative upkeep) falls through to PCHuman.
        val lifePart = cost.costParts.singleOrNull() as? forge.game.cost.CostPayLife
            ?: return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers)
        return costPaymentCoordinator.payShockLand(lifePart, sa)
    }

    // -- Discard unless type -----------------------------------------------
    // PCHuman uses InputSelectEntitiesFromList (desktop-only, hangs).
    // Bridge as a card selection prompt.  Refs meeting 2026-02-08 Tier 1.

    override fun chooseCardsToDiscardUnlessType(
        min: Int,
        hand: CardCollectionView,
        param: Array<String>,
        sa: SpellAbility,
    ): CardCollectionView = targetingCoordinator.chooseCardsToDiscardUnlessType(min, hand, param, sa)

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
    ): forge.player.TargetSelectionResult =
        targetingCoordinator.selectTargets(validTargets, sa, mandatory, numTargets, divisionValues)

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
    ): Boolean = costPaymentCoordinator.applyManaToCost(toPay, ability, effect)

    override fun chooseCardsForCost(
        optionList: forge.game.card.CardCollectionView,
        sa: SpellAbility,
        cpl: forge.game.cost.CostPartWithList,
        amount: Int,
        isOptional: Boolean,
        prompt: String,
    ): forge.game.card.CardCollectionView {
        val min = if (isOptional) 0 else amount
        return targetingCoordinator.chooseCardsViaBridge(optionList, min, amount, prompt)
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
    ): Int = when {
        max <= 0 -> 0
        max == 1 -> costPaymentCoordinator.chooseKeywordCostBinary(prompt)
        // max > 1: getGui().getInteger() is bridged through WebGuiGame, safe to inherit.
        else -> super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max)
    }

    override fun chooseOptionalCosts(
        chosenSa: SpellAbility,
        optionalCosts: MutableList<forge.game.spellability.OptionalCostValue>,
    ): MutableList<forge.game.spellability.OptionalCostValue> =
        costPaymentCoordinator.chooseOptionalCosts(chosenSa, optionalCosts)

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

        // Apply optional costs (kicker, buyback, etc.) BEFORE playAbility.
        // Can't delegate to PlaySpellAbility.chooseOptionalAdditionalCosts() because
        // it calls getAbilityToPlay() which blocks on InteractivePromptBridge — deadlock
        // since the engine thread is already in this call.
        // Instead, read the stashed decision from TargetingHandler (set after client
        // responded to CastingTimeOptionsReq). Fallback: auto-accept all (test harness).
        var sa = chosenSa
        val optionalCosts = forge.game.GameActionUtil.getOptionalCostValues(sa)
        if (optionalCosts.isNotEmpty()) {
            val chosen = chooseOptionalCosts(sa, optionalCosts)
            sa = forge.game.GameActionUtil.addOptionalCosts(sa, chosen)
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
            isTriggeredAbility = sa.isTrigger,
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
    // Game-loop overrides — delegate to PriorityLoopCoordinator when the
    // action bridge is present. Fall through to PCHuman's default otherwise
    // (tests that construct GameBridge without a session layer).
    // ═══════════════════════════════════════════════════════════════════

    override fun chooseSpellAbilityToPlay(): List<SpellAbility>? {
        val coord = priorityLoopCoordinator ?: return super.chooseSpellAbilityToPlay()
        return coord.chooseSpellAbility()
    }

    override fun declareAttackers(attacker: Player, combat: Combat) {
        val coord = priorityLoopCoordinator ?: return super.declareAttackers(attacker, combat)
        coord.declareAttackers(attacker, combat)
    }

    override fun declareBlockers(defender: Player, combat: Combat) {
        val coord = priorityLoopCoordinator ?: return super.declareBlockers(defender, combat)
        coord.declareBlockers(defender, combat)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Static application confirmations
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Auto-decline "assign damage as though unblocked" for trample creatures.
     * The compatibility flow uses AssignDamageReq for manual damage distribution.
     * Forge's desktop UI offers this as a convenience shortcut, but we suppress it
     * to keep the prompt shape consistent.
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
        return super.confirmStaticApplication(hostCard, mode, message, logic.orEmpty())
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
        // Cache hit: CombatHandler pre-filled this attacker's damage map from a
        // batched client response earlier in the per-attacker loop.
        damageAssignCache.remove(ForgeCardId(attacker.id))?.let { cached ->
            log.info("assignCombatDamage: cache hit for {} (id={})", attacker.name, attacker.id)
            return cached
        }

        // Single blocker, no trample → auto-assign, no UI needed.
        val needsManualAssign = blockers.size > 1 ||
            (attacker.hasKeyword(Keyword.TRAMPLE) && defender != null)
        val fallback: () -> MutableMap<Card?, Int>? = {
            super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder)
        }
        if (!needsManualAssign) return fallback()

        val coord = priorityLoopCoordinator ?: return fallback()
        return coord.promptForCombatDamage(attacker, blockers, damageDealt, defender, fallback)
    }

    override fun notifyStateChanged() {
        if (onStateChanged != null) {
            try {
                onStateChanged.invoke()
            } catch (ex: Exception) {
                log.debug("State notification failed: ${ex.message}")
            }
        }
    }
}
