package leyline.bridge.forge

import forge.LobbyPlayer
import forge.card.ColorSet
import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import forge.game.Game
import forge.game.GameActionUtil
import forge.game.GameEntity
import forge.game.GameObject
import forge.game.ability.AbilityKey
import forge.game.ability.AbilityUtils
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.card.CardCollectionView
import forge.game.card.CardLists
import forge.game.card.CardView
import forge.game.combat.Combat
import forge.game.cost.Cost
import forge.game.cost.CostDecisionMakerBase
import forge.game.cost.CostDiscard
import forge.game.cost.CostEnlist
import forge.game.cost.CostForage
import forge.game.cost.CostPart
import forge.game.cost.CostPartMana
import forge.game.cost.CostPartWithList
import forge.game.cost.CostPayLife
import forge.game.cost.CostReturn
import forge.game.cost.CostSacrifice
import forge.game.cost.CostWaterbend
import forge.game.keyword.Keyword
import forge.game.keyword.KeywordInterface
import forge.game.mana.ManaConversionMatrix
import forge.game.mana.ManaCostBeingPaid
import forge.game.player.DelayedReveal
import forge.game.player.PlaySpellAbility
import forge.game.player.Player
import forge.game.player.PlayerActionConfirmMode
import forge.game.player.PlayerView
import forge.game.replacement.ReplacementEffect
import forge.game.spellability.AbilitySub
import forge.game.spellability.OptionalCostValue
import forge.game.spellability.SpellAbility
import forge.game.staticability.StaticAbility
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import forge.player.PlayerControllerHuman
import forge.player.TargetSelectionResult
import forge.util.collect.FCollectionView
import leyline.bridge.NonInteractiveScope
import leyline.bridge.coord.CostPaymentCoordinator
import leyline.bridge.coord.PriorityLoopCoordinator
import leyline.bridge.coord.SpellExecutor
import leyline.bridge.coord.StaticChoiceCoordinator
import leyline.bridge.coord.TargetingCoordinator
import leyline.bridge.handoff.CommanderReturnPromptContext
import leyline.bridge.handoff.DamageAssignmentPrompt
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.MulliganBridge
import leyline.bridge.handoff.NumericInputGate
import leyline.bridge.handoff.NumericInputPrompt
import leyline.bridge.handoff.OptionalActionGate
import leyline.bridge.handoff.OptionalActionPrompt
import leyline.bridge.handoff.OwnerContext
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PhaseStopProfile
import leyline.bridge.types.PriorityDecision
import leyline.bridge.types.Seating
import leyline.bridge.types.manaTokenToPair
import leyline.bridge.types.toCandidateRefs
import leyline.game.mapping.PromptIds
import org.apache.commons.lang3.tuple.ImmutablePair
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate

