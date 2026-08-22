package leyline.tooling.headless

import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.coord.GameLoopPoller
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.config.AiConfig
import leyline.config.MatchConfig
import leyline.config.ServerConfig
import leyline.game.bundle.AbilityExhaustionFactsCapture
import leyline.game.bundle.InvariantSelection
import leyline.game.bundle.MechanicSourceFactsCapture
import leyline.game.bundle.MessageCounter
import leyline.game.bundle.PersistentFeedFactsCapture
import leyline.game.data.BasicLandAbilities
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleSource
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateProjectionCompiler
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.infra.ListMessageSink
import leyline.match.ConnectionState
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.IdentityHashMap

/**
 * Test harness wrapping real [MatchSession] — zero reimplemented logic.
 *
 * Creates a MatchSession with [ListMessageSink] (paceDelayMs=0).
 * All auto-pass, combat, targeting, game-over flows run through production code.
 *
 * @param validating when true (default), wraps the sink in [ValidatingMessageSink]
 *                   to get automatic invariant checking on every message
 */
@Suppress("LargeClass")
internal class MatchFlowHarness(
    private val seed: Long = 42L,
    private val deckList: String? = null,
    private val opponentDeckList: String? = null,
    validating: Boolean = true,
    private val validation: InvariantSelection = defaultValidation(validating),
    private val validationStrict: Boolean = true,
    private val matchConfig: MatchConfig =
        MatchConfig(
            ai = AiConfig(speed = 0.0),
            // Fail fast in tests. Local gameplay leaves the human bridge
            // timeout disabled; here the engine
            // Candidate projection can traverse a full action set under suite
            // load; this remains short enough to surface a stalled game loop.
            server =
                ServerConfig(
                    bridgeTimeoutMs = 15_000L,
                    aiTurnWaitMs = 2_000L,
                    mulliganWaitMs = 2_000L,
                ),
        ),
    private val variant: String? = null,
    /**
     * Bypass the YAML-fixture-backed [TestCardRegistry] when set. The harness
     * walks the deck list, asks Forge to load each card by name (failing
     * loudly if any card is unknown), and uses the supplied repository for
     * card-identity lookups instead of the fixture-derived in-memory store.
     *
     * Default null preserves the existing fixture-pinned behavior the
     * conformance suite relies on. SimClient passes a SQLite-backed repo so
     * arbitrary decks work without per-card fixtures.
     */
    private val cardRepositoryOverride: CardRepository? = null,
    val responseMode: HeadlessResponseMode = HeadlessResponseMode.AutoForTests,
    private val startupPuzzleText: String? = null,
    private val startupPuzzleResource: String? = null,
    private val startupAiScript: List<ScriptedAction>? = null,
    private val startupSetup: List<MatchSetup> = emptyList(),
) : HeadlessMatch {
    companion object {
        fun defaultValidation(validating: Boolean): InvariantSelection = defaultHeadlessValidation(validating)

        /** Build the concrete adapter behind the semantic headless seam. */
        internal fun fromSpec(
            spec: MatchSpec,
            cardRepositoryOverride: CardRepository? = null,
            matchConfig: MatchConfig =
                MatchConfig(
                    ai = AiConfig(speed = 0.0),
                    server =
                        ServerConfig(
                            bridgeTimeoutMs = 15_000L,
                            aiTurnWaitMs = 2_000L,
                            mulliganWaitMs = 2_000L,
                        ),
                ),
            variant: String? = null,
        ): HeadlessMatch =
            MatchFlowHarness(
                seed = spec.seed,
                deckList = spec.deckList,
                opponentDeckList = spec.opponentDeckList,
                validating = spec.validating,
                validation = spec.validation,
                validationStrict = spec.validationStrict,
                matchConfig =
                    matchConfig.copy(
                        server =
                            matchConfig.server.copy(
                                promptFailsafeMs = spec.promptTimeoutMs ?: matchConfig.server.promptFailsafeMs,
                            ),
                    ),
                variant = variant,
                cardRepositoryOverride = cardRepositoryOverride,
                responseMode = spec.responseMode,
                startupPuzzleText = spec.puzzleText,
                startupPuzzleResource = spec.puzzleResource,
                startupAiScript = spec.aiScript,
                startupSetup = spec.setup,
            )
    }

    private val matchId = "test-match"
    private val seatId = SeatId(1)
    private val opponentSeatId = SeatId(2)

    val registry = MatchRegistry()
    val sink = ListMessageSink()

    /** Validating decorator — null only when the selected validation set is empty. */
    val validatingSink: ValidatingMessageSink? =
        if (!validation.isEmpty()) ValidatingMessageSink(sink, strict = validationStrict, selection = validation) else null

    /** The [MessageSink] passed to [MatchSession] (validating wrapper or plain). */
    private val effectiveSink get() = validatingSink ?: sink

    val accumulator = ClientAccumulator()
    val allMessages = mutableListOf<GREToClientMessage>()
    private val messageLog = MatchFlowMessageLog(allMessages)

    private val combatDriver =
        MatchFlowCombatDriver(
            seatId = seatId,
            bridge = { bridge },
            session = { session },
            messageSnapshot = { messageLog.snapshot() },
            messagesSince = { snapshot -> messageLog.since(snapshot) },
            submitWithGsId = { msg -> submitWithGsId(msg) },
            drainSink = { drainSink() },
        )

    /** All raw messages (SettingsResp, MatchCompleted, etc.) sent via [MessageSink.sendRaw]. */
    val allRawMessages = mutableListOf<MatchServiceToClientMessage>()

    /** When set, the next auto-accepted optional-action prompt declines instead.
     *  Use [declineNextOptionalAction] to set this. Cleared after one use. */
    private var nextOptionalResponse: OptionResponse? = null
    private var holdNextOptionalResponse = false
    private var nextNumericInputValue: Int? = null
    private var lastConsumedPromptMsgIds: List<Int> = emptyList()
    private val appliedSetupCards = mutableSetOf<Pair<MatchSetup, Int>>()

    lateinit var session: MatchSession
        private set
    lateinit var bridge: GameBridge
        private set
    private var lastGame: Game? = null
    private val seatByPlayerId = mutableMapOf<Int, Int>()
    private val cardInstanceIds = IdentityHashMap<Card, Int>()

    /**
     * Bring the match up in whichever mode the caller described, then advance
     * to the first action phase.
     *
     * At most one source may be non-null. All null starts a normal game
     * (mulligan + keep) from the harness's deck lists.
     *
     * @param puzzleText full `.pzl` text (metadata + state)
     * @param puzzleResource classpath resource path, e.g. `puzzles/bolt-face.pzl`
     */
    fun connect(
        puzzleText: String? = null,
        puzzleResource: String? = null,
        aiScript: List<ScriptedAction>? = null,
    ) {
        require(puzzleText == null || puzzleResource == null) {
            "Give at most one puzzle source: inline text or classpath resource"
        }
        when {
            puzzleText != null -> connectAndKeepPuzzleText(puzzleText, aiScript)
            puzzleResource != null -> connectAndKeepPuzzle(puzzleResource, aiScript)
            else -> connectAndKeep(aiScript)
        }
        cacheSeatPlayers()
        applyStartupSetup()
    }

    private fun applyStartupSetup() {
        applySetupToCards(cardViews())
        if (startupSetup.isNotEmpty()) drainSink()
    }

    private fun applySetupToCards(cards: List<HeadlessCard>) {
        startupSetup.forEach { setup ->
            val zone =
                when (setup) {
                    is MatchSetup.AddKeyword -> setup.zone
                    is MatchSetup.AddStaticAbility -> setup.zone
                }
            val matching =
                cards.filter {
                    val cardName =
                        when (setup) {
                            is MatchSetup.AddKeyword -> setup.cardName
                            is MatchSetup.AddStaticAbility -> setup.cardName
                        }
                    it.name == cardName && (zone == SpellZone.Anywhere || it.zone == zone.toZoneType().name)
                }
            if (matching.isEmpty() && zone != SpellZone.Anywhere) {
                error(
                    "Startup setup card not found: ${when (setup) {
                        is MatchSetup.AddKeyword -> setup.cardName
                        is MatchSetup.AddStaticAbility -> setup.cardName
                    }} in $zone",
                )
            }
            matching.forEach { card ->
                if (!appliedSetupCards.add(setup to card.instanceId)) return@forEach
                val forgeId = bridge.getForgeCardId(InstanceId(card.instanceId)) ?: error("Unknown setup card ${card.instanceId}")
                val forgeCard = game().findById(forgeId.value) ?: error("Unknown setup card ${card.instanceId}")
                when (setup) {
                    is MatchSetup.AddKeyword -> forgeCard.addIntrinsicKeyword(setup.keyword.toForgeName())
                    is MatchSetup.AddStaticAbility -> forgeCard.addStaticAbility(setup.ability.toForgeScript())
                }
            }
        }
    }

    private fun SpellZone.toZoneType(): ZoneType =
        when (this) {
            SpellZone.Anywhere -> error("Anywhere is a setup selector, not a Forge zone")
            SpellZone.Battlefield -> ZoneType.Battlefield
            SpellZone.Hand -> ZoneType.Hand
            SpellZone.Graveyard -> ZoneType.Graveyard
            SpellZone.Exile -> ZoneType.Exile
        }

    private fun IntrinsicKeyword.toForgeName(): String =
        when (this) {
            IntrinsicKeyword.FirstStrike -> "First Strike"
            IntrinsicKeyword.DoubleStrike -> "Double Strike"
        }

    private fun StaticAbilitySetup.toForgeScript(): String =
        when (this) {
            StaticAbilitySetup.VehicleCrewPowerWeight ->
                "Mode\$ TapPowerValue | ValidSA\$ Activated.Crew+Vehicle | " +
                    "ValidCard\$ Card.Self | Value\$ Toughness"
        }

    private fun ManaColorChoice.toProtoColor(): ManaColor =
        when (this) {
            ManaColorChoice.White -> ManaColor.White_afc9
            ManaColorChoice.Blue -> ManaColor.Blue_afc9
            ManaColorChoice.Black -> ManaColor.Black_afc9
            ManaColorChoice.Red -> ManaColor.Red_afc9
            ManaColorChoice.Green -> ManaColor.Green_afc9
            ManaColorChoice.Colorless -> ManaColor.Colorless_afc9
            ManaColorChoice.Phyrexian -> ManaColor.Phyrexian_afc9
            ManaColorChoice.Generic -> ManaColor.Generic
            ManaColorChoice.X -> ManaColor.X
            ManaColorChoice.Y -> ManaColor.Y
            ManaColorChoice.TwoGeneric -> ManaColor.TwoGeneric
            ManaColorChoice.AnyColor -> ManaColor.AnyColor
            ManaColorChoice.Snow -> ManaColor.Snow_afc9
        }

    private fun DamageRecipientChoice.toProtoRecipient(): DamageRecipient =
        when (this) {
            DamageRecipientChoice.Opponent ->
                DamageRecipient
                    .newBuilder()
                    .setType(DamageRecType.Player_a0e5)
                    .setPlayerSystemSeatId(opponentSeatId.value)
                    .build()
            is DamageRecipientChoice.Planeswalker ->
                DamageRecipient
                    .newBuilder()
                    .setType(DamageRecType.PlanesWalker)
                    .setPlaneswalkerInstanceId(instanceId)
                    .build()
        }

    private fun StopChange.toProtoStop(): Stop =
        Stop
            .newBuilder()
            .setStopType(StopType.valueOf(phase))
            .setAppliesTo(
                when (scope) {
                    StopScope.Team -> SettingScope.Team_ac6e
                    StopScope.Opponents -> SettingScope.Opponents
                    StopScope.AnyPlayer -> SettingScope.AnyPlayer
                },
            ).setStatus(if (enabled) SettingStatus.Set else SettingStatus.Clear_a3fe)
            .build()

    private fun AutoPassChoice.toProtoOption(): AutoPassOption =
        when (this) {
            AutoPassChoice.ResolveMyStackEffects -> AutoPassOption.ResolveMyStackEffects
        }

    private fun submitSelection(selection: ActionSelection) {
        val expectedType =
            when (selection.kind) {
                ActionKind.Pass -> ActionType.Pass
                ActionKind.Cast -> ActionType.Cast
                ActionKind.Activate -> ActionType.Activate_add3
                ActionKind.ActivateMana -> ActionType.ActivateMana
                ActionKind.PlayLand -> ActionType.Play_add3
                ActionKind.PlayMdfc -> ActionType.PlayMdfc
                ActionKind.CastMdfc -> ActionType.CastMdfc
                ActionKind.CastAdventure -> ActionType.CastAdventure
                ActionKind.CastOmen -> ActionType.CastOmen
                ActionKind.TurnFaceUp -> ActionType.SpecialTurnFaceUp_add3
            }
        val action =
            accumulator.actions
                ?.actionsList
                ?.firstOrNull {
                    it.actionType == expectedType &&
                        (selection.instanceId == 0 || it.instanceId == selection.instanceId) &&
                        (selection.abilityGrpId == 0 || it.abilityGrpId == selection.abilityGrpId) &&
                        (selection.alternativeGrpId == 0 || it.alternativeGrpId == selection.alternativeGrpId)
                } ?: error("No offered ${selection.kind} action for instance ${selection.instanceId}")
        submitAction(action)
    }

    /**
     * Resolve both seat handles while the game is still standing.
     *
     * [human] and [ai] memoize on first read, and `GameBridge.shutdown` clears
     * the seat map, so a spec that first touches a seat after the game has ended
     * — a lethal-damage assertion, a turn-limit loss — would resolve against an
     * empty map. Binding both at connect keeps the handles valid for the whole
     * test regardless of when it reads them.
     */
    private fun cacheSeatPlayers() {
        humanRef = bridge.getPlayer(seatId)
        aiRef = bridge.getPlayer(opponentSeatId)
    }

    /** Start game, keep hand, advance to first real-action phase via MatchSession. */
    fun connectAndKeep(aiScript: List<ScriptedAction>? = null) {
        GameBootstrap.initializeCardDatabase(quiet = true)
        val repo = cardRepositoryForConstructedDecks()

        bridge = newBridge(repo)
        bridge.start(seed = seed, deckList1 = deckList, deckList2 = opponentDeckList, variant = variant)

        createAndRegisterSession()

        // Seed accumulator + validator with a Full GSM BEFORE submitKeep.
        // At this point the engine is blocked at mulligan — safe to call
        // buildFromSnapshot without racing the engine thread's AI action capture.
        // After submitKeep, the engine runs (potentially AI-first) and concurrent
        // buildFromSnapshot calls would race on drainEvents/nextAnnotationId.
        seedInitialFull()

        bridge.submitKeep(seatId)

        session.onMulliganKeep()
        drainSink()

        if (aiScript != null) installScriptedAi(aiScript)
    }

    /** Start puzzle game from classpath resource, advance to first action phase. */
    fun connectAndKeepPuzzle(
        resourcePath: String,
        aiScript: List<ScriptedAction>? = null,
    ) {
        GameBootstrap.initializeCardDatabase(quiet = true)
        startPuzzleBridge(PuzzleSource.loadFromResource(resourcePath), aiScript)
    }

    /**
     * Start puzzle game from inline `.pzl` text, advance to first action phase.
     *
     * Faster than [connectAndKeep]: skips mulligan + turn advancement.
     * Board state is defined declaratively — no multi-turn setup loops.
     *
     * @param aiScript optional scripted actions for the AI — installed before
     *                 auto-pass runs so the AI follows the script on its first turn.
     */
    fun connectAndKeepPuzzleText(
        puzzleText: String,
        aiScript: List<ScriptedAction>? = null,
    ) {
        // Card DB must init before PuzzleSource.loadFromText — the Puzzle
        // constructor triggers GameState.<clinit> which requires localization.
        GameBootstrap.initializeCardDatabase(quiet = true)
        startPuzzleBridge(PuzzleSource.loadFromText(puzzleText), aiScript)
    }

    private fun startPuzzleBridge(
        puzzle: forge.gamemodes.puzzle.Puzzle,
        aiScript: List<ScriptedAction>?,
    ) {
        val repo = cardRepositoryForPuzzle()

        bridge = newBridge(repo)
        bridge.startPuzzle(puzzle, seed = seed) { game ->
            // Fixture identity must exist before the first engine-owned action
            // window freezes its catalog and deferred-cost plan.
            if (cardRepositoryOverride == null) TestCardRegistry.registerPuzzleCards(game)
        }

        // Install scripted AI BEFORE onPuzzleStart — auto-pass will advance
        // through the human turn into the AI turn, where the script takes over.
        if (aiScript != null) {
            installScriptedAi(aiScript)
        }

        createAndRegisterSession()

        seedInitialFull()

        session.onPuzzleStart()
        drainSink()
    }

    private fun cardRepositoryForConstructedDecks(): CardRepository =
        if (cardRepositoryOverride != null) {
            ensureForgeKnowsDeck(deckList)
            ensureForgeKnowsDeck(opponentDeckList)
            cardRepositoryOverride
        } else {
            TestCardRegistry.ensureRegistered()
            if (deckList != null) TestCardRegistry.ensureDeckRegistered(deckList)
            if (opponentDeckList != null) TestCardRegistry.ensureDeckRegistered(opponentDeckList)
            TestCardRegistry.repo
        }

    private fun cardRepositoryForPuzzle(): CardRepository =
        if (cardRepositoryOverride != null) {
            cardRepositoryOverride
        } else {
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.repo
        }

    private val effectiveMatchConfig: MatchConfig = matchConfig

    private fun newBridge(repo: CardRepository): GameBridge =
        GameBridge(
            matchId = matchId,
            bridgeTimeoutMs = effectiveMatchConfig.server.bridgeTimeoutMs,
            promptFailsafeMs = effectiveMatchConfig.server.promptFailsafeMs,
            matchConfig = effectiveMatchConfig,
            messageCounter = MessageCounter(),
            cardRepository = repo,
        ).also { it.priorityWaitMs = 2_000L }

    private fun createAndRegisterSession() {
        session =
            MatchSession(
                connection =
                    ConnectionState(
                        seatId = seatId,
                        matchId = matchId,
                        sink = effectiveSink,
                        registry = registry,
                    ),
                gameBridge = bridge,
                paceDelayMs = 0,
            )
        registry.registerSession(matchId, seatId, session)
    }

    private fun seedInitialFull() {
        val game = bridge.getGame() ?: return
        val priorProjection = bridge.projectionStateSnapshot()
        val (snap, capturedProjection) =
            bridge.editProjection(priorProjection) {
                GsmSnapshot.capture(game, bridge, matchId, 0)
            }
        val events = bridge.closeBundleFrame(seatId.value)
        val promptFacts = bridge.materializePromptProjectionFacts()
        val fullResult =
            StateProjectionCompiler.compileOneViewer(
                environment = bridge.stateProjectionEnvironment,
                input =
                    StateFrameInput(
                        gameStateId = 0,
                        snapshot = snap,
                        previousSnapshot = null,
                        events = events,
                        promptFacts = promptFacts,
                        persistentFeedFacts =
                            PersistentFeedFactsCapture.capture(
                                snap,
                                promptFacts,
                                bridge,
                                bridge.stateProjectionEnvironment,
                            ),
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                        mechanicSourceFacts = MechanicSourceFactsCapture.capture(bridge, events.events),
                        abilityExhaustionFacts = AbilityExhaustionFactsCapture.capture(snap, bridge),
                        updateType = GameStateUpdate.SendAndRecord,
                        viewingSeatId = seatId.value,
                        revealForSeat = null,
                    ),
                prior = capturedProjection.copy(revision = priorProjection.revision),
            )
        bridge.commitProjection(fullResult.transition)
        accumulator.seedFull(fullResult.gsm)
        validatingSink?.seedFull(fullResult.gsm)
    }

    /**
     * Play a land from hand. Returns true if successful.
     *
     * @param name optional preferred land name. When set, plays that named
     *             land if present in hand; otherwise falls back to any land.
     *             Specifying intent here matters: tests that follow up with
     *             a coloured spell cast (e.g. Raging Goblin needs {R}) must
     *             play a same-coloured basic. Without intent, this picks the
     *             first land in hand, which is shuffle-dependent — and shuffle
     *             order shifts with upstream forge edition data because
     *             PaperCard.hashCode is printing-specific. A wrong-coloured
     *             land then makes the follow-up spell uncastable, autoPass
     *             advances through phases unblocked, and the turn counter
     *             skips past T1 before the test asserts.
     */
    fun playLand(name: String? = null): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val handCards = player.getZone(ZoneType.Hand).cards
        val land =
            (if (name != null) handCards.firstOrNull { it.isLand && it.name.equals(name, ignoreCase = true) } else null)
                ?: handCards.firstOrNull { it.isLand }
                ?: return false

        val msg =
            performAction {
                actionType = ActionType.Play_add3
                instanceId = bridge.instanceId(land)
                grpId = bridge.cardRepository.findGrpIdByName(land.name) ?: 0
            }

        session.onPerformAction(submitWithGsId(msg))
        drainSink()
        return true
    }

    /** Cast a creature from hand. Returns true if successful. */
    fun castCreature(): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val creature =
            player
                .getZone(ZoneType.Hand)
                .cards
                .firstOrNull { it.isCreature } ?: return false

        val msg =
            performAction {
                actionType = ActionType.Cast
                instanceId = bridge.instanceId(creature)
                grpId = bridge.cardRepository.findGrpIdByName(creature.name) ?: 0
            }

        session.onPerformAction(submitWithGsId(msg))
        drainSink()
        return true
    }

    /** Activate a battlefield mana ability and drain the resulting state update. */
    fun activateMana(
        cardName: String,
        abilityIndex: Int = 0,
        selectedColor: ManaColor? = null,
    ): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card =
            player
                .getZone(ZoneType.Battlefield)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false
        val ability = getPlayableManaAbilities(card, player).getOrNull(abilityIndex) ?: return false
        val priorProjection = bridge.projectionStateSnapshot()
        val (identityAndOffer, nextProjection) =
            bridge.editProjection(priorProjection) {
                val iid = bridge.instanceId(card)
                val grpId = bridge.resolveGrpId(card, iid)
                val cardData = bridge.cardRepository.findByGrpId(grpId)
                val abilityGrpId =
                    bridge.abilityRegistryFor(card, cardData)?.forSpellAbility(ability)
                        ?: basicLandAbilityGrpId(card)
                val offer =
                    ActionMapper
                        .buildFromSnapshot(seatId.value, GsmSnapshot.capture(game(), bridge, "activateMana", 0), bridge)
                        .actionsList
                        .firstOrNull { action ->
                            action.actionType == ActionType.ActivateMana &&
                                action.instanceId == iid &&
                                action.abilityGrpId == abilityGrpId
                        }
                iid to offer
            }
        val (_, offer) = identityAndOffer
        if (offer == null) return false
        val action =
            if (selectedColor == null) {
                offer
            } else {
                val paymentOption =
                    offer.manaPaymentOptionsList.firstOrNull { option ->
                        option.manaList.any { it.color == selectedColor }
                    } ?: return false
                offer
                    .toBuilder()
                    .clearManaPaymentOptions()
                    .addManaPaymentOptions(paymentOption)
                    .build()
            }
        bridge.commitProjection(leyline.game.state.ProjectionTransition(priorProjection.revision, nextProjection))

        val msg =
            performAction {
                mergeFrom(action)
            }
        session.onPerformAction(submitWithGsId(msg))
        drainSink()
        return true
    }

    private fun basicLandAbilityGrpId(card: Card): Int = BasicLandAbilities.byForgeSubtypeNames(card.type.subtypes) ?: 0

    /** Submit the semantic answer to a native mana-payment prompt. */
    private fun respondToManaPayment(
        sourceInstanceId: Int?,
        repeatedSelectionInstanceIds: List<Int>,
    ) {
        val action =
            performAction {
                actionType = if (sourceInstanceId == null) ActionType.Pass else ActionType.MakePayment
                if (sourceInstanceId != null) instanceId = sourceInstanceId
                repeatedSelectionInstanceIds.forEach { addManaSelections(ManaSelection.newBuilder().setInstanceId(it)) }
            }
        submitAction(action.performActionResp.actionsList.single())
    }

    /** Advance one exact priority or state-only synchronization stop. */
    fun passPriority() {
        val pending = bridge.actionBridge(seatId).getPending()
        if (pending?.state?.kind == leyline.bridge.handoff.PendingActionKind.SYNC_ONLY) {
            session.sendPriorityState(bridge)
            drainSink()
            return
        }
        session.onPerformAction(submitWithGsId(performAction { actionType = ActionType.Pass }))
        drainSink()
    }

    /** Submit an offered action without rebuilding or dropping action fields. */
    fun submitAction(action: Action) {
        session.onPerformAction(submitWithGsId(performAction(action)))
        drainSink()
    }

    /**
     * Advance through whichever default client response is appropriate for
     * the current stop. Priority uses Pass; combat declaration prompts and
     * selection prompts need their own submit messages.
     *
     * The cleanup discard is the case worth naming: holding more than seven
     * cards at end of turn produces a [SelectNReq] and no priority window, so
     * passing priority answers nothing and the turn never ends. A pass loop
     * that ignores it spins to its budget with the game frozen in CLEANUP.
     */
    private fun advanceDefaultStop() {
        val unanswered = unansweredSelectN()
        when {
            unanswered != null -> respondToSelectN(unanswered.ids.take(unanswered.count))
            phase() == "COMBAT_DECLARE_ATTACKERS" -> declareNoAttackers()
            phase() == "COMBAT_DECLARE_BLOCKERS" -> declareNoBlockers()
            else -> passPriority()
        }
    }

    /**
     * The trailing [SelectNReq] when it is still open — nothing the engine sent
     * afterwards implies it was consumed. Returns the minimum legal selection,
     * which for a discard is "exactly as many as the rules demand".
     */
    private fun unansweredSelectN(): PendingSelectN? {
        val pending = pendingSelectN() ?: return null
        return PendingSelectN(ids = pending.instanceIds, count = pending.min)
    }

    /**
     * Return only the latest interaction when no subsequent prompt supersedes it.
     * The result is copied into the immutable observation so consumers never need
     * to inspect message history to decide whether a response is currently legal.
     */
    private fun pendingSelectN(): PendingInteraction.SelectN? {
        val index = allMessages.indexOfLast { it.hasSelectNReq() }
        if (index < 0) return null
        val laterPrompts =
            allMessages.drop(index + 1).any {
                it.hasActionsAvailableReq() || it.hasSelectNReq() || it.hasDeclareAttackersReq() || it.hasDeclareBlockersReq()
            }
        if (laterPrompts) return null
        val message = allMessages[index]
        val req = message.selectNReq
        return PendingInteraction.SelectN(
            messageId = message.msgId,
            instanceIds = req.idsList.map { it.toInt() },
            min = req.minSel.coerceAtLeast(0),
            max = req.maxSel.coerceAtLeast(req.minSel),
        )
    }

    private data class PendingSelectN(
        val ids: List<Int>,
        val count: Int,
    )

    /**
     * Keep passing until [stopWhen] becomes true, the game ends, or [maxPasses] is hit.
     *
     * Returns true when [stopWhen] was observed before the pass budget ran out.
     * Prefer this over fixed `repeat(N) { passPriority() }` loops in integration tests.
     */
    fun passUntil(
        maxPasses: Int = 20,
        stopWhen: MatchFlowHarness.() -> Boolean,
    ): Boolean = advanceUntil(maxPasses) { isGameOver() || stopWhen() }

    private fun advanceUntil(
        maxPasses: Int,
        stopWhen: () -> Boolean,
    ): Boolean {
        repeat(maxPasses) {
            if (stopWhen()) return true
            advanceDefaultStop()
        }
        return stopWhen()
    }

    /**
     * Pass priority until the stack is empty. Use after cast + target to resolve.
     *
     * Always passes at least once: the stack may already be empty before the
     * action's effect lands, because auto-pass can resolve it during
     * `selectTargets` / `drainSink`.
     */
    fun passUntilResolved(maxPasses: Int = 10) {
        repeat(maxPasses) {
            if (isGameOver()) return
            passPriority()
            val retainedSynchronization =
                bridge
                    .actionBridge(seatId)
                    .getPending()
                    ?.state
                    ?.kind == PendingActionKind.SYNC_ONLY
            if (game().stackZone.size() == 0 && !retainedSynchronization) return
        }
    }

    /**
     * Keep passing until a target turn is reached (or game over / max iterations).
     *
     * TODO(multi-turn-overshoot): A single [passPriority] call triggers
     *  [MatchSession.autoPassAndAdvance] which loops up to 50 times, auto-passing
     *  every phase where only Pass is available. This means one call can skip
     *  entire turns — e.g. with seed 42, land + creature + resolve + pass jumps
     *  from turn 1 to turn 3. Tests that need exact turn control should use haste
     *  creatures (turn-1 combat) or assert turn >= N instead of turn == N. A proper
     *  fix would be to add a turn-boundary stop in autoPassAndAdvance so the client
     *  always gets priority at the start of each new turn.
     */
    fun passUntilTurn(
        targetTurn: Int,
        maxPasses: Int = 30,
    ) {
        advanceUntil(maxPasses) { turn() >= targetTurn || isGameOver() }
    }

    /**
     * Pass priority through remaining combat until the turn advances or game ends.
     * Replaces the verbose `repeat(15) { if (gameOver/nextTurn) return@repeat; passPriority() }` pattern.
     */
    fun passThroughCombat(
        startTurn: Int = turn(),
        maxPasses: Int = 15,
    ) {
        advanceUntil(maxPasses) { isGameOver() || turn() > startTurn }
    }

    /**
     * Trigger autoPassAndAdvance directly — without submitting an action first.
     *
     * Use when the engine is already blocked at a combat phase (e.g.
     * COMBAT_DECLARE_BLOCKERS) and you need CombatHandler to send the
     * prompt message. Calling [passPriority] would submit Pass to the
     * combat pending, which is not what you want.
     */
    fun triggerAutoPass() {
        session.triggerAutoPass()
    }

    /**
     * Replace the AI seat's controller with a [ScriptedPlayerController].
     * Call after [connectAndKeep] — the AI player must already exist.
     * Returns the scripted controller for inspection.
     */
    fun installScriptedAi(script: List<ScriptedAction>): ScriptedPlayerController {
        val game = game()
        val aiPlayer =
            bridge.getPlayer(SeatId(2))
                ?: error("No AI player found")
        val controller = ScriptedPlayerController(game, aiPlayer, script)
        // Use highest timestamp so this controller takes priority over the default AI
        aiPlayer.addController(Long.MAX_VALUE, aiPlayer, controller, false)
        return controller
    }

    // --- Phase-precise advancement (bridge-level, no AutoPassEngine) ---

    /**
     * Advance to a specific phase via bridge — one PassPriority at a time.
     * No AutoPassEngine involvement, no phase overshoot.
     */
    fun advanceToPhase(
        phase: String,
        turn: Int? = null,
    ) = leyline.game.advanceToPhase(bridge, phase, turn)

    /** Advance to Main1 via bridge. */
    fun advanceToMain1() = leyline.game.advanceToMain1(bridge)

    /** Advance to COMBAT_DECLARE_ATTACKERS via bridge. */
    fun advanceToCombat(turn: Int? = null) = leyline.game.advanceToCombat(bridge, turn)

    /** Advance to MAIN2 via bridge. */
    fun advanceToMain2(turn: Int? = null) = leyline.game.advanceToMain2(bridge, turn)

    // --- Combat helpers ---

    /** Human's creatures on the battlefield: (instanceId, cardName). */
    fun humanBattlefieldCreatures(): List<Pair<Int, String>> = combatDriver.humanBattlefieldCreatures()

    /**
     * Declare attackers by instanceId using the two-phase Arena protocol:
     * 1. Send [DeclareAttackersResp] with selection (iterative update)
     * 2. Send [SubmitAttackersReq] to finalize (the "Done" button)
     */
    fun declareAttackers(attackerInstanceIds: List<Int>) = combatDriver.declareAttackers(attackerInstanceIds)

    fun declareAttackers(
        attackerInstanceIds: List<Int>,
        damageRecipients: Map<Int, DamageRecipient>,
    ) = combatDriver.declareAttackers(attackerInstanceIds, damageRecipients)

    /** Declare no attackers (skip combat). Sends empty selection then submits. */
    fun declareNoAttackers() = combatDriver.declareNoAttackers()

    /**
     * Send only the iterative DeclareAttackersResp (no Submit) — simulates an Arena
     * attacker-option toggle. Returns messages produced by the echo-back.
     */
    fun toggleAttackers(
        attackerInstanceIds: List<Int>,
        attackerAlternatives: Map<Int, Int> = emptyMap(),
        damageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    ): List<GREToClientMessage> = combatDriver.toggleAttackers(attackerInstanceIds, attackerAlternatives, damageRecipients)

    fun deselectAttackers(attackerInstanceIds: List<Int>): List<GREToClientMessage> = combatDriver.deselectAttackers(attackerInstanceIds)

    /**
     * Send SubmitAttackersReq (type=31, no payload) — the reference client's "Done" button.
     *
     * In the two-phase combat protocol, iterative creature toggles send
     * [DeclareAttackersResp] (type=30) with selection state, while the final
     * confirmation sends [SubmitAttackersReq] (type=31) which is **type-only,
     * no payload**. The server must use the last known selection.
     */
    fun submitAttackers() = combatDriver.submitAttackers()

    /**
     * Send DeclareAttackersResp with auto_declare=true — the "Attack All" button.
     *
     * In Arena, this is the iterative update that selects all qualified attackers
     * targeting the specified damage recipient. Should be followed by [submitAttackers].
     */
    fun declareAllAttackers() = combatDriver.declareAllAttackers()

    /**
     * Declare blockers with assignments using two-phase Arena protocol:
     * 1. Send [DeclareBlockersResp] with assignments (iterative update)
     * 2. Send [SubmitBlockersReq] to finalize
     *
     * Each entry means "this blocker blocks that attacker."
     */
    fun declareBlockers(assignments: Map<Int, Int>) = combatDriver.declareBlockers(assignments)

    /** Declare no blockers (let all attackers through). Sends SubmitBlockersReq directly. */
    fun declareNoBlockers() = combatDriver.declareNoBlockers()

    /**
     * Send only the iterative DeclareBlockersResp (no Submit) — simulates a single
     * blocker assignment click. Returns messages produced by the echo-back.
     */
    fun toggleBlockers(assignments: Map<Int, Int>): List<GREToClientMessage> = combatDriver.toggleBlockers(assignments)

    /**
     * Send DeclareBlockersResp deselecting a single blocker (wire shape: Blocker
     * entry with blockerInstanceId and empty selectedAttackerInstanceIds).
     */
    fun deselectBlocker(blockerInstanceId: Int): List<GREToClientMessage> = combatDriver.deselectBlocker(blockerInstanceId)

    /**
     * Send SubmitBlockersReq (type-only, no payload) — the reference client's "Done" button.
     */
    fun submitBlockers() = combatDriver.submitBlockers()

    // --- Damage assignment helpers ---

    /**
     * Send AssignDamageResp with damage assignments.
     *
     * @param assigners list of (attackerInstanceId, assignments) where assignments
     *                  is a list of (blockerOrDefenderInstanceId, damage)
     */
    fun assignDamage(assigners: List<Pair<Int, List<Pair<Int, Int>>>>) = combatDriver.assignDamage(assigners)

    // --- Targeting helpers ---

    /**
     * Full two-phase target selection: SelectTargetsResp (phase 1) + SubmitTargetsReq (phase 2).
     *
     * Convenience wrapper — sends both messages so existing tests don't need to change.
     * Use [selectTargetsIterative] + [submitTargets] for phase-by-phase control.
     */
    fun selectTargets(
        targetInstanceIds: List<Int>,
        targetIndex: Int = currentTargetIndex(),
    ) {
        val before = allMessages.size
        session.onSelectTargets(submitWithGsId(selectTargetsResp(targets = targetInstanceIds, targetIdx = targetIndex)))
        drainSink()
        // The engine echoes the picked target back as an iterative re-prompt and
        // holds the window open for the submit below. That echo is answered here,
        // not by whoever asked for this call, so record it as consumed — a driver
        // that later treats it as outstanding work would answer a window this
        // method already closed.
        for (i in before until allMessages.size) {
            val msg = allMessages[i]
            if (msg.hasSelectTargetsReq()) consumedPromptMsgIds += msg.msgId
        }
        session.onSubmitTargets(submitWithGsId(submitTargetsReq()))
        drainSink()
    }

    /** Prompt msgIds answered inside a multi-phase responder; drained by the caller. */
    internal fun takeConsumedPromptMsgIds(): List<Int> {
        val taken = consumedPromptMsgIds.toList()
        consumedPromptMsgIds.clear()
        return taken
    }

    /**
     * Phase 1 only: send SelectTargetsResp without SubmitTargetsReq.
     * Use to inspect the echo-back re-prompt before confirming.
     */
    fun selectTargetsIterative(
        targetInstanceIds: List<Int>,
        targetIndex: Int = currentTargetIndex(),
    ) {
        session.onSelectTargets(submitWithGsId(selectTargetsResp(targets = targetInstanceIds, targetIdx = targetIndex)))
        drainSink()
    }

    fun unselectTargets(targetInstanceIds: List<Int>) {
        session.onSelectTargets(submitWithGsId(unselectTargetsResp(targetInstanceIds, currentTargetIndex())))
        drainSink()
    }

    private val consumedPromptMsgIds = mutableSetOf<Int>()

    private fun currentTargetIndex(): Int =
        allMessages
            .lastOrNull { it.hasSelectTargetsReq() }
            ?.selectTargetsReq
            ?.targetsList
            ?.singleOrNull()
            ?.targetIdx
            ?: 1

    /** Phase 2: send SubmitTargetsReq — the client's "Done" button. */
    fun submitTargets() {
        session.onSubmitTargets(submitWithGsId(submitTargetsReq()))
        drainSink()
    }

    /** Cancel a pending targeting action (backs out of spell cast). */
    fun cancelAction() {
        session.onCancelAction(submitWithGsId(cancelActionReq()))
        drainSink()
    }

    /**
     * Respond to a GroupReq (surveil/scry). Places specified instanceIds into the
     * "away" group (graveyard for surveil, bottom for scry). Remaining cards stay on top.
     *
     * @param awayInstanceIds cards to put into the away zone (group 1)
     * @param allInstanceIds all card instanceIds from the GroupReq (for the keep group)
     */
    fun respondToGroupReq(
        awayInstanceIds: List<Int>,
        allInstanceIds: List<Int>,
    ) {
        val keepIds = allInstanceIds.filter { it !in awayInstanceIds }
        val msg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.GroupResp_097b)
                .setGroupResp(
                    GroupResp
                        .newBuilder()
                        .addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(keepIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                                .setSubZoneType(SubZoneType.Top),
                        ).addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(awayInstanceIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Graveyard)
                                .setSubZoneType(SubZoneType.None_a455),
                        ).setGroupType(GroupType.Ordered),
                ).build()
        session.onGroupResp(submitWithGsId(msg))
        drainSink()
    }

    /**
     * Respond to a GroupReq for scry. Places specified instanceIds on the bottom
     * of library. Remaining cards stay on top.
     */
    fun respondToScry(
        bottomInstanceIds: List<Int>,
        allInstanceIds: List<Int>,
    ) {
        val topIds = allInstanceIds.filter { it !in bottomInstanceIds }
        val msg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.GroupResp_097b)
                .setGroupResp(
                    GroupResp
                        .newBuilder()
                        .addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(topIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                                .setSubZoneType(SubZoneType.Top),
                        ).addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(bottomInstanceIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                                .setSubZoneType(SubZoneType.Bottom),
                        ).setGroupType(GroupType.Ordered),
                ).build()
        session.onGroupResp(submitWithGsId(msg))
        drainSink()
    }

    /**
     * Cast a spell by card name from the given [zone] (default: Hand).
     * For flashback/escape, use `zone = ZoneType.Graveyard`.
     * Returns false if card not found in the zone.
     */
    fun castSpellByName(
        cardName: String,
        zone: ZoneType = ZoneType.Hand,
        alternativeGrpId: Int = 0,
    ): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card =
            player
                .getZone(zone)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false

        val msg =
            performAction {
                actionType = ActionType.Cast
                instanceId = bridge.instanceId(card)
                grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                if (alternativeGrpId != 0) this.alternativeGrpId = alternativeGrpId
            }

        session.onPerformAction(submitWithGsId(msg))
        drainSink()
        return true
    }

    /** Alias for `castSpellByName(cardName, ZoneType.Graveyard)`. */
    fun castFromGraveyard(cardName: String): Boolean = castSpellByName(cardName, zone = ZoneType.Graveyard)

    /** Alias for `castSpellByName(cardName, ZoneType.Exile)`. */
    fun castFromExile(cardName: String): Boolean = castSpellByName(cardName, zone = ZoneType.Exile)

    /**
     * Cast a spell and pass once to resolve it.
     *
     * Use only for spells that do not require an interactive client response
     * (no targeting, grouping, modal, or SelectN prompt).
     */
    fun resolveSpell(cardName: String): Boolean {
        if (!castSpellByName(cardName)) return false
        passPriority()
        return true
    }

    /**
     * Cast a spell, run any required follow-up advancement, and return the
     * latest prompt message matching [extract].
     *
     * Keeps flow tests focused on protocol assertions instead of the repeated
     * cast -> advance -> scan message log sequence.
     */
    fun <T> castSpellUntil(
        cardName: String,
        promptName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = {},
        extract: (GREToClientMessage) -> T?,
    ): T {
        check(castSpellByName(cardName)) { "Could not cast $cardName" }
        advanceAfterCast()
        return allMessages.asReversed().firstNotNullOfOrNull(extract)
            ?: error("Expected $promptName after casting $cardName")
    }

    fun castSpellUntilGroupReq(
        cardName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = {
            var found = false
            repeat(8) {
                if (!found) {
                    found =
                        runCatching {
                            GameLoopPoller.awaitCondition(timeoutMs = 500) {
                                drainSink()
                                allMessages.any { it.hasGroupReq() }
                            }
                        }.isSuccess
                    if (!found) passPriority()
                }
            }
        },
    ): GroupReq =
        castSpellUntil(cardName, promptName = "GroupReq", advanceAfterCast = advanceAfterCast) { msg ->
            if (msg.hasGroupReq()) msg.groupReq else null
        }

    fun castSpellUntilSelectNReq(
        cardName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = { passPriority() },
    ): SelectNReq {
        val before = messageSnapshot()
        check(castSpellByName(cardName)) { "Could not cast $cardName" }
        if (messagesSince(before).none { it.hasSelectNReq() }) {
            advanceAfterCast()
        }
        return messagesSince(before).asReversed().firstNotNullOfOrNull { msg ->
            if (msg.hasSelectNReq()) msg.selectNReq else null
        } ?: error("Expected SelectNReq after casting $cardName")
    }

    fun castSpellUntilOrderReq(
        cardName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = { passPriority() },
    ): OrderReq =
        castSpellUntil(cardName, promptName = "OrderReq", advanceAfterCast = advanceAfterCast) { msg ->
            if (msg.hasOrderReq()) msg.orderReq else null
        }

    fun castSpellUntilCastingTimeOptionsReq(
        cardName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = { passPriority() },
    ): CastingTimeOptionsReq =
        castSpellUntil(cardName, promptName = "CastingTimeOptionsReq", advanceAfterCast = advanceAfterCast) { msg ->
            if (msg.hasCastingTimeOptionsReq()) msg.castingTimeOptionsReq else null
        }

    /**
     * Activate a non-mana ability on a battlefield card by name and ability index.
     *
     * @param cardName name of the card on the battlefield
     * @param abilityIndex 0-based index into the card's non-mana activated abilities
     *                     (e.g., planeswalker: 0=first loyalty, 1=second, 2=ultimate)
     * @return true if the card was found and action sent
     */
    fun activateAbility(
        cardName: String,
        abilityIndex: Int = 0,
    ): Boolean = activateAbilityInZone(cardName, ZoneType.Battlefield, abilityIndex)

    /** Activate an ability on a card in the player's hand (Channel, Cycling, etc.). */
    fun activateAbilityFromHand(
        cardName: String,
        abilityIndex: Int = 0,
    ): Boolean = activateAbilityInZone(cardName, ZoneType.Hand, abilityIndex)

    /** Activate an ability on a card in the player's graveyard (Unearth, Embalm, Eternalize). */
    fun activateAbilityFromGraveyard(
        cardName: String,
        abilityIndex: Int = 0,
    ): Boolean = activateAbilityInZone(cardName, ZoneType.Graveyard, abilityIndex)

    private fun activateAbilityInZone(
        cardName: String,
        zone: ZoneType,
        abilityIndex: Int,
    ): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card =
            player
                .getZone(zone)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false
        return submitActivateAction(card, abilityIndex)
    }

    /** Common Activate_add3 submission for both battlefield and hand cards. */
    private fun submitActivateAction(
        card: forge.game.card.Card,
        abilityIndex: Int,
    ): Boolean {
        val iid = bridge.instanceId(card)
        val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: 0
        val cardData = bridge.cardRepository.findByGrpId(grpId)
        val ability = bridge.getPlayer(seatId)?.let { getNonManaActivatedAbilities(card, it).getOrNull(abilityIndex) }
        val abilityGrpId =
            if (cardData != null && ability != null) {
                bridge.abilityRegistryFor(card, cardData)?.forSpellAbility(ability) ?: 0
            } else {
                0
            }

        val msg =
            performAction {
                actionType = ActionType.Activate_add3
                instanceId = iid
                this.grpId = grpId
                this.abilityGrpId = abilityGrpId
            }
        session.onPerformAction(submitWithGsId(msg))
        drainSink()
        return true
    }

    // --- SelectN helpers ---

    /**
     * Respond to a SelectNReq (legend rule, "choose N" prompts) with selected instanceIds.
     *
     * @param selectedInstanceIds the instanceIds the player chose (e.g. the legendary to keep)
     */
    fun respondToSelectN(selectedInstanceIds: List<Int>) {
        session.onSelectN(submitWithGsId(selectNResp(ids = selectedInstanceIds)))
        drainSink()
    }

    fun respondToOrder(orderedInstanceIds: List<Int>) {
        session.onOrderResp(submitWithGsId(orderResp(ids = orderedInstanceIds)))
        drainSink()
    }

    fun respondToSearch(itemsFound: List<Int>) {
        session.onSearch(submitWithGsId(searchResp(itemsFound)))
        drainSink()
    }

    fun respondToEffectCost(selectedInstanceIds: List<Int>) {
        session.onEffectCost(submitWithGsId(effectCostResp(selectedInstanceIds)))
        drainSink()
    }

    fun respondToGatherCounters(gatherings: List<Pair<Int, Int>>) {
        session.onEffectCost(submitWithGsId(gatherCountersResp(gatherings)))
        drainSink()
    }

    // --- Modal helpers ---

    /** Respond to a CastingTimeOptionsReq (modal choice) with selected grpIds. */
    fun respondModalChoice(selectedGrpIds: List<Int>) {
        session.onCastingTimeOptions(submitWithGsId(castingTimeOptionsResp(selectedGrpIds = selectedGrpIds)))
        drainSink()
    }

    // --- Optional cost helpers ---

    /** Respond to a CastingTimeOptionsReq with the given ctoId (kicker, buyback). */
    fun respondToOptionalCost(ctoId: Int) {
        session.onCastingTimeOptions(submitWithGsId(optionalCostResp(ctoId)))
        drainSink()
    }

    /** Respond to a required alternate-additional-cost CastingTimeOptionsReq. */
    fun respondToAlternateCost(
        ctoId: Int,
        optionIndex: Int,
    ) {
        session.onCastingTimeOptions(submitWithGsId(alternateCostResp(ctoId, optionIndex)))
        drainSink()
    }

    fun respondToManaTypeChoices(choicesByCtoId: List<Pair<Int, ManaColor>>) {
        session.onCastingTimeOptions(submitWithGsId(manaTypeResp(choicesByCtoId)))
        drainSink()
    }
    // --- Message inspection ---

    /** Snapshot current message count for later comparison with [messagesSince]. */
    fun messageSnapshot(): Int = messageLog.snapshot()

    /** Get all messages since a snapshot point. */
    fun messagesSince(snapshot: Int): List<GREToClientMessage> = messageLog.since(snapshot)

    /** Get all game-state messages since a snapshot point. */
    fun gameStateMessagesSince(snapshot: Int): List<GameStateMessage> = messageLog.gameStateMessagesSince(snapshot)

    /** Get all annotations from game-state messages since a snapshot point. */
    fun annotationsSince(snapshot: Int): List<AnnotationInfo> = messageLog.annotationsSince(snapshot)

    /** Most recent [SelectNReq] the harness has drained. */
    fun lastSelectNReq(): SelectNReq = allMessages.last { it.hasSelectNReq() }.selectNReq

    /** Most recent [GroupReq] the harness has drained. */
    fun lastGroupReq(): GroupReq = allMessages.last { it.hasGroupReq() }.groupReq

    /** Most recent [CastingTimeOptionsReq] the harness has drained. */
    fun lastCastingTimeOptionsReq(): CastingTimeOptionsReq = allMessages.last { it.hasCastingTimeOptionsReq() }.castingTimeOptionsReq

    // --- State queries ---

    fun phase(): String? = game().phaseHandler.phase?.name

    fun turn(): Int = game().phaseHandler.turn

    fun isAiTurn(): Boolean {
        val human = bridge.getPlayer(seatId) ?: return false
        return game().phaseHandler.playerTurn != human
    }

    fun isGameOver(): Boolean {
        val game = bridge.getGame()
        if (game != null) return game.isGameOver

        if (
            allMessages.any {
                it.hasGameStateMessage() &&
                    it.gameStateMessage.hasGameInfo() &&
                    it.gameStateMessage.gameInfo.stage == GameStage.GameOver
            }
        ) {
            return true
        }

        return allRawMessages.any {
            it.hasMatchGameRoomStateChangedEvent() &&
                it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                MatchGameRoomStateType.MatchCompleted
        }
    }

    fun game(): Game {
        val active = if (::bridge.isInitialized) bridge.getGame() else null
        if (active != null) lastGame = active
        return active ?: lastGame ?: error("Game was not initialised")
    }

    // --- Seat handles ---
    //
    // Resolved once and held. Forge `Player` identity is stable for the whole
    // game, but the bridge clears its seat map when the match closes — and a
    // game that reaches its end during a test still has a readable final board
    // that specs assert on. Caching keeps those assertions working.

    private var humanRef: Player? = null
    private var aiRef: Player? = null

    /** Human player — seat 1. */
    val human: Player get() = humanRef ?: resolveSeat(seatId).also { humanRef = it }

    /** AI / opponent player — seat 2. */
    val ai: Player get() = aiRef ?: resolveSeat(opponentSeatId).also { aiRef = it }

    /** Seat handle if one has been resolved or is still reachable, else null. */
    internal fun seatPlayerOrNull(seat: SeatId): Player? =
        when (seat) {
            seatId -> humanRef
            opponentSeatId -> aiRef
            else -> null
        } ?: if (::bridge.isInitialized) bridge.getPlayer(seat) else null

    private fun resolveSeat(seat: SeatId): Player =
        bridge.getPlayer(seat) ?: error("No player at seat ${seat.value} — call connect() first")

    // --- Instance probe DSL ---

    /** Battlefield zone of [this] player as a probe handle. */
    val Player.battlefield: PlayerZone get() = PlayerZone(this, ZoneType.Battlefield)

    /** Hand zone of [this] player as a probe handle. */
    val Player.hand: PlayerZone get() = PlayerZone(this, ZoneType.Hand)

    /** Graveyard zone of [this] player as a probe handle. */
    val Player.graveyard: PlayerZone get() = PlayerZone(this, ZoneType.Graveyard)

    /** Exile zone of [this] player as a probe handle. */
    val Player.exile: PlayerZone get() = PlayerZone(this, ZoneType.Exile)

    /** Library zone of [this] player as a probe handle. */
    val Player.library: PlayerZone get() = PlayerZone(this, ZoneType.Library)

    /**
     * Resolve a card by name within this (player, zone) handle to its
     * instanceId, so call sites read like a path:
     * `human.battlefield.iid("Walking Corpse")`.
     */
    fun PlayerZone.iid(cardName: String): Int = iidVia(bridge, cardName)

    /**
     * Resolve a [Card] already held by the caller to its instanceId. The zone
     * is informational only — the bridge keys by Forge card id — but keeping
     * the call shape uniform makes probe sites read the same way.
     */
    fun PlayerZone.iid(card: Card): Int = bridge.instanceId(card)

    /** Resolve several cards by name — `human.battlefield.iids("A", "B", "C")`. */
    fun PlayerZone.iids(vararg cardNames: String): List<Int> = cardNames.map { iid(it) }

    /** Find a card in the zone by name. */
    fun PlayerZone.card(name: String): Card = cardIn(player, zone, name)

    /**
     * Resolve a card by name in [player]'s [zone]. Prefer the probe DSL; use
     * this when the zone or player is computed at runtime.
     */
    fun instanceIdOf(
        cardName: String,
        player: Player = human,
        zone: ZoneType = ZoneType.Battlefield,
    ): Int = PlayerZone(player, zone).iidVia(bridge, cardName)

    /**
     * Resolve an instanceId back to its Forge [Card], or null when the mapping
     * is stale (card zoned out or the instanceId was reallocated).
     */
    fun cardByIid(iid: Int): Card? {
        val cardId = bridge.getForgeCardId(InstanceId(iid)) ?: return null
        return game().findById(cardId.value)
    }

    /** Resolve an instanceId to its card name. Fails clearly when unmapped. */
    fun cardName(instanceId: Int): String =
        cardByIid(instanceId)?.name
            ?: error("No card for instanceId $instanceId")

    /** Find the instanceId naming [name] among [candidateIds]. */
    fun findInstanceId(
        candidateIds: List<Int>,
        name: String,
    ): Int =
        candidateIds.firstOrNull { cardName(it) == name }
            ?: error("Card '$name' not found in candidates: $candidateIds")

    /**
     * True when the seat's [GameActionBridge] has a pending action awaiting
     * the client's response. False means the engine isn't blocked on us — any
     * submit we make will trigger
     * `WARN ActionPerformer: PerformActionResp but no pending action` and a
     * spurious state resync. Use as a guard before `session.onPerformAction`
     * in long-running drivers (simclient) where the auto-pass loop frequently
     * advances past priority windows between observe and submit.
     */
    fun hasPendingAction(seat: SeatId = seatId): Boolean = bridge.actionBridge(seat).getPending() != null

    fun shutdown() {
        if (::session.isInitialized) session.close()
        // `connect` can fail before the bridge exists; teardown must not then
        // replace the real failure with an uninitialised-property error.
        if (::bridge.isInitialized) bridge.shutdown()
    }

    // --- Real-client gsId reflection ---
    //
    // A real client reflects the gsId of the latest prompt-bearing GRE it
    // has received on every response it sends. The harness used to leave
    // `gameStateId` at proto default 0, which short-circuited the production
    // staleness check (`clientGsId != 0 && ...`) and meant no testGate test
    // exercised it.
    //
    // [submitWithGsId] fills the field by scanning [allMessages] — the
    // drained record of what the harness has *seen*, mirroring a real
    // client's TCP receive view. Reading from `bridge.messageCounter.
    // lastPromptGsId()` directly would race against the engine thread:
    // the engine can emit a new prompt between the harness's read and the
    // session's processing of the response, leaving the response stamped
    // with an old gsId that the staleness predicate then rejects (observed
    // on CI under load, never reproduces locally because the engine drains
    // synchronously fast enough to hide the race).
    //
    // Tests that need to send an explicit (or stale) gsId can pass a
    // non-zero `gameStateId` on the inbound message; the wrapper leaves
    // those untouched.

    /**
     * gsId of the most recent prompt-bearing GRE the harness has drained.
     * 0 pre-handshake or before any prompt has been received.
     *
     * Walks [allMessages] in reverse — that's the harness's view of what
     * the "client" has seen. Deliberately does not consult
     * `bridge.messageCounter.lastPromptGsId()`: the bridge counter is
     * shared mutable state advanced from the engine thread, so reading it
     * races against in-flight emissions.
     */
    fun latestPromptGsId(): Int = messageLog.latestPromptGsId()

    fun latestPromptMsgId(): Int = messageLog.latestPromptMsgId()

    fun hasPendingSelectNPrompt(): Boolean = bridge.cutCoordinator.cardSelect.current() != null

    /**
     * Reflect the latest prompt ids onto a client response before it enters
     * the session. Explicit non-zero ids remain unchanged so tests can submit
     * stale values deliberately.
     *
     * gsId is stamped from the latest *prompt*, not the latest *state*; the two
     * coincide for iterative prompts (the echo is both). Guards tested through
     * this harness must not assume a real client stamps prompt-fidelity gsIds.
     *
     * `internal` so cross-package drivers in the same module (notably
     * [leyline.tooling.simclient.SimClientDriver]) can route their direct
     * `session.on*` calls through it instead of bypassing reflection.
     */
    internal fun submitWithGsId(msg: ClientToGREMessage): ClientToGREMessage {
        val prompt = messageLog.latestPrompt() ?: return msg
        val builder = msg.toBuilder()
        if (msg.gameStateId == 0) builder.gameStateId = prompt.gameStateId
        if (msg.respId == 0 && msg.type in leyline.match.CORRELATED_CLIENT_MESSAGE_TYPES) {
            builder.respId = prompt.msgId
        }
        return builder.build()
    }

    /**
     * Walk a deck list and ask Forge's static data to load every unique card
     * by name. Required when [cardRepositoryOverride] is in play — without
     * the YAML-fixture path, nothing else routes through
     * [forge.StaticData.attemptToLoadCard], and the engine fails opaquely
     * when a deck contains a card it has never seen.
     *
     * Fails loudly with the offending card name when Forge cannot resolve it,
     * matching the simclient policy: a deck either runs end-to-end or it
     * doesn't run at all.
     *
     * **simclient-only contract.** The simclient tool runs rows serially and
     * Forge's static `MyRandom` is not safe for concurrent games. Do NOT reuse
     * this override path from a parallelised Gradle test task; the lock would
     * still hold but threads would silently serialise on it. If a non-simclient
     * caller needs SQLite-backed card resolution, factor a thread-safe resolver
     * out first.
     */
    private fun ensureForgeKnowsDeck(deckList: String?) {
        val list = deckList ?: return
        val sectionHeader = Regex("""^\[.+]$|^(Deck|Sideboard|Maybeboard|Commander|Companion)\s*$""", RegexOption.IGNORE_CASE)
        val names =
            list
                .trim()
                .lines()
                .filter { it.isNotBlank() }
                .map { it.trim() }
                .filter { !sectionHeader.matches(it) }
                // Strip leading `<count> ` and trailing Arena-export suffix
                // ` (SET) NNN` (e.g. `4 Diregraf Ghoul (FDN) 171` → `Diregraf Ghoul`).
                // Mirrors `DeckLoader.parseDeckList` — without this, the
                // validator looks up `Diregraf Ghoul (FDN) 171` verbatim and
                // every Arena-export deck spuriously fails to resolve.
                .map {
                    it
                        .replaceFirst(Regex("^\\d+\\s+"), "")
                        .replace(Regex("""\s*\([A-Z0-9]+\)\s*\d*\s*$"""), "")
                        .trim()
                }.distinct()
        val db =
            forge.model.FModel
                .getMagicDb()
                ?.commonCards
                ?: error("Forge card DB not initialised — call GameBootstrap.initializeCardDatabase first")
        val missing = mutableListOf<String>()
        synchronized(forge.StaticData.instance()) {
            for (name in names) {
                if (db.getCard(name) != null) continue
                forge.StaticData.instance().attemptToLoadCard(name)
                if (db.getCard(name) == null) missing.add(name)
            }
        }
        check(missing.isEmpty()) {
            "Forge cannot resolve cards in deck list: ${missing.joinToString()}. " +
                "Either the names are mistyped or Forge's card DB does not include them."
        }
    }

    private fun collectSinkMessages() {
        allMessages.addAll(sink.messages)
        allRawMessages.addAll(sink.rawMessages)
        accumulator.processAll(sink.messages)
        sink.clear()
    }

    internal fun drainSink() {
        collectSinkMessages()

        // Setup is applied at an explicit action/progression boundary. Reads
        // remain projections and never mutate Forge state or answer prompts.
        if (startupSetup.isNotEmpty()) applySetupToCards(cardViews())

        if (responseMode != HeadlessResponseMode.AutoForTests) return

        // Auto-respond to engine-initiated prompts so the engine can continue.
        // Loops because chained prompts (e.g. Wildborn Preserver: optional
        // accept → numeric input → potentially another optional on the next
        // resolution step) need every step responded to within one drain.
        // Each helper returns whether it actually fired; loop while any did.
        do {
            val acted = autoRespondToOptionalAction() || autoRespondToNumericInput()
        } while (acted)

        if (startupSetup.isNotEmpty()) applySetupToCards(cardViews())
    }

    private fun autoRespondToOptionalAction(): Boolean {
        bridge.cutCoordinator
            .currentBlockingInteraction()
            ?.takeIf { it.interaction is leyline.bridge.handoff.BlockingInteraction.Optional }
            ?: return false
        val msg = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e } ?: return false
        if (holdNextOptionalResponse) {
            return false
        }

        // If a test pre-seeded a one-shot response via [declineNextOptionalAction],
        // use it and clear the slot. Otherwise default to AllowYes to keep existing
        // tests unblocked.
        val response = nextOptionalResponse ?: OptionResponse.AllowYes
        nextOptionalResponse = null

        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.OptionalActionResp)
                .setGameStateId(msg.gameStateId)
                .setRespId(msg.msgId)
                .setOptionalResp(
                    OptionalResp
                        .newBuilder()
                        .setResponse(response),
                ).build()
        session.onOptionalActionResp(greMsg)

        // Drain follow-up messages without recursing
        collectSinkMessages()
        return true
    }

    /**
     * Pre-seed the next auto-accepted optional-action prompt to be declined instead.
     * One-shot; cleared after the next OptionalActionMessage is auto-responded to.
     * Use when exercising decline branches (e.g. Madness "put in graveyard" path).
     */
    fun declineNextOptionalAction() {
        nextOptionalResponse = OptionResponse.CancelNo
    }

    /** Leave the next OptionalActionMessage pending so the test can call [respondToOptionalAction]. */
    fun holdNextOptionalAction() {
        holdNextOptionalResponse = true
    }

    private fun autoRespondToNumericInput(): Boolean {
        bridge.cutCoordinator
            .currentBlockingInteraction()
            ?.takeIf { it.interaction is leyline.bridge.handoff.BlockingInteraction.Numeric }
            ?: return false
        val msg = allMessages.lastOrNull { it.type == GREMessageType.NumericInputReq_695e } ?: return false

        val value = nextNumericInputValue ?: 0
        nextNumericInputValue = null

        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.NumericInputResp_097b)
                .setGameStateId(msg.gameStateId)
                .setRespId(msg.msgId)
                .setNumericInputResp(
                    NumericInputResp
                        .newBuilder()
                        .setNumericInputValue(value),
                ).build()
        session.onNumericInputResp(greMsg)

        collectSinkMessages()
        return true
    }

    /**
     * Pre-seed the next auto-responded NumericInputReq with [value].
     * One-shot; cleared after the next response. Default (no pre-seed) is `0`.
     */
    fun nextNumericInput(value: Int) {
        nextNumericInputValue = value
    }

    /**
     * Respond to a NumericInputReq with [value] explicitly.
     * For tests that need direct control over the numeric pick.
     */
    fun respondToNumericInput(value: Int) {
        val msg = allMessages.lastOrNull { it.type == GREMessageType.NumericInputReq_695e }
        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.NumericInputResp_097b)
                .setGameStateId(msg?.gameStateId ?: 0)
                .setRespId(msg?.msgId ?: 0)
                .setNumericInputResp(
                    NumericInputResp
                        .newBuilder()
                        .setNumericInputValue(value),
                ).build()
        session.onNumericInputResp(greMsg)
        collectSinkMessages()
    }

    /**
     * Respond to an OptionalActionMessage with Accept or Decline.
     * For tests that need explicit control over the optional decision.
     */
    fun respondToOptionalAction(accept: Boolean) {
        holdNextOptionalResponse = false
        val msg = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.OptionalActionResp)
                .setGameStateId(msg?.gameStateId ?: 0)
                .setRespId(msg?.msgId ?: 0)
                .setOptionalResp(
                    OptionalResp
                        .newBuilder()
                        .setResponse(if (accept) OptionResponse.AllowYes else OptionResponse.CancelNo),
                ).build()
        session.onOptionalActionResp(greMsg)
        collectSinkMessages()
    }

    // --- Semantic headless seam ---

    override fun start(): MatchResult {
        connect(
            puzzleText = startupPuzzleText,
            puzzleResource = startupPuzzleResource,
            aiScript = startupAiScript,
        )
        return result()
    }

    override fun submit(intent: MatchIntent): MatchResult {
        val consumedBefore = consumedPromptMsgIds.toList()
        var accepted = true
        when (intent) {
            is MatchIntent.Play ->
                when (val action = intent.action) {
                    is PlayAction.Land -> accepted = playLand(action.name)
                    is PlayAction.Spell ->
                        accepted =
                            castSpellByName(
                                action.cardName,
                                zone = action.zone.toZoneType(),
                                alternativeGrpId = action.alternativeGrpId ?: 0,
                            )
                    is PlayAction.Ability ->
                        accepted =
                            when (action.zone) {
                                SpellZone.Anywhere -> error("Anywhere is a setup selector, not an ability zone")
                                SpellZone.Battlefield -> activateAbility(action.cardName, action.abilityIndex)
                                SpellZone.Hand -> activateAbilityFromHand(action.cardName, action.abilityIndex)
                                SpellZone.Graveyard -> activateAbilityFromGraveyard(action.cardName, action.abilityIndex)
                                SpellZone.Exile -> error("Ability activation from exile is not supported")
                            }
                    is PlayAction.ManaAbility -> accepted = activateMana(action.cardName, action.abilityIndex, action.color?.toProtoColor())
                    is PlayAction.Selection -> submitSelection(action.action)
                }
            is MatchIntent.Combat ->
                when (val action = intent.action) {
                    is CombatAction.Attackers ->
                        if (action.recipientMode == AttackerRecipientMode.Omit) {
                            combatDriver.declareAttackersWithoutRecipients(action.instanceIds)
                        } else if (action.damageRecipients.isEmpty()) {
                            declareAttackers(action.instanceIds)
                        } else {
                            declareAttackers(action.instanceIds, action.damageRecipients.mapValues { it.value.toProtoRecipient() })
                        }
                    is CombatAction.ToggleAttackers ->
                        toggleAttackers(
                            action.instanceIds,
                            action.alternatives,
                            action.damageRecipients.mapValues { it.value.toProtoRecipient() },
                        )
                    is CombatAction.DeselectAttackers -> combatDriver.deselectAttackers(action.instanceIds)
                    is CombatAction.Blockers -> declareBlockers(action.assignments)
                    is CombatAction.ToggleBlockers -> toggleBlockers(action.assignments)
                    is CombatAction.DeselectBlocker -> deselectBlocker(action.blockerInstanceId)
                    is CombatAction.DamageAssignment -> assignDamage(action.assigners)
                    CombatAction.NoAttackers -> declareNoAttackers()
                    CombatAction.AllAttackers -> declareAllAttackers()
                    CombatAction.SubmitAttackers -> submitAttackers()
                    CombatAction.NoBlockers -> declareNoBlockers()
                    CombatAction.SubmitBlockers -> submitBlockers()
                }
            is MatchIntent.Prompt ->
                when (val response = intent.response) {
                    is PromptResponse.Targets ->
                        if (response.iterative) {
                            selectTargetsIterative(response.instanceIds, response.targetIndex ?: currentTargetIndex())
                        } else {
                            selectTargets(response.instanceIds, response.targetIndex ?: currentTargetIndex())
                        }
                    is PromptResponse.UnselectTargets -> unselectTargets(response.instanceIds)
                    PromptResponse.SubmitTargets -> submitTargets()
                    is PromptResponse.Group -> respondToGroupReq(response.awayInstanceIds, response.allInstanceIds)
                    is PromptResponse.Scry -> respondToScry(response.bottomInstanceIds, response.allInstanceIds)
                    is PromptResponse.SelectN -> respondToSelectN(response.instanceIds)
                    is PromptResponse.Order -> respondToOrder(response.instanceIds)
                    is PromptResponse.Search -> respondToSearch(response.instanceIds)
                    is PromptResponse.EffectCost -> respondToEffectCost(response.instanceIds)
                    is PromptResponse.GatherCounters -> respondToGatherCounters(response.gatherings)
                    is PromptResponse.ModalChoice -> respondModalChoice(response.selectedGrpIds)
                    is PromptResponse.OptionalCost -> respondToOptionalCost(response.ctoId)
                    is PromptResponse.AlternateCost -> respondToAlternateCost(response.ctoId, response.optionIndex)
                    is PromptResponse.ManaTypeChoices ->
                        respondToManaTypeChoices(
                            response.choicesByCtoId.map {
                                it.first to
                                    it.second.toProtoColor()
                            },
                        )
                    is PromptResponse.OptionalAction -> respondToOptionalAction(response.accept)
                    is PromptResponse.NumericInput -> respondToNumericInput(response.value)
                    is PromptResponse.ManaPayment -> respondToManaPayment(response.sourceInstanceId, response.repeatedSelectionInstanceIds)
                    PromptResponse.Cancel -> cancelAction()
                }
            is MatchIntent.Control ->
                when (val action = intent.action) {
                    ControlAction.PassPriority -> passPriority()
                    ControlAction.Concede -> {
                        session.onConcede()
                        drainSink()
                    }
                    ControlAction.HoldNextOptionalAction -> holdNextOptionalAction()
                    ControlAction.DeclineNextOptionalAction -> declineNextOptionalAction()
                    is ControlAction.Stops -> {
                        val msg =
                            clientMessage(ClientMessageType.SetSettingsReq_097b) {
                                setSetSettingsReq(
                                    SetSettingsReq.newBuilder().setSettings(
                                        SettingsMessage.newBuilder().addAllStops(action.changes.map { it.toProtoStop() }),
                                    ),
                                )
                            }
                        session.onSettings(submitWithGsId(msg))
                        drainSink()
                    }
                    is ControlAction.AutoPass -> {
                        session.autoPassState.update(
                            SettingsMessage.newBuilder().setAutoPassOption(action.option.toProtoOption()).build(),
                        )
                    }
                }
        }
        val consumed = consumedPromptMsgIds.toList().filterNot { it in consumedBefore }
        lastConsumedPromptMsgIds = consumed
        return result(accepted = accepted, consumedPromptMsgIds = consumed)
    }

    override fun advance(goal: AdvanceGoal): MatchResult {
        val advanced =
            when (goal) {
                is AdvanceGoal.Until -> advanceUntilProgress(goal.maxPasses)
                is AdvanceGoal.UntilTurn -> {
                    passUntilTurn(goal.turn, goal.maxPasses)
                    true
                }
                is AdvanceGoal.ThroughCombat -> {
                    passThroughCombat(goal.startTurn ?: turn(), goal.maxPasses)
                    true
                }
                is AdvanceGoal.Resolved -> {
                    passUntilResolved(goal.maxPasses)
                    true
                }
                is AdvanceGoal.Phase -> {
                    advanceSemanticallyTo(goal.name, goal.turn)
                    true
                }
                AdvanceGoal.Main1 -> {
                    advanceSemanticallyTo("MAIN1")
                    true
                }
                is AdvanceGoal.Combat -> {
                    advanceSemanticallyTo("COMBAT_DECLARE_ATTACKERS", goal.turn)
                    true
                }
                is AdvanceGoal.Main2 -> {
                    advanceSemanticallyTo("MAIN2", goal.turn)
                    true
                }
                AdvanceGoal.TriggerAutoPass -> {
                    triggerAutoPass()
                    true
                }
            }
        drainSink()
        return result(accepted = advanced)
    }

    private fun advanceUntilProgress(maxPasses: Int): Boolean {
        require(maxPasses > 0) { "Until requires a positive pass budget" }
        val beforeMessages = allMessages.size
        val beforeTurn = runCatching { turn() }.getOrNull()
        val beforePhase = runCatching { phase() }.getOrNull()

        fun progressed(): Boolean =
            allMessages.size > beforeMessages ||
                runCatching { turn() }.getOrNull() != beforeTurn ||
                runCatching { phase() }.getOrNull() != beforePhase
        repeat(maxPasses) {
            if (progressed() || isGameOver()) return progressed()
            advanceDefaultStop()
        }
        return progressed()
    }

    private fun advanceSemanticallyTo(
        targetPhase: String,
        targetTurn: Int? = null,
        maxPasses: Int = 50,
    ) {
        repeat(maxPasses) {
            if (phase() == targetPhase && (targetTurn == null || turn() == targetTurn)) return
            if (isGameOver()) return
            passPriority()
        }
        check(phase() == targetPhase && (targetTurn == null || turn() == targetTurn)) {
            "Could not advance to $targetPhase${targetTurn?.let { " on turn $it" } ?: ""}"
        }
    }

    override fun observe(): MatchObservation = observation()

    override fun checkpoint(): MatchCheckpoint = MatchCheckpoint(messageLog.snapshot())

    override fun messagesSince(checkpoint: MatchCheckpoint): List<GREToClientMessage> = messageLog.since(checkpoint.index)

    fun diagnostics(
        label: String,
        messageTail: Int = 15,
    ): String = renderDiagnostics(label, messageTail)

    override fun advise(
        prompt: GREToClientMessage,
        mode: HeadlessAdviceMode,
    ) = when (mode) {
        HeadlessAdviceMode.Live -> HeadlessMatchRuntime.liveConsult(this, prompt, seatId)
        HeadlessAdviceMode.Snapshot -> HeadlessMatchRuntime.snapshotConsult(this, prompt, seatId)
    }

    override fun query(query: MatchQuery): MatchQueryResult = HeadlessMatchRuntime.query(this, query)

    override fun close() = shutdown()

    private fun cardRepositoryByRuntime(grpId: Int): String? = bridge.cardRepository.findNameByGrpId(grpId)

    private fun result(
        accepted: Boolean = true,
        consumedPromptMsgIds: List<Int> = emptyList(),
    ): MatchResult = MatchResult(accepted = accepted, observation = observation(), consumedPromptMsgIds = consumedPromptMsgIds)

    private fun observation(): MatchObservation =
        MatchObservation(
            messages = allMessages.toList(),
            rawMessages = allRawMessages.toList(),
            client = accumulator.snapshot(),
            phase = runCatching { phase() }.getOrNull(),
            turn = runCatching { turn() }.getOrNull(),
            aiTurn = runCatching { isAiTurn() }.getOrDefault(false),
            gameOver = runCatching { isGameOver() }.getOrDefault(false),
            latestPromptGsId = messageLog.latestPromptGsId(),
            latestPromptMsgId = messageLog.latestPromptMsgId(),
            cards = cardViews(),
            stackSize = runCatching { game().stackZone.size() }.getOrNull(),
            stackObjectsSize = runCatching { game().stack.size() }.getOrNull(),
            pendingAction = runCatching { hasPendingAction() }.getOrDefault(false),
            pendingActionKind =
                runCatching {
                    bridge
                        .actionBridge(seatId)
                        .getPending()
                        ?.state
                        ?.kind
                        ?.name
                }.getOrNull(),
            pendingSynchronization =
                runCatching {
                    bridge
                        .actionBridge(seatId)
                        .getPending()
                        ?.state
                        ?.kind == PendingActionKind.SYNC_ONLY ||
                        bridge
                            .cutCoordinator
                            .hasCommittedBatches(seatId)
                }.getOrDefault(false),
            blockingInteraction =
                runCatching {
                    bridge.cutCoordinator
                        .currentBlockingInteraction()
                        ?.interaction
                        ?.javaClass
                        ?.simpleName
                }.getOrNull(),
            blockingInteractionId =
                runCatching {
                    bridge.cutCoordinator.targeting
                        .current()
                        ?.interactionId
                }.getOrNull(),
            pendingInteraction = runCatching { pendingSelectN() }.getOrNull(),
            activeRevealVersion =
                runCatching {
                    bridge
                        .promptBridge(seatId)
                        .journal
                        .activeRevealEntry()
                        ?.version
                }.getOrNull(),
            pendingRevealInteractionId =
                runCatching {
                    bridge.cutCoordinator.revealChoices
                        .current()
                        ?.interactionId
                }.getOrNull(),
            loopFailure =
                runCatching {
                    bridge.throwIfGameLoopFailed()
                    null
                }.getOrElse { it.message ?: it::class.simpleName },
            pendingCostSelection = runCatching { bridge.cutCoordinator.oneShotPayCosts.current() != null }.getOrDefault(false),
            validationViolations = validatingSink?.violations?.toList().orEmpty(),
            validationViolationsByCheck = validatingSink?.violationsByCheck?.toMap().orEmpty(),
            promptHistory = runCatching { bridge.promptBridge(seatId).history.toList() }.getOrDefault(emptyList()),
            consumedPromptMsgIds = lastConsumedPromptMsgIds,
            enabledStops =
                runCatching {
                    bridge.getPlayer(seatId)?.let { player ->
                        bridge.phaseStopProfile
                            ?.getEnabled(player.id)
                            ?.map { it.name }
                            ?.toSet()
                            .orEmpty()
                    }
                }.getOrNull().orEmpty(),
            poisonCountersBySeat =
                runCatching {
                    game()
                        .registeredPlayers
                        .associate { player ->
                            (bridge.seatOf(player)?.value ?: seatByPlayerId[player.id] ?: 0) to player.poisonCounters
                        }.filterKeys { it > 0 }
                }.getOrDefault(emptyMap()),
        )

    private fun cardViews(): List<HeadlessCard> =
        runCatching {
            game().registeredPlayers.flatMap { player ->
                val seat =
                    runCatching { bridge.seatOf(player)?.value }.getOrNull()
                        ?: when {
                            runCatching { bridge.getPlayer(seatId) == player }.getOrDefault(false) -> seatId.value
                            runCatching { bridge.getPlayer(opponentSeatId) == player }.getOrDefault(false) -> opponentSeatId.value
                            else -> null
                        }
                        ?: seatByPlayerId[player.id]
                        ?: return@flatMap emptyList()
                seatByPlayerId[player.id] = seat
                ZoneType.values().flatMap { zoneType ->
                    val cards =
                        if (zoneType == ZoneType.Stack) {
                            game().getStackZone().cards.filter { it.controller == player }
                        } else {
                            player.getZone(zoneType)?.cards.orEmpty()
                        }
                    cards.mapNotNull { card ->
                        val instanceId =
                            runCatching { bridge.instanceId(card) }.getOrNull()
                                ?: cardInstanceIds[card]
                                ?: return@mapNotNull null
                        cardInstanceIds[card] = instanceId
                        val objectInfo = accumulator.snapshot().objects[instanceId]
                        val displayName =
                            card.name.ifBlank {
                                objectInfo?.grpId?.let(::cardRepositoryByRuntime).orEmpty()
                            }
                        HeadlessCard(
                            instanceId = instanceId,
                            name = displayName,
                            seat = seat,
                            zone = zoneType.name,
                            power = card.netPower.takeIf { card.isCreature },
                            toughness = card.netToughness.takeIf { card.isCreature },
                            planeswalker = card.isPlaneswalker,
                            faceDown = card.isFaceDown,
                            isToken = card.isToken,
                            isCopy = objectInfo?.isCopy ?: false,
                            isLand = card.isLand,
                            isTapped = card.isTapped,
                            damage = card.damage,
                            counters =
                                runCatching { card.counters.entrySet().associate { it.element.name to it.count } }.getOrDefault(
                                    emptyMap(),
                                ),
                            colors =
                                objectInfo
                                    ?.colorList
                                    ?.map { it.name.substringBefore('_') }
                                    ?.toSet()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: runCatching {
                                        buildSet {
                                            val color = card.rules.color
                                            if (color.hasWhite()) add("White")
                                            if (color.hasBlue()) add("Blue")
                                            if (color.hasBlack()) add("Black")
                                            if (color.hasRed()) add("Red")
                                            if (color.hasGreen()) add("Green")
                                        }
                                    }.getOrDefault(emptySet()),
                            grpId = objectInfo?.grpId ?: 0,
                            objectSourceGrpId = objectInfo?.objectSourceGrpId ?: 0,
                            cardTypes =
                                objectInfo
                                    ?.cardTypesList
                                    ?.map { it.name }
                                    ?.toSet()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: runCatching { if (card.isCreature) setOf("Creature") else emptySet() }.getOrDefault(emptySet()),
                            subtypes =
                                objectInfo
                                    ?.subtypesList
                                    ?.map { it.name }
                                    ?.toSet()
                                    .orEmpty(),
                            abilityIds = objectInfo?.uniqueAbilitiesList?.map { it.grpId }.orEmpty(),
                            keywords =
                                runCatching {
                                    card
                                        .getKeywords()
                                        ?.mapNotNull { it.original }
                                        .orEmpty()
                                        .toSet()
                                }.getOrDefault(
                                    emptySet(),
                                ),
                            isSaddled = runCatching { card.isSaddled }.getOrDefault(false),
                            isSuspected = runCatching { card.isSuspected }.getOrDefault(false),
                            currentStateName = card.currentStateName,
                            sVars = runCatching { card.sVars?.keys.orEmpty() }.getOrDefault(emptySet()),
                            hasSickness = runCatching { card.isSick }.getOrDefault(false),
                            attachedToInstanceId =
                                runCatching {
                                    (card.entityAttachedTo as? forge.game.card.Card)?.let { bridge.instanceId(it) }
                                }.getOrNull(),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
}
