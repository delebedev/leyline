package leyline.game.state

import forge.ai.LobbyPlayerAi
import forge.game.Game
import forge.game.GameType
import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import forge.gamemodes.puzzle.Puzzle
import forge.player.PlayerControllerHuman
import forge.util.MyRandom
import leyline.DevCheck
import leyline.bridge.bootstrap.DeckLoader
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.bootstrap.isCommander
import leyline.bridge.bootstrap.isCommanderVariant
import leyline.bridge.coord.GameLoopController
import leyline.bridge.forge.PlayerController
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.MulliganBridge
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.MulliganPhase
import leyline.bridge.types.PhaseStopProfile
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.SeatId
import leyline.bridge.types.Seating
import leyline.bridge.types.opponent
import leyline.config.MatchConfig
import leyline.game.GamePlayback
import leyline.game.annotations.AnnotationBuilder
import leyline.game.bundle.BundleCursor
import leyline.game.bundle.MessageCounter
import leyline.game.codes.CounterTypes
import leyline.game.data.CardData
import leyline.game.data.CardProtoBuilder
import leyline.game.data.CardRepository
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.GameEventCollector
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GrpIdResolver
import leyline.game.snapshot.GsmSnapshot
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import java.lang.reflect.InvocationTargetException
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Bridges the client protocol to a real Forge [Game] engine.
 *
 * Creates a constructed game (human seat 1 + AI seat 2), starts the game loop,
 * and blocks until the engine reaches mulligan. The client handler reads hands
 * and submits keep/mull decisions through this bridge.
 *
 * Internally composed of focused components:
 * - [InstanceIdRegistry] — Forge cardId ↔ client instanceId bimap
 * - [LimboTracker] — retired instanceId history
 * - [DiffSnapshotter] — zone tracking + diff-baseline/client-seen snapshots
 *
 * Threading: [start] blocks the caller (~2-3s first call for card DB, <100ms after).
 * The engine thread blocks at mulligan via [MulliganBridge].
 */