/**
 * The single integration point between Forge's rules engine and our session layer.
 * Extends [PlayerControllerHuman] so all 157 interactive methods route through
 * [InteractivePromptBridge] via [ClientGuiGame]; the ~42 methods PCHuman implements
 * with desktop-only classes (InputConfirm, InputSelectCardsFromList, FModel,
 * GuiBase) are overridden here.
 *
 * ## Single-inheritance constraint
 *
 * Forge calls `controller.chooseSpellAbilityToPlay()`, `chooseSingleEntityForEffect(...)`,
 * etc. via a [forge.game.player.PlayerController] field on [Player].
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
 * - [StaticChoiceCoordinator] — static enum selections for color, subtype,
 *   parity-like binary choices, and parity-flavoured confirmations.
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
 * [leyline.bridge.handoff.PromptJournal] on [InteractivePromptBridge]; the priority-loop "prompt just
 * resolved" flag lives on [leyline.bridge.types.PrioritySignal].
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
 *    Keep it here. Update [PlayerControllerStructureTest]'s pinned override
 *    count in the same commit.
 * 2. **Fits an existing coordinator's concern?** Add a method there, delegate.
 * 3. **Shares a lifecycle pattern with other overrides** (e.g. a future dance)?
 *    Extract a shared helper before adding the override — see [OptionalActionGate].
 * 4. **New concern that does not fit any existing coordinator?** Propose a new
 *    coordinator; justify it against the anti-patterns above.
 *
 * The structure test is the guardrail: it fails when the override count drifts
 * from its pinned value, forcing this class and the test to stay in sync.
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
@Suppress("LargeClass") // Forge dispatches via single inheritance; the override surface lives on this class.
class PlayerController(
    game: Game,
    player: Player,
    lobbyPlayer: LobbyPlayer,
    private val bridge: InteractivePromptBridge,
    private val seating: Seating,
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

    /**
     * Pending numeric-input prompt. Set by [NumericInputGate] when Forge calls one of
     * the [chooseNumber] overloads with a `Cost$ X` / `Announce$ X` / similar request.
     * Detected by `NumericInputHandler` in the auto-pass loop, which emits
     * `NumericInputReq` to the client and completes the future on `NumericInputResp`.
     *
     * Same dedicated-future pattern as [pendingOptionalAction] / [pendingDamageAssignment].
     */
    @Volatile override var pendingNumericInput: NumericInputPrompt? = null

    /** Cache for batched responses — subsequent attackers in Forge's per-attacker loop. */
    override val damageAssignCache: MutableMap<ForgeCardId, MutableMap<Card?, Int>> = mutableMapOf()

    /** Set client auto-pass state (called by MatchSession after bridge connection). */
    fun setAutoPassState(state: ClientAutoPassState) {
        autoPassState = state
    }

    private val optionalActionGate = OptionalActionGate(this, actionBridge)
    private val numericInputGate = NumericInputGate(this, actionBridge)
    private val spellExecutor = SpellExecutor(game, player, bridge)
    private val targetingCoordinator = TargetingCoordinator(bridge, seating, currentSourceEntityId = ::currentSourceEntityId)
    private val costPaymentCoordinator = CostPaymentCoordinator(bridge, player, optionalActionGate)
    private val staticChoiceCoordinator = StaticChoiceCoordinator(bridge)
    private var activeSpellSourceId: Int? = null
    private val priorityLoopCoordinator: PriorityLoopCoordinator? =
        actionBridge?.let { ab ->
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
        setGui(
            ClientGuiGame(
                bridge,
                currentStackSourceId = {
                    currentSourceEntityId()
                },
                stackCardRefs = { game.stack.map { it.sourceCard.id to it.sourceCard.name } },
            ),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PlayerController::class.java)
        private const val MAX_DECISIONS = 200
    }

    /** Recent priority decisions for debug observability. */
    private val recentDecisions = ArrayDeque<PriorityDecisionEntry>()

    private var pendingManaColorChoice: Byte? = null

    data class PriorityDecisionEntry(
        val ts: Long,
        val phase: String?,
        val turn: Int,
        val decision: PriorityDecision,
    )

    /** Snapshot of recent decisions for the debug API. */
    fun decisionLog(): List<PriorityDecisionEntry> =
        synchronized(recentDecisions) {
            recentDecisions.toList()
        }

    override fun recordDecision(decision: PriorityDecision) {
        val entry =
            PriorityDecisionEntry(
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

    fun <T> withManaColorChoice(
        colorMask: Byte?,
        block: () -> T,
    ): T {
        if (colorMask == null) return block()
        val previous = pendingManaColorChoice
        pendingManaColorChoice = colorMask
        return try {
            block()
        } finally {
            pendingManaColorChoice = previous
        }
    }

    override fun isAI(): Boolean = false

    // ═══════════════════════════════════════════════════════════════════
    // Overrides for PCHuman methods that use desktop-only classes.
    // Methods using only getGui() calls are inherited and work via ClientGuiGame.
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
    }

    override fun reveal(
        cards: List<CardView>,
        zone: ZoneType,
        owner: PlayerView,
        messagePrefix: String?,
        addMsgSuffix: Boolean,
    ) {
        targetingCoordinator.captureReveal(cards, zone, owner, game.players)
    }

    // -- Sacrifice / Destroy ----------------------------------------------
    // PCHuman uses InputSelectCardsFromList

    override fun choosePermanentsToSacrifice(
        sa: SpellAbility?,
        min: Int,
        max: Int,
        validTargets: CardCollectionView,
        message: String?,
    ): CardCollectionView {
        // Optional sacrifice reductions answer from the active policy.
        // Mandatory sacrifices fall through so the refusal surfaces at the bridge.
        if (min == 0) {
            NonInteractiveScope.active?.let { policy ->
                return NonInteractiveAnswers.permanentsToSacrifice(policy, sa, validTargets)
            }
        }
        return targetingCoordinator.choosePermanentsToSacrifice(sa, min, max, validTargets, message)
    }

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

    override fun chooseCardsToDiscardFrom(
        p: Player,
        sa: SpellAbility?,
        validCards: CardCollection,
        min: Int,
        max: Int,
        visibleToChooser: CardCollectionView,
    ): CardCollectionView = targetingCoordinator.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser)

    override fun chooseCardsToDiscardToMaximumHandSize(nDiscard: Int): CardCollection =
        targetingCoordinator.chooseCardsToDiscardToMaximumHandSize(nDiscard, player.getZone(ZoneType.Hand).cards)

    override fun chooseCardsToRevealFromHand(
        min: Int,
        max: Int,
        valid: CardCollectionView,
    ): CardCollectionView = targetingCoordinator.chooseCardsToRevealFromHand(min, max, valid)

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
        return targetingCoordinator.chooseEntities(optionList, min, max, title, sa)
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
        if (isParadigmCopyCast(sa) || isParadigmCopyCard(cardToShow)) return true

        // Endure: binary mode pick at trigger resolution. Yes → +1/+1 counters
        // (engine adds counters when confirmAction returns true); No → Spirit
        // token (engine creates the token in the else branch). Rides the same
        // OptionalActionMessage gate as confirmTrigger, with a counters-flavoured
        // promptId so the client renders a Yes/No prompt over the source creature.
        if (sa?.api == ApiType.Endure) {
            val hostCard = cardToShow ?: sa.hostCard
            return optionalActionGate.await(
                hostCard = hostCard,
                defaultOnTimeout = true,
                logContext = "confirmAction:Endure",
                customPromptId = PromptIds.ENDURE_PUT_COUNTERS,
            )
        }

        val displayMessage = message ?: "Confirm action?"
        val displayOptions =
            if (options.isNullOrEmpty()) {
                listOf("Yes", "No")
            } else {
                options.toList()
            }
        return staticChoiceCoordinator.confirmAction(displayMessage, displayOptions, (cardToShow ?: sa?.hostCard)?.id)
    }

    override fun confirmTrigger(wrapper: WrappedAbility): Boolean {
        if (wrapper.isMandatory) return true
        if (isParadigmDelayedTrigger(wrapper)) return true
        // Route through OptionalActionGate → pendingOptionalAction → OptionalActionMessage
        // (GRE type 45). Auto-accept on timeout is safe: the ability resolves normally.
        val accepted =
            optionalActionGate.await(
                hostCard = wrapper.hostCard,
                defaultOnTimeout = true,
                logContext = "confirmTrigger",
            )
        if (!accepted) return false

        // Announce X for triggered abilities with `Cost$ X`. Forge's standard
        // X-announce path (`PlaySpellAbility.announceValuesLikeX`) early-exits
        // for wrapped triggered abilities, so X stays unset and the trigger
        // resolves with X=0 unless we set it here. The protocol surface for a
        // "may pay {X}" trigger pairs the optional accept with a follow-up
        // NumericInputReq (ChooseX) — emitted by routing through the gate.
        announceXIfPresent(wrapper)
        return true
    }

    private fun announceXIfPresent(wrapper: WrappedAbility) {
        val cost = wrapper.payCosts ?: return
        if (wrapper.xManaCostPaid != null) return

        // Forge's own X-announce gate (PlaySpellAbility:773) checks
        // `cost.hasXInAnyCostPart()`, but for wrapped triggered SAs that
        // accessor returns false even when `Cost$ X` is set (the wrapper
        // strips the cost into a separate accessor path). So we additionally
        // accept `SVar:X = Count$xPaid` — Forge's own canonical marker for
        // "this ability's X is the amount paid as X mana" — which is set on
        // every `Cost$ X` trigger we've observed (Wildborn Preserver and the
        // mechanic-mirror cards in `forge/forge-gui/res/cardsfolder`). Other
        // SVar:X values (Count$Domain, PT$X, etc.) reference X for some other
        // computation and must not fire a NumericInputReq.
        val sVar = wrapper.getSVar("X")
        val needsX = cost.hasXInAnyCostPart() || sVar == "Count\$xPaid"
        if (!needsX) return

        val maxX = cost.getMaxForNonManaX(wrapper, player, false) ?: Int.MAX_VALUE
        val x =
            numericInputGate.await(
                sourceCard = wrapper.hostCard,
                min = 0,
                max = maxX,
                defaultOnTimeout = 0,
                logContext = "confirmTrigger-X",
            )
        wrapper.setXManaCostPaid(x)
    }

    /**
     * "Do you want to cast this spell that was given to you?" — fires from
     * Forge's PlayEffect for Madness, Discover, Cascade-into-cast, and similar
     * optional-cast paths. The default inherited behavior (PlayerControllerHuman)
     * routes through PlaySpellAbility which bypasses the client entirely;
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
        if (isParadigmCopyCast(tgtSA)) return super.playSaFromPlayEffect(tgtSA)

        val hostCard = tgtSA.hostCard
        log.info(
            "playSaFromPlayEffect: prompting for optional cast of {} (alt-cost={})",
            hostCard?.name,
            tgtSA.getAlternativeCost(),
        )
        // Decline on timeout — safer than surprise-casting. On accept, super drives
        // the real cast flow (targeting, mana payment, stack placement). On decline,
        // Forge's PlayEffect SubAbility fires the "otherwise put in graveyard" branch.
        val accepted =
            optionalActionGate.await(
                hostCard = hostCard,
                forceSnapshotBeforePrompt = true,
                defaultOnTimeout = false,
                logContext = "playSaFromPlayEffect",
            )
        return if (accepted) super.playSaFromPlayEffect(tgtSA) else false
    }

    private fun isParadigmDelayedTrigger(wrapper: WrappedAbility): Boolean =
        wrapper.trigger?.getParam("Execute") == "ParadigmCopy" &&
            wrapper.hostCard?.effectSource?.hasKeyword("Paradigm") == true

    private fun isParadigmCopyCast(sa: SpellAbility?): Boolean =
        sa?.isCastFromPlayEffect == true &&
            sa.hasParam("WithoutManaCost") &&
            isParadigmCopyCard(sa.hostCard)

    private fun isParadigmCopyCard(card: Card?): Boolean = card?.isToken == true && card.copiedPermanent?.hasKeyword("Paradigm") == true

    override fun confirmPayment(
        costPart: CostPart?,
        question: String,
        sa: SpellAbility,
    ): Boolean {
        // PCHuman's version uses InputConfirm (desktop-only). Route through bridge.
        val request =
            PromptRequest(
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
        if (replacementEffect.hasParam("CommanderMoveReplacement")) {
            val hostCard = (affected as? Card) ?: replacementEffect.hostCard
            return optionalActionGate.await(
                hostCard = hostCard,
                forceSnapshotBeforePrompt = true,
                defaultOnTimeout = true,
                logContext = "confirmReplacementEffect:Commander",
                customPromptId = PromptIds.COMMANDER_RETURN_TO_COMMAND,
                commanderReturn = hostCard?.let { commanderReturnContext(it, sa) },
            )
        }

        // PCHuman uses GuiBase + InputConfirm
        val message = prompt ?: replacementEffect.toString()
        val request =
            PromptRequest(
                promptType = "confirm",
                message = message,
                options = listOf("Yes", "No"),
                min = 1,
                max = 1,
                defaultIndex = if (isEnterAsCopyReplacement(message)) 1 else 0,
            )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() == 0
    }

    private fun isEnterAsCopyReplacement(message: String): Boolean = message.contains("enter as a copy", ignoreCase = true)

    @Suppress("UNCHECKED_CAST")
    private fun commanderReturnContext(
        card: Card,
        sa: SpellAbility?,
    ): CommanderReturnPromptContext? {
        val originalParams = sa?.getReplacingObject(AbilityKey.OriginalParams) as? Map<AbilityKey, Any?>
        val origin = originalParams?.get(AbilityKey.Origin) as? ZoneType ?: card.zone?.zoneType ?: ZoneType.Battlefield
        val destination = originalParams?.get(AbilityKey.Destination) as? ZoneType ?: ZoneType.Graveyard
        val oldInstanceId = bridge.forgeIidResolver?.invoke(ForgeCardId(card.id))?.value ?: return null
        val promptInstanceId = bridge.instanceIdReservoir?.invoke()?.value ?: return null
        return CommanderReturnPromptContext(
            oldInstanceId = oldInstanceId,
            promptInstanceId = promptInstanceId,
            originZone = origin,
            destinationZone = destination,
            ownerSeatId = seating.humanSeat.value,
            transferCategory = commanderTransferCategory(origin, destination),
        )
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun commanderTransferCategory(
        origin: ZoneType,
        destination: ZoneType,
    ): String =
        when (destination) {
            ZoneType.Graveyard -> if (origin == ZoneType.Battlefield) "Destroy" else "Put"
            ZoneType.Exile -> "Exile"
            ZoneType.Hand -> "Bounce"
            ZoneType.Library -> "Put"
            else -> "ZoneTransfer"
        }

    override fun chooseBinary(
        sa: SpellAbility?,
        question: String?,
        kindOfChoice: BinaryChoiceType?,
        defaultVal: Boolean?,
    ): Boolean = staticChoiceCoordinator.chooseBinary(sa, question, kindOfChoice, defaultVal)

    override fun chooseColor(
        message: String,
        sa: SpellAbility?,
        colors: ColorSet,
    ): Byte {
        val cntColors = colors.countColors()
        if (cntColors == 0) return 0
        if (cntColors == 1) return colors.color
        pendingManaColorChoice?.let { selectedColor ->
            pendingManaColorChoice = null
            if (colors.orderedColors.any { it.colorMask == selectedColor }) return selectedColor
        }
        return staticChoiceCoordinator.chooseColor(message, sa, colors)
    }

    override fun chooseColors(
        message: String,
        sa: SpellAbility?,
        min: Int,
        max: Int,
        options: ColorSet,
    ): ColorSet = staticChoiceCoordinator.chooseColors(message, sa, min, max, options)

    override fun chooseSomeType(
        kindOfType: String,
        sa: SpellAbility?,
        validTypes: Collection<String>,
        isOptional: Boolean,
    ): String? = staticChoiceCoordinator.chooseSomeType(kindOfType, sa, validTypes, isOptional)

    override fun willPutCardOnTop(c: Card): Boolean {
        // PCHuman uses InputConfirm
        val request =
            PromptRequest(
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
    ): CardCollectionView = targetingCoordinator.orderMoveToZoneList(cards, zone, sa)

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
        NonInteractiveScope.active?.let { policy ->
            return NonInteractiveAnswers.cardsForConvokeOrImprovise(policy, manaCost, untappedCards, artifacts, maxReduction)
        }
        return costPaymentCoordinator.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction)
    }

    override fun chooseCardsToDelve(
        genericAmount: Int,
        grave: CardCollection,
    ): CardCollectionView {
        NonInteractiveScope.active?.let { policy ->
            return NonInteractiveAnswers.cardsToDelve(policy, genericAmount, grave)
        }
        return super.chooseCardsToDelve(genericAmount, grave)
    }

    override fun chooseNumberForCostReduction(
        sa: SpellAbility,
        min: Int,
        max: Int,
    ): Int {
        NonInteractiveScope.active?.let { policy ->
            return NonInteractiveAnswers.numberForCostReduction(policy, min, max)
        }
        return super.chooseNumberForCostReduction(sa, min, max)
    }

    override fun chooseSingleStaticAbility(possibleStatics: List<StaticAbility>): StaticAbility {
        // Reduce/set statics all apply; only the application order is chosen.
        // Non-interactive contexts take them in list order.
        if (NonInteractiveScope.active != null) return possibleStatics.first()
        return super.chooseSingleStaticAbility(possibleStatics)
    }

    override fun choosePlayerToAssistPayment(
        optionList: FCollectionView<Player>,
        sa: SpellAbility,
        title: String?,
        max: Int,
    ): Player? {
        // Assist commits another player's mana; no non-interactive context may
        // assume it.
        if (NonInteractiveScope.active != null) return null
        return super.choosePlayerToAssistPayment(optionList, sa, title, max)
    }

    override fun helpPayForAssistSpell(
        cost: ManaCostBeingPaid,
        sa: SpellAbility,
        max: Int,
        requested: Int,
    ): Boolean {
        if (NonInteractiveScope.active != null) return false
        return super.helpPayForAssistSpell(cost, sa, max, requested)
    }

    // -- Pay cost to prevent effect ----------------------------------------

    override fun payCostToPreventEffect(
        cost: Cost,
        sa: SpellAbility,
        alreadyPaid: Boolean,
        allPayers: FCollectionView<Player>,
    ): Boolean {
        // Single-part costs route to coordinator helpers; everything else
        // (echo, cumulative upkeep, multi-part) falls through to PCHuman.
        cost.costParts.singleOrNull().let { single ->
            if (single is CostPayLife) {
                return costPaymentCoordinator.payShockLand(single, sa)
            }
            if (single is CostPartMana && sa.isKeyword(Keyword.WARD)) {
                return costPaymentCoordinator.payWardManaTax(cost, sa)
            }
        }
        return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers)
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
    // CostDecision routes interactive cost choices through the bridge.
    override fun getCostDecisionMaker(
        player: Player,
        ability: SpellAbility,
        effect: Boolean,
        prompt: String?,
    ): CostDecisionMakerBase =
        CostDecision(
            this,
            player,
            ability,
            effect,
            bridge,
            prompt,
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
        filter: Predicate<GameObject>?,
        mustTargetFiltered: Boolean,
    ): TargetSelectionResult = targetingCoordinator.selectTargets(validTargets, sa, mandatory, numTargets, divisionValues)

    // -- Mana Payment ------------------------------------------------------
    override fun payManaCost(
        toPay: ManaCost,
        costPartMana: CostPartMana,
        sa: SpellAbility,
        prompt: String?,
        matrix: ManaConversionMatrix?,
        effect: Boolean,
    ): Boolean {
        if (costPartMana is CostWaterbend) {
            val untapped =
                CardCollection(
                    player.getCardsIn(ZoneType.Battlefield).filter { card ->
                        !card.isTapped && (card.isArtifact || card.isCreature)
                    },
                )
            val tappedForWaterbend =
                costPaymentCoordinator.chooseCardsForConvokeOrImprovise(
                    sa = sa,
                    manaCost = toPay,
                    untappedCards = untapped,
                    artifacts = true,
                    creatures = true,
                    maxReduction = toPay.genericCost,
                )
            val remaining = ManaCostBeingPaid(toPay)
            for ((card, shard) in tappedForWaterbend) {
                remaining.decreaseShard(shard, 1)
                card.tap(true, sa, player)
            }
            return PlaySpellAbility.payManaCost(this, remaining.toManaCost(), costPartMana, sa, player, prompt, matrix, effect)
        }
        return PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect)
    }

    override fun applyManaToCost(
        toPay: ManaCostBeingPaid,
        ability: SpellAbility,
        prompt: String?,
        matrix: ManaConversionMatrix?,
        effect: Boolean,
    ): Boolean = costPaymentCoordinator.applyManaToCost(toPay, ability, effect)

    override fun chooseCardsForCost(
        optionList: CardCollectionView,
        sa: SpellAbility,
        cpl: CostPartWithList,
        amount: Int,
        isOptional: Boolean,
        prompt: String,
    ): CardCollectionView {
        val semantic =
            when (cpl) {
                is CostDiscard -> PromptSemantic.SelectNDiscard
                is CostReturn ->
                    if (cpl.type.contains("attacking+unblocked") ||
                        cpl.descriptiveType.contains("unblocked attacker", ignoreCase = true)
                    ) {
                        PromptSemantic.ReturnUnblockedAttackerCost
                    } else {
                        PromptSemantic.Generic
                    }
                is CostSacrifice -> PromptSemantic.SelectNCostSacrifice
                is CostEnlist -> PromptSemantic.EnlistCost
                is CostForage ->
                    if (optionList.all { it.isInPlay }) {
                        PromptSemantic.SelectNCostSacrifice
                    } else {
                        PromptSemantic.Generic
                    }
                else -> PromptSemantic.Generic
            }
        val selected =
            targetingCoordinator.chooseCardsViaBridge(
                cards = optionList,
                min = amount,
                max = amount,
                message = prompt,
                semantic = semantic,
                candidateRefs = optionList.toCandidateRefs(),
                sourceEntityId = sa.hostCard.id.takeIf { it > 0 },
                forcePrompt = isOptional,
            )
        if (cpl is CostEnlist && selected.isNotEmpty()) {
            bridge.journal.record(
                PromptSideEffect.EnlistTapAffector(
                    tappedForgeCardId = ForgeCardId(selected.first().id),
                    attackerForgeCardId = ForgeCardId(sa.hostCard.id),
                ),
            )
        }
        return selected
    }

    override fun chooseCardsForCollectEvidence(
        optionList: CardCollectionView,
        sa: SpellAbility,
        total: Int,
        prompt: String,
    ): CardCollectionView {
        bridge.journal.record(PromptSideEffect.CollectEvidenceCost(ForgeCardId(sa.hostCard.id), total))
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = prompt,
                options = optionList.map { it.name },
                min = 0,
                max = optionList.size,
                defaultIndex = 0,
                semantic = PromptSemantic.SelectNCostCollectEvidence,
                candidateRefs = optionList.toCandidateRefs(),
                costSelectionWeights = optionList.map { it.getCMC().coerceAtLeast(0) },
                minSelectionWeight = total,
                sourceEntityId = sa.hostCard.id.takeIf { it > 0 },
            )
        val indices = bridge.requestChoice(request, targetingSa = sa)
        val selected = CardCollection()
        for (index in indices) {
            if (index in 0 until optionList.size) selected.add(optionList[index])
        }
        if (CardLists.getTotalCMC(selected) < total) {
            bridge.journal.clearCollectEvidenceCost()
        }
        return selected
    }

    override fun chooseCardsForRevealCost(
        optionList: CardCollectionView,
        sa: SpellAbility,
        cost: CostPartWithList,
        amount: Int,
        optional: Boolean,
        sameColor: Boolean,
        prompt: String,
    ): CardCollectionView = chooseCardsForCost(optionList, sa, cost, amount, optional, prompt)

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
    ): Int =
        when {
            max <= 0 -> 0
            max == 1 -> costPaymentCoordinator.chooseKeywordCostBinary(prompt, keyword.keyword?.toString())
            // max > 1: getGui().getInteger() is bridged through ClientGuiGame, safe to inherit.
            else -> super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max)
        }

    override fun chooseOptionalCosts(
        chosenSa: SpellAbility,
        optionalCosts: MutableList<OptionalCostValue>,
    ): MutableList<OptionalCostValue> = costPaymentCoordinator.chooseOptionalCosts(chosenSa, optionalCosts)

    // -- Seam 6: chooseNumber (NumericInputReq) ------------------------------
    // PCHuman routes all three overloads through Swing-backed `getGui()` calls
    // (`ClientGuiGame.getInteger` / `.one`), which silently auto-respond in
    // headless mode and don't emit a wire prompt. Override the two range-style
    // overloads to route through the numeric-input gate so the client gets a
    // real `NumericInputReq` (ChooseX). The list-of-values overload is rarer
    // and a different shape; throw to surface the call site if it ever fires.

    override fun chooseNumber(
        sa: SpellAbility,
        title: String,
        min: Int,
        max: Int,
    ): Int {
        // PCHuman short-circuits when the range is degenerate; preserve that
        // invariant so we don't ship a NumericInputReq with maxValue == minValue
        // (or worse, max < min) and wait for a pointless client roundtrip.
        if (min >= max) return min
        return numericInputGate.await(
            sourceCard = sa.hostCard,
            min = min,
            max = max,
            defaultOnTimeout = min,
            logContext = "chooseNumber",
        )
    }

    /**
     * Params overload: currently ignores `params` by design — the no-params
     * overload above is sufficient for every site we've observed. If Forge
     * ever passes a meaningful flag here (e.g. an "is counter amount" hint
     * that should change emission semantics), thread it through
     * `NumericInputGate` via a new optional field rather than silently
     * dropping.
     */
    override fun chooseNumber(
        sa: SpellAbility,
        string: String,
        min: Int,
        max: Int,
        params: MutableMap<String, Any>?,
    ): Int {
        if (min >= max) return min
        return numericInputGate.await(
            sourceCard = sa.hostCard,
            min = min,
            max = max,
            defaultOnTimeout = min,
            logContext = "chooseNumber(params)",
        )
    }

    override fun chooseNumber(
        sa: SpellAbility,
        title: String,
        values: MutableList<Int>,
        relatedPlayer: Player?,
    ): Int =
        error(
            "chooseNumber(sa, title, values, relatedPlayer) not yet implemented for headless bridge — " +
                "list-of-values shape needs a separate emit path (likely SelectN-of-1). See leyline-yt8x. " +
                "sa.hostCard=${sa.hostCard?.name}, values=$values",
        )

    /**
     * Forge's `PCHuman.announceRequirements` only routes through `chooseNumber` when
     * the cost is mandatory; the optional-X branch goes straight to
     * `getGui().getInteger`, which the headless `ClientGuiGame` shim resolves to a
     * silent `0` (the choose_one path never lands a `NumericInputReq` on the wire).
     *
     * Override redirects both branches through `chooseNumber` so the gate fires
     * for "you may pay {X}" triggers (e.g. Wildborn Preserver's ImmediateTrigger).
     */
    override fun announceRequirements(
        ability: SpellAbility,
        min: Int,
        max: Int,
        announce: String,
    ): Int? {
        val host = ability.hostCard
        val cost: Cost? = ability.payCosts
        var effectiveMax = max

        if ("X" == announce && cost != null) {
            val costX = cost.getMaxForNonManaX(ability, player, false)
            val flag = forge.game.player.PlayerController.FullControlFlag.AllowPaymentStartWithMissingResources
            if (costX != null && !player.controller.isFullControl(flag)) {
                effectiveMax = minOf(effectiveMax, costX)
            }
        }

        if (min > effectiveMax) return null

        val announceTitle =
            if ("X" == announce) {
                ability.getParamOrDefault("XAnnounceTitle", announce)
            } else {
                ability.getParamOrDefault("AnnounceTitle", announce)
            }
        val title = "Choose $announceTitle for ${host?.translatedName ?: "spell"}"
        return chooseNumber(ability, title, min, effectiveMax)
    }

    // -- Play spell --------------------------------------------------------
    // PCHuman uses HumanPlay + HumanPlaySpellAbility (desktop Input classes)

    override fun playChosenSpellAbility(chosenSa: SpellAbility): Boolean {
        // Use the upstream PlaySpellAbility path so cost decisions, optional
        // costs, rollback, splice, and mana conversion all stay centralized.
        //
        // Targets may be pre-set on the outer SA when the client's Cast
        // PerformAction carries target ids — SpellExecutor.applyTargets()
        // populates them before this seam runs. When targets are NOT pre-set
        // we pass mayChooseTargets=true so the engine invokes setupTargets(),
        // which walks the SA chain (including post-makeChoices wrapper subs)
        // and routes per-link targeting through selectTargetsInteractively() →
        // InteractivePromptBridge → SelectTargetsReq/Resp.
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
        val optionalCosts =
            GameActionUtil
                .getOptionalCostValues(sa)
                .filterNot { sa.isOptionalCostPaid(it.type) }
                .toMutableList()
        if (optionalCosts.isNotEmpty()) {
            val chosen = chooseOptionalCosts(sa, optionalCosts)
            sa = GameActionUtil.addOptionalCosts(sa, chosen)
        }

        sa.hostCard?.setSplitStateToPlayAbility(sa)

        // Wrapper APIs (Charm, Effect, Repeat, …) often have a non-targeting
        // outer SA whose chosen sub-SAs target. Forge's setupTargets() walks
        // the chain post-makeChoices and only invokes chooseTargetsFor on
        // links where usesTargeting() is true, so passing mayChooseTargets=true
        // is a no-op for genuinely non-targeting chains. The only thing the
        // gate must protect is a pre-set outer-target supplied via the Cast
        // PerformAction — sa.targets.isEmpty() handles that.
        val needsTargeting = sa.targets.isEmpty()
        return withActiveSpellSource(sa) {
            val req = PlaySpellAbility(this, sa)
            req.playAbility(needsTargeting, false, false)
        }
    }

    private fun <T> withActiveSpellSource(
        sa: SpellAbility,
        block: () -> T,
    ): T {
        val previous = activeSpellSourceId
        activeSpellSourceId = sa.hostCard?.id ?: previous
        return try {
            block()
        } finally {
            activeSpellSourceId = previous
        }
    }

    private fun currentSourceEntityId(): Int? =
        activeSpellSourceId
            ?: game
                .stack
                .firstOrNull()
                ?.sourceCard
                ?.id

    override fun playSpellAbilityNoStack(
        effectSA: SpellAbility,
        mayChoseNewTargets: Boolean,
    ) {
        // Direct resolve — this is called by the engine for triggered abilities,
        // replacement effects, and other no-stack effects.
        // Must use AbilityUtils.resolve (not raw effectSA.resolve()) so that
        // chained sub-abilities execute — e.g. CharmEffect chains the chosen
        // mode as a sub, and the sub must resolve after the parent no-op.
        effectSA.activatingPlayer = player
        AbilityUtils.resolve(effectSA)
    }

    override fun chooseModeForAbility(
        sa: SpellAbility,
        possible: MutableList<AbilitySub>,
        min: Int,
        num: Int,
        allowRepeat: Boolean,
    ): List<AbilitySub> {
        if (possible.isEmpty()) return emptyList()

        // Derive structural data the session layer needs to resolve grpIds.
        // PlayerController doesn't import the card-DB layer (bridge → game
        // dependency violation), so we provide:
        //   - possibleFullIndices: where each possible[i] sits in the full
        //     Choices list. Lets TargetingHandler index into card-DB childGrpIds
        //     without re-deriving the filter.
        //   - excludedFullIndices: full-list positions Forge pruned for legality.
        //     Maps to ModalReq.excludedOptions[].
        //   - modalCosts / excludedCosts: per-mode `+ {cost}` parsed from
        //     `getParam("ModeCost")` (empty for cost-free Charm modes).
        // Without this, the CastingTimeOptionsReq's ModalOption list reflects
        // the unfiltered card-DB ordering, which gets out of sync with
        // `possible` when modes are pruned (e.g. Spree's counter mode with no
        // stack target) — response indices then map to the wrong AbilitySub.
        val shape = deriveModalChoiceShape(sa, possible)

        if (!allowRepeat && min == num && num == possible.size && shape.excludedFullIndices.orEmpty().isEmpty()) {
            return possible
        }

        val labels = possible.map { it.description ?: it.toString() }

        val request =
            PromptRequest(
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
                forgeAbilityId = if (sa.isTrigger) sa.id else 0,
                modalChoicePossibleFullIndices = shape.possibleFullIndices,
                modalCosts = shape.modalCosts,
                excludedModalFullIndices = shape.excludedFullIndices,
                excludedModalCosts = shape.excludedCosts,
            )
        val result = bridge.requestChoice(request, targetingSa = sa)
        return result.mapNotNull { idx -> possible.getOrNull(idx) }
    }

    /**
     * For an SP$ Charm SA (Charm, Spree, Tiered), compute possible/excluded mappings
     * into the unfiltered Choices list plus per-mode costs parsed from Forge's
     * `ModeCost$` SVar. Returns `(null, null, null, null)` when:
     *   - the SA has no `getAdditionalAbilityList("Choices")` (not a Charm)
     *   - any element of `possible` isn't in the full list (defensive)
     *
     * In those cases the caller falls back to legacy emit (no modeCost,
     * unfiltered childGrpIds) — preserves existing behavior for non-modal
     * paths and minimizes blast radius.
     */
    private fun deriveModalChoiceShape(
        sa: SpellAbility,
        possible: List<AbilitySub>,
    ): ModalChoiceShape {
        val fullList = sa.getAdditionalAbilityList("Choices") ?: return ModalChoiceShape.EMPTY
        if (fullList.isEmpty()) return ModalChoiceShape.EMPTY

        val possibleSet = possible.toSet()
        val possibleFullIndices = mutableListOf<Int>()
        val modalCosts = mutableListOf<List<Pair<ManaColor, Int>>>()
        val excludedFullIndices = mutableListOf<Int>()
        val excludedCosts = mutableListOf<List<Pair<ManaColor, Int>>>()

        for (sub in possible) {
            val fullIdx = fullList.indexOf(sub)
            // Defensive: shouldn't happen with CharmEffect.makePossibleOptions,
            // but bail to legacy if a mode in `possible` isn't in `fullList`.
            if (fullIdx < 0) return ModalChoiceShape.EMPTY
            possibleFullIndices += fullIdx
            modalCosts += parseForgeModeCost(sub.getParam("ModeCost"))
        }
        for ((idx, sub) in fullList.withIndex()) {
            if (sub in possibleSet) continue
            excludedFullIndices += idx
            excludedCosts += parseForgeModeCost(sub.getParam("ModeCost"))
        }

        return ModalChoiceShape(possibleFullIndices, modalCosts, excludedFullIndices, excludedCosts)
    }

    /**
     * Parse Forge's `ModeCost$` text (e.g. `"0"`, `"3"`, `"2 R"`) into
     * (ManaColor, count) pairs. Tokenizer differs from card-DB OldSchoolManaText
     * (whitespace vs. `o` prefix) but the single-symbol vocabulary is shared via
     * [manaTokenToPair]. Empty/null returns empty list (Charm-style cost-free mode).
     */
    private fun parseForgeModeCost(text: String?): List<Pair<ManaColor, Int>> {
        if (text.isNullOrBlank()) return emptyList()
        val counts = mutableMapOf<ManaColor, Int>()
        for (token in text.trim().split(Regex("\\s+"))) {
            if (token == "0") {
                counts[ManaColor.Generic] = 0
                continue
            }
            val pair = manaTokenToPair(token) ?: continue
            counts.merge(pair.first, pair.second, Int::plus)
        }
        return counts.toList()
    }

    private data class ModalChoiceShape(
        val possibleFullIndices: List<Int>?,
        val modalCosts: List<List<Pair<ManaColor, Int>>>?,
        val excludedFullIndices: List<Int>?,
        val excludedCosts: List<List<Pair<ManaColor, Int>>>?,
    ) {
        companion object {
            val EMPTY = ModalChoiceShape(null, null, null, null)
        }
    }

    // -- Mulligan / starting player ----------------------------------------
    // The engine's MulliganService calls these on the game thread.
    // When a MulliganBridge is wired, they block until the client
    // submits a decision. Without a bridge (tests, AI), they auto-decide.

    override fun mulliganKeepHand(
        mulliganingPlayer: Player,
        cardsToReturn: Int,
    ): Boolean {
        val mb =
            mulliganBridge ?: run {
                log.debug("mulliganKeepHand: no bridge, auto-keep for {}", player.name)
                return true
            }
        return mb.awaitKeepDecision(player.id, cardsToReturn)
    }

    override fun tuckCardsViaMulligan(
        hand: CardCollectionView,
        cardsToReturn: Int,
    ): CardCollectionView {
        if (cardsToReturn <= 0) return CardCollection()
        val mb =
            mulliganBridge ?: run {
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

    override fun declareAttackers(
        attacker: Player,
        combat: Combat,
    ) {
        val coord = priorityLoopCoordinator ?: return super.declareAttackers(attacker, combat)
        coord.declareAttackers(attacker, combat)
    }

    override fun enlistAttackers(attackers: MutableList<Card>): MutableList<Card> {
        val coord = priorityLoopCoordinator ?: return super.enlistAttackers(attackers)
        val selected = coord.enlistAttackers(attackers)
        return CardCollection(selected)
    }

    override fun declareBlockers(
        defender: Player,
        combat: Combat,
    ) {
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
        val needsManualAssign =
            blockers.size > 1 ||
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