class GameBridge(
    /** Timeout for action bridge / prompt bridge / mulligan bridge.
     *  Null waits indefinitely. Tests should pass a small finite value. */
    private val bridgeTimeoutMs: Long? = 45_000L,
    /** Playtest config — controls AI speed, die roll, etc. */
    val matchConfig: MatchConfig = MatchConfig(),
    /** Shared protocol counter for GRE message sequencing.
     *  Production: shared with MatchSession. Tests: local default. */
    val messageCounter: MessageCounter = MessageCounter(),
    /** Card data repository — lookups for grpId ↔ name, card metadata. */
    val cardRepository: CardRepository,
    /** Proto builder for GameObjectInfo — uses [cardRepository] for static card data. */
    val cardProto: CardProtoBuilder = CardProtoBuilder(cardRepository),
) : IdMapping,
    PlayerLookup,
    AutoPassView,
    ZoneTracking,
    AnnotationIds,
    EventDrain {
    private val log = LoggerFactory.getLogger(GameBridge::class.java)

    private var game: Game? = null

    /** True when the active game uses Commander/Brawl/Oathbreaker rules. */
    val isBrawlOrCommander: Boolean
        get() = game?.isCommander ?: false
    private val players: MutableMap<Int, Player> = mutableMapOf()
    private var loopController: GameLoopController? = null

    val abilityLineage = AbilityLineageRegistry()

    /** Shared signal — bridges notify when they have a pending item, replacing poll loops. */
    val prioritySignal = PrioritySignal()

    /**
     * Resolved die roll winner for this match.
     * Uses config override if set, otherwise randomizes (1 or 2) via Forge RNG.
     * Lazy — evaluated once, after [start] seeds the RNG.
     */
    val dieRollWinner: Int by lazy {
        matchConfig.game.dieRollWinner ?: (MyRandom.getRandom().nextInt(2) + 1)
    }

    // --- Per-seat bridge maps ---

    /** Forge cardId → AbilityRegistry for multi-ability abilityGrpId resolution. */
    private val abilityRegistries = ConcurrentHashMap<Int, AbilityRegistry>()

    private val actionBridges = mutableMapOf<Int, GameActionBridge>()
    private val promptBridges = mutableMapOf<Int, InteractivePromptBridge>()
    private val mulliganBridges = mutableMapOf<Int, MulliganBridge>()

    init {
        // Seed seat-1 bridges (human seat) — matches previous singleton behaviour.
        actionBridges[1] = GameActionBridge(timeoutMs = bridgeTimeoutMs, prioritySignal = prioritySignal)
        promptBridges[1] =
            InteractivePromptBridge(timeoutMs = bridgeTimeoutMs, prioritySignal = prioritySignal).also {
                it.forgeIidResolver = ::getOrAllocInstanceId
            }
        mulliganBridges[1] =
            MulliganBridge(
                autoKeep = matchConfig.game.skipMulligan,
                timeoutMs = bridgeTimeoutMs,
            )
    }

    /** Small seat-scoped facade — keeps handlers off global seat-1 defaults. */
    data class SeatBridges(
        val action: GameActionBridge,
        val prompt: InteractivePromptBridge,
        val mulligan: MulliganBridge,
    ) {
        fun drainReveals(): List<InteractivePromptBridge.RevealRecord> = prompt.drainReveals()
    }

    /** Parameterized accessor — throws if seat not populated. */
    fun actionBridge(seatId: SeatId): GameActionBridge = actionBridges[seatId.value] ?: error("No action bridge for seat ${seatId.value}")

    /** Parameterized accessor — throws if seat not populated. */
    fun promptBridge(seatId: SeatId): InteractivePromptBridge =
        promptBridges[seatId.value] ?: error("No prompt bridge for seat ${seatId.value}")

    /** All populated seat IDs (for iterating prompt bridges). */
    fun allSeatIds(): Set<Int> = promptBridges.keys

    /** Parameterized accessor — throws if seat not populated. */
    fun mulliganBridge(seatId: SeatId): MulliganBridge =
        mulliganBridges[seatId.value] ?: error("No mulligan bridge for seat ${seatId.value}")

    /** Seat-scoped facade — use in handlers instead of raw seat-1 aliases. */
    override fun seat(seatId: SeatId): SeatBridges =
        SeatBridges(
            action = actionBridge(seatId),
            prompt = promptBridge(seatId),
            mulligan = mulliganBridge(seatId),
        )

    /** Drain reveal queue(s) for a specific viewer; seat 0 drains all seats. */
    fun drainReveals(viewingSeatId: Int): List<InteractivePromptBridge.RevealRecord> =
        if (viewingSeatId == 0) {
            promptBridges.toSortedMap().values.flatMap { it.drainReveals() }
        } else {
            seat(SeatId(viewingSeatId)).drainReveals()
        }

    /**
     * Pre-populate auto-pass bridges for a synthetic seat.
     * Used by tests that need an extra passive seat without AI wiring.
     *
     * timeout=0 means: action bridge returns PassPriority immediately,
     * prompt bridge returns defaultIndex immediately, mulligan auto-keeps.
     */
    fun configureSyntheticSeat(seatId: SeatId) {
        actionBridges[seatId.value] = GameActionBridge(timeoutMs = 0, prioritySignal = prioritySignal)
        promptBridges[seatId.value] =
            InteractivePromptBridge(timeoutMs = 0, prioritySignal = prioritySignal).also {
                it.forgeIidResolver = ::getOrAllocInstanceId
            }
        mulliganBridges[seatId.value] = MulliganBridge(autoKeep = true, timeoutMs = 0)
        log.info("GameBridge: seat {} configured as synthetic (auto-pass)", seatId.value)
    }

    /** Human player's controller — set during [start]/[startFromPuzzle] for debug observability. */
    var humanController: PlayerController? = null
        private set

    /** Per-seat action playback — captures remote-action state diffs via EventBus. Empty before start(). */
    override val playbacks: MutableMap<SeatId, GamePlayback> = mutableMapOf()

    /** Convenience: seat-1 playback for single-player (1vAI) matches. */
    val playback: GamePlayback? get() = playbacks[SeatId(1)]

    /** Event collector — captures Forge engine events for annotation building. Null before start(). */
    var eventCollector: GameEventCollector? = null
        private set

    /** Phase stop profile — controls which phases the engine stops at per player. Null before start(). */
    var phaseStopProfile: PhaseStopProfile? = null
        private set

    /**
     * Look up a Forge [Card] by [ForgeCardId]. Used by snapshot-based pipeline stages
     * that need per-card Forge state while [ObjectMapper] and downstream callers
     * migrate to [GsmSnapshot]. Returns null if the card is not in any zone.
     */
    fun findCard(fid: ForgeCardId): Card? = game?.findById(fid.value)

    /**
     * Resolve a Forge [Card] to its client grpId — single entry point for runtime
     * callers that hold a live Forge card without a [leyline.game.snapshot.CardSnapshot]
     * in scope (e.g. [leyline.match.ActionPerformer] resolving an incoming action).
     *
     * Threads the token registry + card repository so callers can't accidentally
     * omit dependencies. Delegates to [GrpIdResolver.resolve].
     *
     * @param card the Forge card to resolve
     * @param instanceId client instanceId for registry cache lookups (0 = skip cache)
     */
    fun resolveGrpId(
        card: Card,
        instanceId: Int = 0,
    ): Int = GrpIdResolver.resolve(card, cardRepository, instanceId, tokenRegistry)

    // --- Composed components ---

    /** Card ID mapping (Forge cardId ↔ client instanceId). */
    val ids = InstanceIdRegistry()

    /** Retired instanceId history (Limbo zone). */
    val limbo = LimboTracker()

    /** Zone tracking + diff baseline/client-seen state tracking. */
    val diff = DiffSnapshotter(ids)

    /**
     * Bundle-sequence cursor shared by every [leyline.game.bundle.BundleBuilder] bound to this
     * bridge. See [BundleCursor] KDoc for lifecycle + invalidation semantics.
     */
    val bundleCursor: BundleCursor = BundleCursor()

    /**
     * Test-only observability hook — invoked per bundle after [leyline.game.mapping.StateMapper.buildDiff]
     * and [applyMutations], before the session's [BundleCursor] advances. Receives
     * (prev, cur, events, gameStateId, diff gsm). Currently used by
     * [leyline.game.PureDiffReplayTest] to capture live tuples for replay.
     */
    @VisibleForTesting
    @Volatile
    var diffListener: (
        (
            prev: GsmSnapshot?,
            cur: GsmSnapshot,
            events: List<GameEvent>,
            gameStateId: Int,
            diff: GameStateMessage,
        ) -> Unit
    )? = null

    // ── Reveal proxy lifecycle ──────────────────────────────────────────────
    // RevealedCard proxies exist during an active reveal-choose effect.
    // StateMapper drives RevealProxyTracker (allocate/lookup/drain) during
    // GSM assembly. After the choice resolves, proxy IDs move to
    // pendingProxyDeletions so the next diff emits RevealedCardDeleted +
    // diffDeletedInstanceIds.

    /** Currently active RevealedCard proxy IDs, managed via [RevealProxyTracker].
     *  Written on engine thread (during buildFromSnapshot), read serially — not concurrent. */
    val revealProxies: RevealProxyTracker = RevealProxyTracker()

    /** Layered effect lifecycle tracker — synthetic IDs + P/T boost diffing. */
    val effects = EffectTracker()

    /** Cached token grpId per instanceId — stable across diff ticks. */
    val tokenRegistry = TokenIdentityRegistry()

    /**
     * Annotation ID sequences + persistent annotation lifecycle.
     * See [PersistentAnnotationStore] class KDoc for the full lifecycle
     * (create/carry-forward/replace/delete/drain) and ordering invariants.
     */
    val annotations = PersistentAnnotationStore()

    /**
     * Cross-GSM lifecycle for transient `TriggerHolder` gameObjects (Mobilize
     * EOT-sacrifice today; exile-and-return mechanics later). See
     * [DelayedTriggerHolderTracker] for the diff-and-emit contract — the
     * tracker is fed each GSM by [leyline.game.mapping.StateMapper] and
     * drained for `diffDeletedInstanceIds` in the diff path.
     */
    val delayedTriggerHolders = DelayedTriggerHolderTracker()

    data class LibraryArrangementResult(
        val seatId: SeatId,
        val context: GroupingContext,
        val topIds: List<Int>,
        val awayIds: List<Int>,
    )

    private val pendingLibraryArrangements = ConcurrentLinkedQueue<LibraryArrangementResult>()

    fun recordLibraryArrangement(
        seatId: SeatId,
        context: GroupingContext,
        topIds: List<Int>,
        awayIds: List<Int>,
    ) {
        pendingLibraryArrangements.add(LibraryArrangementResult(seatId, context, topIds, awayIds))
    }

    fun pollLibraryArrangement(
        seatId: SeatId,
        context: GroupingContext,
    ): LibraryArrangementResult? {
        val match = pendingLibraryArrangements.firstOrNull { it.seatId == seatId && it.context == context } ?: return null
        pendingLibraryArrangements.remove(match)
        return match
    }

    /**
     * Active crew type-change effects: forgeCardId → effectId.
     * Allocated when a vehicle is crewed (type changes to creature),
     * removed when the crew effect expires (end of turn, vehicle reverts).
     */
    private val activeCrewEffects = mutableMapOf<ForgeCardId, Int>()

    /** Get or allocate a synthetic effect ID for a crewed vehicle's type-change effect. */
    fun getOrAllocCrewEffectId(vehicleId: ForgeCardId): Int = activeCrewEffects.getOrPut(vehicleId) { effects.nextEffectId() }

    /** Release expired crew effects. Returns effectIds that were removed. */
    fun releaseCrewEffects(currentCrewedIds: Set<ForgeCardId>): List<Int> {
        val expired = activeCrewEffects.keys - currentCrewedIds
        return expired.mapNotNull { activeCrewEffects.remove(it) }
    }

    /** Drain pending target specs from all seat prompt bridges. */
    fun drainPendingTargetSpecs(): List<InteractivePromptBridge.PendingTarget> =
        promptBridges.values.flatMap { it.drainPendingTargetSpecs() }

    override fun nextAnnotationId(): Int = annotations.nextAnnotationId()

    override fun nextPersistentAnnotationId(): Int = annotations.nextPersistentAnnotationId()

    // --- Interface implementations (IdMapping, PlayerLookup, ZoneTracking, etc.) ---

    override fun getOrAllocInstanceId(forgeCardId: ForgeCardId): InstanceId = ids.getOrAlloc(forgeCardId)

    override fun reallocInstanceId(forgeCardId: ForgeCardId): InstanceIdRegistry.IdReallocation = ids.realloc(forgeCardId)

    override fun getForgeCardId(instanceId: InstanceId): ForgeCardId? = ids.getForgeCardId(instanceId)

    /** Read-only snapshot of instanceId → forgeCardId (all, including retired). */
    fun getInstanceIdMap(): Map<InstanceId, ForgeCardId> = ids.snapshot()

    /** Read-only snapshot of forgeCardId → current active instanceId. */
    fun getActiveInstanceIdMap(): Map<ForgeCardId, InstanceId> = ids.activeSnapshot()

    /** Set of instanceIds currently retired to Limbo. */
    fun getLimboSet(): Set<Int> = limbo.all().toSet()

    /** Proto zone tracking — where the protocol last placed each instanceId. */
    fun getProtoZones(): Map<Int, Int> = diff.allZones()

    override fun retireToLimbo(instanceId: InstanceId) {
        limbo.retire(instanceId.value)
        tokenRegistry.retire(instanceId.value)
    }

    override fun getLimboInstanceIds(): List<InstanceId> = limbo.all().map { InstanceId(it) }

    override fun recordZone(
        instanceId: InstanceId,
        zoneId: Int,
    ): Int? = diff.recordZone(instanceId.value, zoneId)

    override fun getPreviousZone(instanceId: InstanceId): Int? = diff.getPreviousZone(instanceId.value)

    /**
     * Apply ordering-sensitive mutations returned by [leyline.game.mapping.StateMapper.buildDiff].
     * Fixed order: id reallocations → limbo retires → zone recordings →
     * persistent annotation batch → next annotation ID counter.
     *
     * Called by [leyline.game.bundle.BundleBuilder] between diff compute and action build.
     */
    fun applyMutations(m: BridgeMutations) {
        for (r in m.idReallocations) ids.applyRealloc(r)
        for (id in m.retiredIds) retireToLimbo(id)
        for ((iid, zid) in m.zoneRecordings) recordZone(iid, zid)
        annotations.applyBatchResult(m.persistentBatch)
        annotations.setAnnotationId(m.nextAnnotationId)
    }

    override fun closeFrame(): FrameEventLog = eventCollector?.closeFrame() ?: FrameEventLog.EMPTY

    /**
     * Close the event frame for one bundle build: collector events + reveal records
     * for [viewingSeatId] (promoted to [GameEvent.CardsRevealed]). Caller passes
     * the returned log to [leyline.game.mapping.StateMapper.buildFromSnapshot] /
     * [leyline.game.mapping.StateMapper.buildDiff].
     *
     * One close per call; per-seat reveal consumption is seat-scoped. A multi-seat
     * close (so two per-seat builds of the same snapshot see the same reveals) is
     * a separate design concern if the pattern ever matters.
     */
    fun closeBundleFrame(viewingSeatId: Int = 0): FrameEventLog {
        val events = closeFrame().events.toMutableList()
        for (reveal in drainReveals(viewingSeatId)) {
            events.add(GameEvent.CardsRevealed(reveal.forgeCardIds, reveal.ownerSeatId))
        }
        return FrameEventLog(events)
    }

    /** True if the open frame has accumulated events not yet closed into a GSM. */
    fun hasPendingEvents(): Boolean = eventCollector?.hasEvents() ?: false

    companion object {
        /** Fallback grpId for cards not in client DB (renders face-down). */
        const val FALLBACK_GRPID = 0

        /** Forge-id offset for synthetic delayed-trigger holder objects. The
         *  holder's forge id is `<source card forge id> + offset`, which gets
         *  fed through [getOrAllocInstanceId] to yield a stable instance id that
         *  both `DelayedTriggerAffectees.affectorId` and the per-token
         *  `TemporaryPermanent.affectorId` reference. Picked above any plausible
         *  real or stack-ability forge id range so it doesn't collide. */
        const val DELAYED_TRIGGER_HOLDER_FORGE_OFFSET = 90_000_000

        /** Default deck when no decklist is provided (tests, puzzles without decks). */
        private const val FALLBACK_DECK = """
20 Llanowar Elves
4 Elvish Mystic
4 Giant Growth
32 Forest
"""

        private const val DEFAULT_PRIORITY_WAIT_MS = 15_000L

        /** Short settle delay after detecting pending state — lets engine thread finish
         *  in-flight zone moves before we snapshot. */
        private const val SETTLE_MS = 10L

        /** Max time to wait for gsId to advance after detecting a pending interaction.
         *  Capped to avoid stalling on prompts that fire before any GSM is sent. */
        private const val PROGRESS_WAIT_MS = 50L

        /** Poll interval for mulligan ready check (no signal available for mulligan). */
        private const val POLL_INTERVAL_MS = 50L
    }

    /**
     * How long [awaitPriority] waits for the engine to reach a priority stop.
     * Production default: 15s. Tests should use ~2s since the engine responds
     * in <100ms and the extra headroom only delays timeout-based test failures.
     */
    var priorityWaitMs: Long = DEFAULT_PRIORITY_WAIT_MS

    /**
     * Wrap an existing [Game] without starting the engine loop thread.
     *
     * The game should already be at the desired phase (via `devModeSet`).
     * Cards should already be in zones (via `Zone.add`). This method wires
     * only the components needed for proto output: [GameEventCollector] for
     * annotations, [InstanceIdRegistry] for card IDs.
     *
     * No bridges, no threads, no CompletableFuture — fully synchronous.
     * Forge events fire inline when you call `game.action.*` methods.
     *
     * Use in conformance tests where you need [leyline.game.mapping.StateMapper] + [leyline.game.bundle.BundleBuilder]
     * but don't need the game loop or player interaction.
     */
    fun wrapGame(g: Game) {
        game = g
        populateSeatMap(g)
        val collector = GameEventCollector(this)
        eventCollector = collector
        g.subscribeToEvents(collector)
    }

    /**
     * Initialize card DB, create game, start engine loop, wait for mulligan.
     * Blocks caller until engine has dealt hands and is waiting for keep/mull.
     *
     * @param seed if non-null, seeds the RNG for deterministic shuffles (tests/replays)
     * @param deckList1 decklist text for seat 1 (human). Falls back to [deckList] or built-in fallback.
     * @param deckList2 decklist text for seat 2 (AI). Falls back to [deckList1].
     * @param deckList single-deck shorthand — applies to both seats when deckList1/deckList2 omitted.
     */
    fun start(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
        variant: String? = null,
    ) {
        log.info("GameBridge: initializing card database")
        GameBootstrap.initializeCardDatabase()

        if (seed != null) {
            log.info("GameBridge: using deterministic seed={}", seed)
            MyRandom.setRandom(Random(seed))
        } else {
            log.info("GameBridge: using random seed")
        }

        val seat1Str = (deckList1 ?: deckList ?: FALLBACK_DECK).trimIndent()
        val seat2Str = (deckList2 ?: deckList ?: seat1Str).trimIndent()
        val deck1 = DeckLoader.parseDeckList(seat1Str)
        val deck2 = DeckLoader.parseDeckList(seat2Str)
        log.info(
            "GameBridge: parsed decks (seat1={} cards, seat2={} cards)",
            deck1.main.countAll(),
            deck2.main.countAll(),
        )

        val g =
            if (variant != null && isCommanderVariant(variant)) {
                log.info("GameBridge: creating commander-variant game (variant={})", variant)
                GameBootstrap.createCommanderGame(deck1, deck2, variant)
            } else {
                GameBootstrap.createConstructedGame(deck1, deck2)
            }
        game = g

        populateSeatMap(g)

        // Wire PlayerController for seat 1 (human) with mulligan bridge
        val human = g.players.first { it.lobbyPlayer !is LobbyPlayerAi }
        val aiPlayer = g.players.first { it.lobbyPlayer is LobbyPlayerAi }
        phaseStopProfile = PhaseStopProfile.createDefaults(human.id, aiPlayer.id)
        val controller =
            PlayerController(
                game = g,
                player = human,
                lobbyPlayer = human.lobbyPlayer,
                bridge = promptBridge(SeatId(1)),
                seating = seating,
                actionBridge = actionBridge(SeatId(1)),
                mulliganBridge = mulliganBridge(SeatId(1)),
                phaseStopProfile = phaseStopProfile,
            )
        humanController = controller
        human.addController(Long.MAX_VALUE - 1, human, controller, false)

        // AI keeps its default controller — handles priority, combat, etc. natively

        val loop =
            GameLoopController(
                g,
                actionBridges = actionBridges.values.toList(),
                promptBridges = promptBridges.values.toList(),
                mulliganBridges = mulliganBridges.values.toList(),
                prioritySignal = prioritySignal,
            )
        loopController = loop
        loop.start()
        loop.awaitStarted()

        // Register event collector FIRST — must fire before playback so closeFrame()
        // includes the current event when playback's captureAndPause runs.
        val collector = GameEventCollector(this)
        eventCollector = collector
        g.subscribeToEvents(collector)
        log.info("GameBridge: registered GameEventCollector for event-driven annotations")

        // Register action playback subscriber (after collector)
        val pb = GamePlayback(this, "forge-match-1", 1, messageCounter, matchConfig.aiDelayMultiplier)
        playbacks[SeatId(1)] = pb
        g.subscribeToEvents(pb)
        log.info("GameBridge: registered GamePlayback for seat 1")

        if (matchConfig.game.skipMulligan) {
            log.info("GameBridge: skipMulligan — engine auto-kept, waiting for priority")
            awaitPriority()
            log.info("GameBridge: engine reached priority after auto-keep")
        } else {
            log.info("GameBridge: game loop started, waiting for mulligan")
            awaitMulliganReady()
            log.info("GameBridge: engine reached mulligan, hand ready")
        }
    }

    /** Get the current hand for a seat as client grpIds. */
    fun getHandGrpIds(seatId: SeatId): List<Int> {
        val player = getPlayer(seatId) ?: return emptyList()
        return player.getZone(ZoneType.Hand).cards.map { card ->
            DevCheck.requireOrNull(cardRepository.findGrpIdByName(card.name)) { "hand grpId miss: '${card.name}'" }
                ?: FALLBACK_GRPID
        }
    }

    /** Full deck as client grpIds (for initial bundle deck message). */
    fun getDeckGrpIds(seatId: SeatId): List<Int> {
        val player = getPlayer(seatId) ?: return emptyList()
        // Combine library + hand + any other zones to reconstruct full deck
        val allCards = mutableListOf<String>()
        for (zone in listOf(ZoneType.Library, ZoneType.Hand)) {
            player.getZone(zone).cards.forEach { allCards.add(it.name) }
        }
        return allCards.map {
            DevCheck.requireOrNull(cardRepository.findGrpIdByName(it)) { "deck grpId miss: '$it'" }
                ?: FALLBACK_GRPID
        }
    }

    override fun getGame(): Game? = game

    override fun getPlayer(seatId: SeatId): Player? = players[seatId.value]

    /**
     * Look up or lazily build the [AbilityRegistry] for a Forge card.
     *
     * Pre-populated for puzzle cards via [registerPuzzleCards]. For all other
     * game types (constructed, tokens, zone transfers), built on first access
     * from the live [card] + [cardData].
     */
    fun abilityRegistryFor(
        card: Card,
        cardData: CardData?,
    ): AbilityRegistry? {
        if (cardData == null) return null
        return abilityRegistries.computeIfAbsent(card.id) { AbilityRegistry.build(card, cardData) }
    }

    /** Evict cached AbilityRegistry for a card (e.g. after DFC transform). */
    fun evictAbilityRegistry(forgeCardId: Int) {
        abilityRegistries.remove(forgeCardId)
    }

    /**
     * Seat role mapping for this match — human vs Familiar.
     * Populated by [populateSeatMap] at game-start.
     */
    lateinit var seating: Seating
        private set

    /** Populate seat map by registration order (seat 1 = first, seat 2 = second). */
    private fun populateSeatMap(g: Game) {
        g.players.forEachIndexed { index, player -> players[index + 1] = player }
        val humanIdx = g.players.indexOfFirst { it.lobbyPlayer !is LobbyPlayerAi }
        if (humanIdx < 0) error("GameBridge.populateSeatMap: no human player in game (all LobbyPlayerAi)")
        val humanSeat = SeatId(humanIdx + 1)
        seating = Seating(humanSeat = humanSeat, familiarSeat = humanSeat.opponent)
        log.info("GameBridge: seating resolved human={} familiar={}", seating.humanSeat.value, seating.familiarSeat.value)
    }

    /**
     * Block until the engine reaches a priority stop (via [GameActionBridge]).
     * After keep, the engine auto-advances through Beginning → Main1.
     */
    override fun awaitPriority() {
        awaitPriorityWithTimeout(priorityWaitMs)
    }

    /**
     * Block until the engine reaches a priority stop, an interactive prompt
     * is pending, or the game ends.
     *
     * Uses [PrioritySignal] (semaphore-based) instead of polling — both
     * [GameActionBridge] and [InteractivePromptBridge] signal when they post
     * a pending item, so we wake up immediately with no 50ms poll latency.
     *
     * The prompt check is needed because targeted spells (e.g. Giant Growth)
     * block the engine thread in [InteractivePromptBridge.requestChoice] before
     * the next action-bridge priority stop is reached. Without this, casting a
     * targeted spell would appear to time out.
     *
     * After detecting a pending interaction, waits for [messageCounter] to
     * advance (proving engine output) before returning. This prevents the
     * caller from draining the sink before the engine has written messages.
     *
     * @param timeoutMs max wait time (use longer values for AI turns)
     * @return true if priority was reached, false if timed out or game over
     */
    override fun awaitPriorityWithTimeout(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val entryGsId = messageCounter.currentGsId()
        while (true) {
            // Check conditions first (handles already-pending case)
            val g = game
            if (g != null && g.isGameOver) {
                log.info("GameBridge: game over detected while waiting for priority")
                return false
            }
            if (hasPendingInteraction()) {
                // Wait for engine to produce output (gsId advances), then settle
                awaitProgress(entryGsId, deadline)
                Thread.sleep(SETTLE_MS)
                return true
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                log.warn("GameBridge: timed out waiting for priority ({}ms)", timeoutMs)
                return false
            }

            // Wait for signal from either bridge (or timeout)
            prioritySignal.awaitSignal(remaining)
        }
    }

    private fun hasPendingInteraction(): Boolean =
        actionBridges.values.any { it.getPending() != null } ||
            promptBridges.values.any { it.getPendingPrompt() != null } ||
            humanController?.pendingDamageAssignment != null ||
            humanController?.pendingOptionalAction != null ||
            humanController?.pendingNumericInput != null

    /**
     * Spin until the message counter advances past [entryGsId], proving engine output.
     * Capped at [PROGRESS_WAIT_MS] to avoid stalling on prompts that fire before any GSM.
     */
    private fun awaitProgress(
        entryGsId: Int,
        deadline: Long,
    ) {
        if (entryGsId == 0) return
        val progressDeadline = minOf(deadline, System.currentTimeMillis() + PROGRESS_WAIT_MS)
        while (messageCounter.currentGsId() <= entryGsId) {
            if (System.currentTimeMillis() >= progressDeadline) return
            Thread.sleep(1)
        }
    }

    /** Submit keep decision for seat. Only the human seat's decision is wired today. */
    // TODO: wire mulliganBridge for familiarSeat to support paired mulligan flow
    fun submitKeep(seatId: SeatId) {
        log.info("GameBridge: seat {} keeps hand", seatId.value)
        if (seatId == seating.humanSeat) mulliganBridge(seatId).submitKeep()
    }

    // TODO: wire mulliganBridge for familiarSeat to support paired mulligan flow

    /**
     * Submit mulligan decision for seat.
     * Blocks until engine re-deals and reaches mulligan again.
     *
     * London mulligan: after mull, the engine draws 7 then calls
     * [tuckCardsViaMulligan] which blocks on [MulliganPhase.WaitingTuck].
     * We auto-tuck first N cards (same as forge-web) to unblock the engine,
     * then wait for the next [MulliganPhase.WaitingKeep].
     */
    fun submitMull(seatId: SeatId) {
        log.info("GameBridge: seat {} mulligans", seatId.value)
        if (seatId == seating.humanSeat) {
            // Capture current prompt sequence BEFORE submitting —
            // avoids race where we see the stale WaitingKeep from the current round.
            val bridge = mulliganBridge(seatId)
            val seqBefore = bridge.promptSequence
            bridge.submitMull()
            // London: engine draws 7 then calls tuckCardsViaMulligan() → WaitingTuck.
            // Wait for a NEW prompt (higher sequence) that's either WaitingTuck or WaitingKeep.
            val deadline = System.currentTimeMillis() + matchConfig.server.mulliganWaitMs
            while (System.currentTimeMillis() < deadline) {
                val prompt = bridge.pendingPromptAfter(seqBefore)
                if (prompt != null) {
                    when (prompt.phase) {
                        MulliganPhase.WaitingKeep -> {
                            log.info("GameBridge: engine re-dealt hand after mulligan (no tuck)")
                            return
                        }
                        MulliganPhase.WaitingTuck -> {
                            val n = prompt.cardsToTuck
                            val hand = getHandCards(seatId)
                            log.info("GameBridge: auto-tucking {} cards (London mulligan)", n)
                            bridge.submitTuck(hand.take(n))
                            // After tuck, engine continues → next WaitingKeep
                            awaitMulliganReady()
                            log.info("GameBridge: engine re-dealt hand after mulligan+tuck")
                            return
                        }
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            log.warn("GameBridge: timed out waiting for engine after mull+tuck")
        }
    }

    /**
     * Block until the engine reaches the tuck phase after a kept mulligan.
     * The engine calls [MulliganBridge.awaitTuckDecision] on the game thread,
     * publishing a [MulliganPhase.WaitingTuck] prompt.
     */
    fun awaitTuckReady() {
        val deadline = System.currentTimeMillis() + matchConfig.server.mulliganWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (mulliganBridge(SeatId(1)).pendingPrompt()?.phase == MulliganPhase.WaitingTuck) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("GameBridge: timed out waiting for engine to reach tuck phase")
    }

    // TODO: parameterize by seatId for paired-flow mulligan support

    /** How many cards the player must put on bottom (London mulligan). */
    fun getTuckCount(): Int = mulliganBridge(SeatId(1)).pendingPrompt()?.cardsToTuck ?: 0

    /** Get the current hand as Card objects for a seat. */
    fun getHandCards(seatId: SeatId): List<Card> {
        val player = getPlayer(seatId) ?: return emptyList()
        return player.getZone(ZoneType.Hand).cards.toList()
    }

    // TODO: wire mulliganBridge for familiarSeat to support paired tuck flow

    /** Submit tuck decision — cards to put on bottom of library. */
    fun submitTuck(
        seatId: SeatId,
        cards: List<Card>,
    ) {
        log.info("GameBridge: seat {} tucking {} cards", seatId.value, cards.size)
        if (seatId == seating.humanSeat) mulliganBridge(seatId).submitTuck(cards)
    }

    /** True when this bridge is running a puzzle game. */
    val isPuzzle: Boolean
        get() = game?.rules?.gameType == GameType.Puzzle

    /**
     * Initialize card DB, create a puzzle game, apply puzzle state, finalize,
     * start the game loop from current state (no mulligan), and wait for priority.
     *
     * @param puzzle the parsed [Puzzle] object to apply
     */
    fun startPuzzle(puzzle: Puzzle) {
        log.info("GameBridge: starting puzzle mode")
        GameBootstrap.initializeCardDatabase()

        val g = GameBootstrap.createPuzzleGame()
        game = g
        populateSeatMap(g)

        // Apply puzzle state via reflection (applyGameOnThread is protected).
        // Install temp PlayerControllers with autoKeep + zero-timeout bridges
        // to handle any SBAs/triggers during setup (forge-web pattern).
        applyPuzzleSafely(puzzle, g)

        // Auto-detect commander presence → apply Brawl variant for commander tax,
        // zone-return rules, and correct starting life. Must happen after puzzle
        // state is applied (commanders are placed by GameState.applyToGame).
        val hasCommander = g.players.any { it.commanders.isNotEmpty() }
        if (hasCommander) {
            g.rules.addAppliedVariant(GameType.Brawl)
            log.info("GameBridge: puzzle has commander — applied Brawl variant")
        }

        // Finalize: set age=Play, position at MAIN1 turn 1
        GameBootstrap.finalizeForPuzzle(g)
        log.info("GameBridge: puzzle applied, game at {} turn {}", g.phaseHandler.phase, g.phaseHandler.turn)

        // Register all puzzle cards in CardRepository and InstanceIdRegistry.
        // Puzzle.applyGameOnThread creates cards via Card.fromPaperCard — they
        // need synthetic grpIds and instanceId mappings for proto output.
        registerPuzzleCards(g)

        // Seed persistent Attachment annotations for pre-attached cards (equipment/auras).
        // No CardAttached events fire for cards that start attached — seed from engine state.
        seedAttachmentAnnotations(g)

        // Forge's puzzle loader applies counters via addCounterInternal(fireEvents=false),
        // so no CountersChanged event reaches MechanicAnnotations. Without Counter_803b,
        // MTGA renders permanents at 0 counters (planeswalkers lose their loyalty UI and
        // suppress the activation modal). Seed from engine state.
        seedCounterAnnotations(g)

        // Wire PlayerController for seat 1 (human) — same as constructed
        // but no mulligan bridge needed (autoKeep=true, unused).
        val human = g.players.first { it.lobbyPlayer !is LobbyPlayerAi }
        val aiPlayer = g.players.first { it.lobbyPlayer is LobbyPlayerAi }
        phaseStopProfile = PhaseStopProfile.createDefaults(human.id, aiPlayer.id)
        val controller =
            PlayerController(
                game = g,
                player = human,
                lobbyPlayer = human.lobbyPlayer,
                bridge = promptBridge(SeatId(1)),
                seating = seating,
                actionBridge = actionBridge(SeatId(1)),
                mulliganBridge = mulliganBridge(SeatId(1)),
                phaseStopProfile = phaseStopProfile,
            )
        humanController = controller
        human.addController(Long.MAX_VALUE - 1, human, controller, false)

        // Start game loop from current state (skip Match.startGame/mulligan)
        val loop =
            GameLoopController(
                g,
                actionBridges = actionBridges.values.toList(),
                promptBridges = promptBridges.values.toList(),
                mulliganBridges = mulliganBridges.values.toList(),
                prioritySignal = prioritySignal,
            )
        loopController = loop
        loop.startFromCurrentState()
        loop.awaitStarted()

        // Register event collector and playback (same as constructed)
        val collector = GameEventCollector(this)
        eventCollector = collector
        g.subscribeToEvents(collector)

        val pb = GamePlayback(this, "forge-match-1", 1, messageCounter, matchConfig.aiDelayMultiplier)
        playbacks[SeatId(1)] = pb
        g.subscribeToEvents(pb)

        log.info("GameBridge: puzzle loop started, waiting for priority")
        awaitPriority()
        log.info("GameBridge: puzzle engine reached priority, ready")
    }

    /**
     * Tear down the current game and start a new puzzle in-place.
     * Clears all bridge state (instanceIds, limbo, zones, snapshots, annotations)
     * so the new puzzle gets a clean slate. The client receives a Full GSM after.
     *
     * @return old instanceIds that the client should delete (for diffDeletedInstanceIds)
     */
    fun resetForPuzzle(puzzle: Puzzle): List<Int> {
        log.info("GameBridge: resetting for new puzzle")

        shutdown()

        // Clear all mapping/tracking state from the previous game
        val deletedIds = ids.resetAll().map { it.value }
        limbo.clear()
        diff.resetAll()
        effects.resetAll()
        annotations.resetAll()
        delayedTriggerHolders.resetAll()
        activeCrewEffects.clear()
        abilityRegistries.clear()
        tokenRegistry.clear()
        revealProxies.clear()

        // Drain bridge state from previous game
        for (bridge in promptBridges.values) bridge.resetForPuzzle()

        startPuzzle(puzzle)
        log.info("GameBridge: puzzle hot-swap complete, deleted {} old instanceIds", deletedIds.size)
        return deletedIds
    }

    /**
     * Tear down heavyweight resources: unsubscribe EventBus listeners, stop game loop.
     * Called by [leyline.match.Match.close] for deterministic lifecycle management.
     * Idempotent — safe to call before [shutdown].
     */
    fun teardownResources() {
        val g = game
        if (g != null) {
            eventCollector?.let { g.unsubscribeFromEvents(it) }
            for (pb in playbacks.values) {
                g.unsubscribeFromEvents(pb)
            }
        }
        loopController?.shutdown()
        loopController = null
        playbacks.clear()
        eventCollector = null
    }

    /**
     * Full shutdown: tear down resources + clear per-seat state.
     * Tests and puzzle reset call this directly. Production code goes through
     * [leyline.match.Match.close] which calls [teardownResources] then this.
     */
    fun shutdown() {
        log.info("GameBridge: shutting down")
        teardownResources()
        game = null
        players.clear()
    }

    // --- Puzzle internals ---

    /**
     * Apply puzzle state to the game via reflection.
     * Installs temp [PlayerController]s with autoKeep/zero-timeout during
     * application to handle any SBAs or triggers that fire during setup.
     */
    @Suppress("SwallowedException") // InvocationTargetException.targetException forwarded as cause
    private fun applyPuzzleSafely(puzzle: Puzzle, game: Game) {
        val method = puzzle.javaClass.superclass.getDeclaredMethod("applyGameOnThread", Game::class.java)
        method.isAccessible = true
        runWithTempControllers(game.players.filter { it.controller is PlayerControllerHuman }) {
            try {
                method.invoke(puzzle, game)
            } catch (e: InvocationTargetException) {
                throw IllegalStateException("Puzzle application failed", e.targetException)
            }
        }
    }

    /**
     * Recursively install temp [PlayerController]s with zero-timeout bridges
     * on each human-controlled player during [block]. Removed automatically after.
     */
    private fun runWithTempControllers(
        players: List<Player>,
        block: () -> Unit,
    ) {
        val player =
            players.firstOrNull() ?: run {
                block()
                return
            }
        val tempPrompt = InteractivePromptBridge(timeoutMs = 0)
        val tempAction = GameActionBridge(timeoutMs = 0)
        val tempMulligan = MulliganBridge(autoKeep = true, timeoutMs = 0)
        val tempController =
            PlayerController(
                game = player.game,
                player = player,
                lobbyPlayer = player.lobbyPlayer,
                bridge = tempPrompt,
                seating = seating,
                actionBridge = tempAction,
                mulliganBridge = tempMulligan,
            )
        player.runWithController(
            { runWithTempControllers(players.drop(1), block) },
            tempController,
        )
    }

    /**
     * After puzzle application: allocate instanceIds and pre-populate
     * [AbilityRegistry] for all cards in all zones.
     */
    private fun registerPuzzleCards(game: Game) {
        val allZones =
            listOf(
                ZoneType.Hand,
                ZoneType.Battlefield,
                ZoneType.Library,
                ZoneType.Graveyard,
                ZoneType.Exile,
                ZoneType.Command,
            )
        var registered = 0
        val playerToSeat: Map<forge.game.player.Player, Int> =
            players.entries.associate { it.value to it.key }
        for (player in game.players) {
            val seatId = playerToSeat[player] ?: continue
            for (zone in allZones) {
                val protocolZoneId = puzzleZoneId(zone, seatId) ?: continue
                for (card in player.getZone(zone).cards) {
                    if (card.rules != null) {
                        val grpId = cardRepository.findGrpIdByName(card.name)
                        if (grpId != null) {
                            val cardData = cardRepository.findByGrpId(grpId)
                            abilityRegistryFor(card, cardData)
                        }
                    }
                    val iid = ids.getOrAlloc(ForgeCardId(card.id))
                    // Seed zone tracking so the FIRST diff after the puzzle
                    // GSM can detect zone changes against the puzzle's
                    // initial state (cycling discard, unearth return, …).
                    // Without this seed, ZoneTransferDetector reads
                    // previousZones[iid]==null and silently skips emission.
                    diff.recordZone(iid.value, protocolZoneId)
                    registered++
                }
            }
        }
        log.info("GameBridge: registered {} puzzle cards in InstanceIdRegistry", registered)
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun puzzleZoneId(
        zone: ZoneType,
        seatId: Int,
    ): Int? =
        when (zone) {
            ZoneType.Hand -> ZoneIds.handOf(seatId)
            ZoneType.Library -> ZoneIds.libraryOf(seatId)
            ZoneType.Graveyard -> ZoneIds.graveyardOf(seatId)
            ZoneType.Battlefield -> ZoneIds.BATTLEFIELD
            ZoneType.Exile -> ZoneIds.EXILE
            ZoneType.Command -> ZoneIds.COMMAND
            // Sideboard / Stack / RareAside / etc. — not zones we seed for puzzles.
            else -> null
        }

    /**
     * Seed persistent [AnnotationType.Attachment] annotations for cards that start
     * pre-attached in a puzzle. No [GameEventCardAttachment] fires for these — the
     * attachment relationship exists only in Forge's [Card.attachedTo] field.
     */
    private fun seedAttachmentAnnotations(game: Game) {
        for (player in game.players) {
            for (card in player.getZone(ZoneType.Battlefield).cards) {
                val target = card.attachedTo ?: continue
                val auraIid = ids.getOrAlloc(ForgeCardId(card.id))
                val targetIid = ids.getOrAlloc(ForgeCardId(target.id))
                val ann =
                    AnnotationBuilder
                        .attachment(auraIid, targetIid)
                        .toBuilder()
                        .setId(annotations.nextPersistentAnnotationId())
                        .build()
                annotations.add(ann)
                log.debug(
                    "seedAttachment: {} (iid={}) → {} (iid={})",
                    card.name,
                    auraIid.value,
                    target.name,
                    targetIid.value,
                )
            }
        }
    }

    /**
     * Seed persistent [AnnotationType.Counter_803b] annotations for permanents that
     * start with counters (loyalty on planeswalkers, +1/+1 on creatures, etc.). Forge's
     * puzzle loader bypasses the event chain when applying counters, so no
     * [GameEvent.CountersChanged] fires.
     */
    private fun seedCounterAnnotations(game: Game) {
        for (player in game.players) {
            for (card in player.getZone(ZoneType.Battlefield).cards) {
                val counters = card.counters
                if (counters.isEmpty()) continue
                val instanceId = ids.getOrAlloc(ForgeCardId(card.id))
                for ((counterType, count) in counters) {
                    if (count <= 0) continue
                    val ann =
                        AnnotationBuilder
                            .counter(instanceId, CounterTypes.counterTypeId(counterType.name), count)
                            .toBuilder()
                            .setId(annotations.nextPersistentAnnotationId())
                            .build()
                    annotations.add(ann)
                    log.debug(
                        "seedCounter: {} (iid={}) {} = {}",
                        card.name,
                        instanceId.value,
                        counterType.name,
                        count,
                    )
                }
            }
        }
    }

    /**
     * Snapshot current P/T boost state for all battlefield cards.
     * Returns map of cardInstanceId → boost entries from Forge's boostPT table.
     */
    fun snapshotBoosts(): Map<Int, List<EffectTracker.BoostEntry>> {
        val game = game ?: return emptyMap()
        val result = mutableMapOf<Int, List<EffectTracker.BoostEntry>>()
        for (player in game.players) {
            for (card in player.getZone(ZoneType.Battlefield).cards) {
                val table = card.ptBoostTable
                if (table.isEmpty) continue
                val instanceId = ids.getOrAlloc(ForgeCardId(card.id))
                val entries =
                    table.cellSet().map { cell ->
                        EffectTracker.BoostEntry(
                            timestamp = cell.rowKey,
                            staticId = cell.columnKey,
                            power = cell.value.left,
                            toughness = cell.value.right,
                        )
                    }
                result[instanceId.value] = entries
            }
        }
        return result
    }

    /**
     * Snapshot current extrinsic keyword grants for all battlefield cards.
     * Returns map of cardInstanceId -> keyword entries from Forge's changedCardKeywords table (Layer 6).
     * Direct parallel to [snapshotBoosts] for P/T.
     */
    fun snapshotKeywords(): Map<Int, List<EffectTracker.KeywordEntry>> {
        val game = game ?: return emptyMap()
        val result = mutableMapOf<Int, MutableList<EffectTracker.KeywordEntry>>()
        for (player in game.players) {
            for (card in player.getZone(ZoneType.Battlefield).cards) {
                val table = card.changedCardKeywords
                if (table.isEmpty) continue
                val instanceId = ids.getOrAlloc(ForgeCardId(card.id))
                for (cell in table.cellSet()) {
                    val timestamp = cell.rowKey
                    val staticId = cell.columnKey
                    for (kw in cell.value.keywords) {
                        result
                            .getOrPut(instanceId.value) { mutableListOf() }
                            .add(EffectTracker.KeywordEntry(timestamp, staticId, kw.keyword.toString()))
                    }
                }
            }
        }
        return result
    }

    /**
     * Snapshot of a crewed vehicle: vehicle card, its instanceId, and the
     * instanceIds of creatures that crewed it.
     */
    data class CrewSnapshot(
        val vehicleForgeCardId: ForgeCardId,
        val vehicleInstanceId: Int,
        val crewSourceInstanceIds: List<Int>,
        /** True when the vehicle is currently a creature (crew resolved). */
        val isCreature: Boolean,
        /** Crew ability grpId, resolved from the vehicle's ability registry. */
        val crewAbilityGrpId: Int?,
    )

    /** Snapshot of a saddled mount and the helper creatures that saddled it. */
    data class SaddleSnapshot(
        val mountInstanceId: Int,
        val saddleSourceInstanceIds: List<Int>,
    )

    /**
     * Snapshot which vehicles are currently crewed this turn.
     * Iterates all battlefield cards and checks `card.getCrewedByThisTurn()`.
     */
    fun snapshotCrewState(): List<CrewSnapshot> {
        val game = game ?: return emptyList()
        val result = mutableListOf<CrewSnapshot>()
        for (player in game.players) {
            for (card in player.getZone(ZoneType.Battlefield).cards) {
                val crewSources = card.getCrewedByThisTurn()
                if (crewSources == null || crewSources.isEmpty()) continue

                val vehicleFid = ForgeCardId(card.id)
                val vehicleIid = ids.getOrAlloc(vehicleFid).value
                val sourceIids = crewSources.map { ids.getOrAlloc(ForgeCardId(it.id)).value }

                // Resolve crew ability grpId (Task 4)
                val crewAbilityGrpId = resolveCrewAbilityGrpId(card)

                result.add(
                    CrewSnapshot(
                        vehicleForgeCardId = vehicleFid,
                        vehicleInstanceId = vehicleIid,
                        crewSourceInstanceIds = sourceIids,
                        isCreature = card.isCreature,
                        crewAbilityGrpId = crewAbilityGrpId,
                    ),
                )
            }
        }
        return result
    }

    /** Snapshot which mounts are currently saddled this turn. */
    fun snapshotSaddleState(): List<SaddleSnapshot> {
        val game = game ?: return emptyList()
        val result = mutableListOf<SaddleSnapshot>()
        for (player in game.players) {
            for (card in player.getZone(ZoneType.Battlefield).cards) {
                val saddleSources = card.getSaddledByThisTurn()
                if (saddleSources == null || saddleSources.isEmpty()) continue

                result.add(
                    SaddleSnapshot(
                        mountInstanceId = ids.getOrAlloc(ForgeCardId(card.id)).value,
                        saddleSourceInstanceIds = saddleSources.map { ids.getOrAlloc(ForgeCardId(it.id)).value },
                    ),
                )
            }
        }
        return result
    }

    /**
     * Resolve crew ability grpId for a card via its ability registry.
     * Finds the SpellAbility where `sa.isCrew == true` and resolves its grpId.
     */
    private fun resolveCrewAbilityGrpId(card: Card): Int? {
        val grpId = cardRepository.findGrpIdByName(card.name) ?: return null
        val cardData = cardRepository.findByGrpId(grpId) ?: return null
        val registry = abilityRegistryFor(card, cardData) ?: return null

        val crewSa = card.spellAbilities?.firstOrNull { it.isCrew } ?: return null
        return registry.forSpellAbility(crewSa.id)
    }

    // --- Internal ---

    /**
     * Poll until seat 1's mulligan bridge is in "waiting_keep" state,
     * meaning the engine has dealt the hand and is blocking.
     */
    private fun awaitMulliganReady() {
        val deadline = System.currentTimeMillis() + matchConfig.server.mulliganWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (mulliganBridge(SeatId(1)).pendingPrompt()?.phase == MulliganPhase.WaitingKeep) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("GameBridge: timed out waiting for engine to reach mulligan")
    }
}
