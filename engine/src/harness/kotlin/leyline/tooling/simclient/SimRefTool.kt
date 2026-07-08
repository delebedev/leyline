package leyline.tooling.simclient

import forge.LobbyPlayer
import forge.ai.PlayerControllerAi
import forge.card.ColorSet
import forge.card.ICardFace
import forge.game.Game
import forge.game.GameEntity
import forge.game.GameObject
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.card.CardCollectionView
import forge.game.card.CardState
import forge.game.card.CounterType
import forge.game.combat.Combat
import forge.game.cost.CostPart
import forge.game.cost.CostPartWithList
import forge.game.keyword.KeywordInterface
import forge.game.player.DelayedReveal
import forge.game.player.Player
import forge.game.player.PlayerActionConfirmMode
import forge.game.player.PlayerController.BinaryChoiceType
import forge.game.replacement.ReplacementEffect
import forge.game.spellability.AbilitySub
import forge.game.spellability.OptionalCostValue
import forge.game.spellability.SpellAbility
import forge.game.spellability.SpellAbilityStackInstance
import forge.game.spellability.TargetChoices
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import forge.util.collect.FCollectionView
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.AiConfig
import leyline.config.MatchConfig
import leyline.game.bundle.MessageCounter
import leyline.game.data.CardRepository
import leyline.game.data.ExposedCardRepository
import leyline.game.generator.PuzzleSource
import leyline.game.state.GameBridge
import leyline.tooling.headless.TestCardRegistry
import org.apache.commons.lang3.tuple.Pair
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.function.Predicate

fun main(args: Array<String>) {
    val exitCode = SimRefMain.run(args)
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}

object SimRefMain {
    fun run(args: Array<String>): Int {
        val config = SimClientConfig.parse(args.toList(), System.getenv()) ?: return 0
        SimRefRunner(config).run()
        return 0
    }
}

class SimRefRunner(
    private val config: SimClientConfig,
) {
    private val resolvedCardDbPath by lazy { resolveSimClientCardDbPath(config) }

    private val cardRepo: CardRepository by lazy {
        val path = requireNotNull(resolvedCardDbPath) { "Card database not found; set LEYLINE_CARD_DB or --card-db" }
        val file = validateSimClientCardDbFile(path)
        ExposedCardRepository(Database.connect("jdbc:sqlite:${file.absolutePath}", "org.sqlite.JDBC"))
    }

    fun run() {
        config.outDir.mkdirs()
        val rows = expandSimClientRows(config)
        if (rows.any { it.useCardDb } || resolvedCardDbPath != null) {
            require(resolvedCardDbPath != null) { "Card database not found; set LEYLINE_CARD_DB or --card-db for deck-file rows" }
        }

        println("=== sim-ref: ${rows.size} row(s) out=${config.outDir} ===")
        val summaries = mutableListOf<SimRefRowSummary>()
        for (row in rows) {
            val summary =
                when (row) {
                    is DeckSimClientRow -> runDeckRow(row)
                    is PuzzleSimClientRow -> runPuzzleRow(row)
                }
            summaries += summary
            println(
                "[${row.runLabel} s=${row.seed}] ${summary.durationMs}ms turn=${summary.turn} " +
                    "reason=${summary.completionReason} gameOver=${summary.gameOver} callbacks=${summary.totalCallbacks}",
            )
        }
        File(config.outDir, "simref-summary.json").writeText(simRefSummaryJson(summaries))
    }

    private fun runDeckRow(row: DeckSimClientRow): SimRefRowSummary {
        GameBootstrap.initializeCardDatabase(quiet = true)
        val useCardDb = row.useCardDb || resolvedCardDbPath != null
        if (!useCardDb) {
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureDeckRegistered(row.deckList)
            row.opponentDeckList?.let(TestCardRegistry::ensureDeckRegistered)
        }

        val ledger = SimRefDecisionLedger()
        val startedAt = System.nanoTime()
        val logTap = GameLogCollector().apply { start() }
        val bridge =
            GameBridge(
                bridgeTimeoutMs = 5_000L,
                promptFailsafeMs = 5_000L,
                matchConfig = MatchConfig(ai = AiConfig(speed = 0.0)),
                messageCounter = MessageCounter(),
                cardRepository = if (useCardDb) cardRepo else TestCardRegistry.repo,
            )
        var turn = 0
        var gameOver = false
        var completionReason = "exception"
        var exceptionMessage: String? = null
        var exceptionStackTop: String? = null
        var outcome = SimRefFinalOutcome()
        var collectedLogs = CollectedLogs(emptyMap(), emptyMap(), emptyList())
        try {
            bridge.startAiVsAi(
                seed = row.seed,
                deckList1 = row.deckList,
                deckList2 = row.opponentDeckList ?: row.deckList,
                aiControllerFactory = { game, player ->
                    TappingPlayerControllerAi(
                        game = game,
                        player = player,
                        lobbyPlayer = player.lobbyPlayer,
                        ledger = ledger,
                        seatOf = { bridge.seatOf(it)?.value ?: 0 },
                    )
                },
            )
            val game = checkNotNull(bridge.getGame()) { "sim-ref game missing after start" }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.gameTimeoutSeconds)
            while (!game.isGameOver && game.phaseHandler.turn <= config.maxTurns && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
            }
            turn = game.phaseHandler.turn
            gameOver = game.isGameOver
            outcome = finalOutcome(game)
            completionReason =
                when {
                    gameOver -> "natural"
                    System.nanoTime() >= deadline -> "wall-timeout"
                    turn > config.maxTurns -> "max-turns"
                    else -> "incomplete"
                }
        } catch (t: Throwable) {
            exceptionMessage = "${t::class.java.name}: ${t.message.orEmpty()}".trimEnd()
            exceptionStackTop = t.stackTrace.firstOrNull()?.toString()
        } finally {
            runCatching { bridge.shutdown() }
            collectedLogs = logTap.stopAndDrain()
        }
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        // The game thread can append ledger entries between loop exit and shutdown;
        // cut at the turn cap so turn-capped rows stay deterministic across reruns.
        val decisions = ledger.snapshot().filter { it.turn <= config.maxTurns }
        File(
            config.outDir,
            "${row.tag}.refdecisions.json",
        ).writeText(
            simRefDecisionsJson(
                SimRefDecisionReport(
                    row = row,
                    decisions = decisions,
                    durationMs = durationMs,
                    gameOver = gameOver,
                    turn = turn,
                    completionReason = completionReason,
                    exceptionMessage = exceptionMessage,
                    exceptionStackTop = exceptionStackTop,
                    outcome = outcome,
                    logs = collectedLogs,
                ),
            ),
        )
        return SimRefRowSummary(row.tag, row.runLabel, row.seed, durationMs, turn, gameOver, decisions.size, completionReason, outcome)
    }

    private fun runPuzzleRow(row: PuzzleSimClientRow): SimRefRowSummary {
        GameBootstrap.initializeCardDatabase(quiet = true)
        val useCardDb = row.useCardDb || resolvedCardDbPath != null
        if (!useCardDb) TestCardRegistry.ensureRegistered()

        val ledger = SimRefDecisionLedger()
        val startedAt = System.nanoTime()
        val logTap = GameLogCollector().apply { start() }
        val bridge =
            GameBridge(
                bridgeTimeoutMs = 5_000L,
                promptFailsafeMs = 5_000L,
                matchConfig = MatchConfig(ai = AiConfig(speed = 0.0)),
                messageCounter = MessageCounter(),
                cardRepository = if (useCardDb) cardRepo else TestCardRegistry.repo,
            )
        var turn = 0
        var gameOver = false
        var completionReason = "exception"
        var exceptionMessage: String? = null
        var exceptionStackTop: String? = null
        var outcome = SimRefFinalOutcome()
        var collectedLogs = CollectedLogs(emptyMap(), emptyMap(), emptyList())
        try {
            bridge.startPuzzle(
                PuzzleSource.loadFromText(row.puzzleText, row.name),
                aiControllerFactory = { game, player ->
                    TappingPlayerControllerAi(
                        game = game,
                        player = player,
                        lobbyPlayer = player.lobbyPlayer,
                        ledger = ledger,
                        seatOf = { bridge.seatOf(it)?.value ?: 0 },
                    )
                },
            )
            if (!useCardDb) TestCardRegistry.registerPuzzleCards(bridge.getGame() ?: error("sim-ref puzzle game missing after start"))
            val game = checkNotNull(bridge.getGame()) { "sim-ref puzzle game missing after start" }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.gameTimeoutSeconds)
            while (!game.isGameOver && game.phaseHandler.turn <= config.maxTurns && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
            }
            turn = game.phaseHandler.turn
            gameOver = game.isGameOver
            outcome = finalOutcome(game)
            completionReason =
                when {
                    gameOver -> "natural"
                    System.nanoTime() >= deadline -> "wall-timeout"
                    turn > config.maxTurns -> "max-turns"
                    else -> "incomplete"
                }
        } catch (t: Throwable) {
            exceptionMessage = "${t::class.java.name}: ${t.message.orEmpty()}".trimEnd()
            exceptionStackTop = t.stackTrace.firstOrNull()?.toString()
        } finally {
            runCatching { bridge.shutdown() }
            collectedLogs = logTap.stopAndDrain()
        }
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val decisions = ledger.snapshot().filter { it.turn <= config.maxTurns }
        File(
            config.outDir,
            "${row.tag}.refdecisions.json",
        ).writeText(
            simRefDecisionsJson(
                SimRefDecisionReport(
                    row = row,
                    decisions = decisions,
                    durationMs = durationMs,
                    gameOver = gameOver,
                    turn = turn,
                    completionReason = completionReason,
                    exceptionMessage = exceptionMessage,
                    exceptionStackTop = exceptionStackTop,
                    outcome = outcome,
                    logs = collectedLogs,
                ),
            ),
        )
        return SimRefRowSummary(row.tag, row.runLabel, row.seed, durationMs, turn, gameOver, decisions.size, completionReason, outcome)
    }

    private fun finalOutcome(game: Game): SimRefFinalOutcome {
        val lifeBySeat =
            game.players
                .mapIndexed { index, player -> (index + 1).toString() to player.life }
                .toMap()
        val winnerSeat =
            game.players
                .indexOfFirst { it.getOutcome()?.hasWon() == true }
                .takeIf { it >= 0 }
                ?.plus(1)
        val loserSeat =
            winnerSeat
                ?.let { winner -> game.players.indices.firstOrNull { it + 1 != winner } }
                ?.plus(1)
        return SimRefFinalOutcome(winnerSeat, loserSeat, lifeBySeat)
    }
}

data class SimRefDecision(
    val index: Int,
    val seat: Int,
    val turn: Int,
    val phase: String,
    val callback: String,
    val source: String?,
    val api: String?,
    val prompt: String?,
)

data class SimRefDecisionContext(
    val source: String? = null,
    val api: String? = null,
    val prompt: String? = null,
)

class SimRefDecisionLedger {
    private val decisions = mutableListOf<SimRefDecision>()

    @Synchronized
    fun record(
        player: Player,
        callback: String,
        seatOf: (Player) -> Int,
        context: SimRefDecisionContext = SimRefDecisionContext(),
    ) {
        val game = player.game
        decisions +=
            SimRefDecision(
                index = decisions.size,
                seat = seatOf(player),
                turn = game.phaseHandler.turn,
                phase = game.phaseHandler.phase.name,
                callback = callback,
                source = context.source,
                api = context.api,
                prompt = context.prompt,
            )
    }

    @Synchronized
    fun snapshot(): List<SimRefDecision> = decisions.toList()
}

private class TappingPlayerControllerAi(
    game: Game,
    player: Player,
    lobbyPlayer: LobbyPlayer,
    private val ledger: SimRefDecisionLedger,
    private val seatOf: (Player) -> Int,
) : PlayerControllerAi(game, player, lobbyPlayer) {
    override fun chooseSpellAbilityToPlay(): MutableList<SpellAbility>? =
        tap("chooseSpellAbilityToPlay") { super.chooseSpellAbilityToPlay() }

    override fun playChosenSpellAbility(sa: SpellAbility): Boolean = tap("playChosenSpellAbility") { super.playChosenSpellAbility(sa) }

    override fun assignCombatDamage(
        attacker: Card,
        blockers: CardCollectionView,
        remaining: CardCollectionView,
        damageDealt: Int,
        defender: GameEntity,
        overrideOrder: Boolean,
    ): MutableMap<Card, Int> =
        tap("assignCombatDamage", context(source = attacker, prompt = defender.toString())) {
            super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder)
        }

    override fun declareAttackers(
        attacker: Player,
        combat: Combat,
    ) = tap("declareAttackers") { super.declareAttackers(attacker, combat) }

    override fun declareBlockers(
        defender: Player,
        combat: Combat,
    ) = tap("declareBlockers") { super.declareBlockers(defender, combat) }

    override fun chooseModeForAbility(
        sa: SpellAbility,
        possible: MutableList<AbilitySub>,
        min: Int,
        num: Int,
        allowRepeat: Boolean,
    ): MutableList<AbilitySub> =
        tap("chooseModeForAbility", context(sa)) { super.chooseModeForAbility(sa, possible, min, num, allowRepeat) }

    override fun chooseCardsForEffect(
        sourceList: CardCollectionView,
        sa: SpellAbility,
        title: String,
        min: Int,
        max: Int,
        isOptional: Boolean,
        params: MutableMap<String, Any>?,
    ): CardCollectionView =
        tap("chooseCardsForEffect", context(sa, title)) {
            super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params)
        }

    override fun <T : GameEntity> chooseEntitiesForEffect(
        optionList: FCollectionView<T>,
        min: Int,
        max: Int,
        delayedReveal: DelayedReveal,
        sa: SpellAbility,
        title: String,
        targetedPlayer: Player?,
        params: MutableMap<String, Any>?,
    ): MutableList<T> =
        tap("chooseEntitiesForEffect", context(sa, title)) {
            super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params)
        }

    override fun chooseSpellAbilitiesForEffect(
        spells: MutableList<SpellAbility>,
        sa: SpellAbility,
        title: String,
        num: Int,
        params: MutableMap<String, Any>?,
    ): MutableList<SpellAbility> =
        tap("chooseSpellAbilitiesForEffect", context(sa, title)) {
            super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params)
        }

    override fun chooseSingleSpellForEffect(
        spells: MutableList<SpellAbility>,
        sa: SpellAbility,
        title: String,
        params: MutableMap<String, Any>?,
    ): SpellAbility? =
        tap("chooseSingleSpellForEffect", context(sa, title)) {
            super.chooseSingleSpellForEffect(spells, sa, title, params)
        }

    override fun <T : GameEntity> chooseSingleEntityForEffect(
        optionList: FCollectionView<T>,
        delayedReveal: DelayedReveal,
        sa: SpellAbility,
        title: String,
        isOptional: Boolean,
        relatedPlayer: Player?,
        params: MutableMap<String, Any>?,
    ): T =
        tap("chooseSingleEntityForEffect", context(sa, title)) {
            super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params)
        }

    override fun confirmAction(
        sa: SpellAbility?,
        mode: PlayerActionConfirmMode?,
        message: String?,
        options: MutableList<String>?,
        cardToShow: Card?,
        params: MutableMap<String, Any>?,
    ): Boolean =
        tap("confirmAction", context(sa, message, cardToShow)) { super.confirmAction(sa, mode, message, options, cardToShow, params) }

    override fun confirmBidAction(
        sa: SpellAbility,
        mode: PlayerActionConfirmMode,
        string: String,
        bid: Int,
        winner: Player,
    ): Boolean = tap("confirmBidAction", context(sa, string)) { super.confirmBidAction(sa, mode, string, bid, winner) }

    override fun confirmStaticApplication(
        hostCard: Card,
        mode: PlayerActionConfirmMode,
        message: String,
        logic: String,
    ): Boolean =
        tap("confirmStaticApplication", context(source = hostCard, prompt = message)) {
            super.confirmStaticApplication(hostCard, mode, message, logic)
        }

    override fun confirmTrigger(sa: WrappedAbility): Boolean = tap("confirmTrigger", context(sa)) { super.confirmTrigger(sa) }

    override fun confirmPayment(
        costPart: CostPart,
        prompt: String,
        sa: SpellAbility,
    ): Boolean = tap("confirmPayment", context(sa, prompt)) { super.confirmPayment(costPart, prompt, sa) }

    override fun confirmReplacementEffect(
        replacementEffect: ReplacementEffect,
        effectSA: SpellAbility,
        affected: GameEntity,
        question: String,
    ): Boolean =
        tap("confirmReplacementEffect", context(effectSA, question)) {
            super.confirmReplacementEffect(replacementEffect, effectSA, affected, question)
        }

    override fun chooseCardsToDiscardFrom(
        playerDiscard: Player,
        sa: SpellAbility,
        validCards: CardCollection,
        min: Int,
        max: Int,
        visibleToChooser: CardCollectionView,
    ): CardCollection =
        tap("chooseCardsToDiscardFrom", context(sa, "discard")) {
            super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser)
        }

    override fun choosePermanentsToSacrifice(
        sa: SpellAbility,
        min: Int,
        max: Int,
        validTargets: CardCollectionView,
        message: String,
    ): CardCollectionView =
        tap("choosePermanentsToSacrifice", context(sa, message)) {
            super.choosePermanentsToSacrifice(sa, min, max, validTargets, message)
        }

    override fun choosePermanentsToDestroy(
        sa: SpellAbility,
        min: Int,
        max: Int,
        validTargets: CardCollectionView,
        message: String,
    ): CardCollectionView =
        tap("choosePermanentsToDestroy", context(sa, message)) {
            super.choosePermanentsToDestroy(sa, min, max, validTargets, message)
        }

    override fun chooseOptionalCosts(
        choosen: SpellAbility,
        optionalCostValues: MutableList<OptionalCostValue>,
    ): MutableList<OptionalCostValue> =
        tap("chooseOptionalCosts", context(choosen)) { super.chooseOptionalCosts(choosen, optionalCostValues) }

    override fun chooseCardsToDiscardToMaximumHandSize(numDiscard: Int): CardCollectionView =
        tap("chooseCardsToDiscardToMaximumHandSize", SimRefDecisionContext(prompt = numDiscard.toString())) {
            super.chooseCardsToDiscardToMaximumHandSize(numDiscard)
        }

    override fun chooseCardsToRevealFromHand(
        min: Int,
        max: Int,
        valid: CardCollectionView,
    ): CardCollectionView =
        tap("chooseCardsToRevealFromHand", SimRefDecisionContext(prompt = "$min..$max")) {
            super.chooseCardsToRevealFromHand(min, max, valid)
        }

    override fun chooseNumber(
        sa: SpellAbility,
        title: String,
        min: Int,
        max: Int,
    ): Int = tap("chooseNumber", context(sa, title)) { super.chooseNumber(sa, title, min, max) }

    override fun chooseNumber(
        sa: SpellAbility,
        string: String,
        min: Int,
        max: Int,
        params: MutableMap<String, Any>?,
    ): Int = tap("chooseNumberWithParams", context(sa, string)) { super.chooseNumber(sa, string, min, max, params) }

    override fun chooseNumber(
        sa: SpellAbility,
        title: String,
        options: MutableList<Int>,
        relatedPlayer: Player?,
    ): Int = tap("chooseNumberFromOptions", context(sa, title)) { super.chooseNumber(sa, title, options, relatedPlayer) }

    override fun chooseFlipResult(
        sa: SpellAbility,
        flipper: Player,
        call: Boolean,
    ): Boolean = tap("chooseFlipResult", context(sa)) { super.chooseFlipResult(sa, flipper, call) }

    override fun chooseTarget(
        saSrc: SpellAbility,
        allTargets: MutableList<Pair<SpellAbilityStackInstance, GameObject>>,
    ): Pair<SpellAbilityStackInstance, GameObject> = tap("chooseTarget", context(saSrc)) { super.chooseTarget(saSrc, allTargets) }

    override fun chooseBinary(
        sa: SpellAbility,
        question: String,
        kindOfChoice: BinaryChoiceType,
        defaultVal: Boolean?,
    ): Boolean = tap("chooseBinary", context(sa, question)) { super.chooseBinary(sa, question, kindOfChoice, defaultVal) }

    override fun chooseBinary(
        sa: SpellAbility,
        question: String,
        kindOfChoice: BinaryChoiceType,
        params: MutableMap<String, Any>?,
    ): Boolean = tap("chooseBinaryWithParams", context(sa, question)) { super.chooseBinary(sa, question, kindOfChoice, params) }

    override fun chooseColorAllowColorless(
        message: String,
        card: Card,
        colors: ColorSet,
    ): Byte =
        tap("chooseColorAllowColorless", context(source = card, prompt = message)) {
            super.chooseColorAllowColorless(message, card, colors)
        }

    override fun chooseColor(
        message: String,
        sa: SpellAbility,
        colors: ColorSet,
    ): Byte = tap("chooseColor", context(sa, message)) { super.chooseColor(message, sa, colors) }

    override fun chooseColors(
        message: String,
        sa: SpellAbility,
        min: Int,
        max: Int,
        options: ColorSet,
    ): ColorSet = tap("chooseColors", context(sa, message)) { super.chooseColors(message, sa, min, max, options) }

    override fun chooseCounterType(
        options: MutableList<CounterType>,
        sa: SpellAbility,
        prompt: String,
        params: MutableMap<String, Any>?,
    ): CounterType = tap("chooseCounterType", context(sa, prompt)) { super.chooseCounterType(options, sa, prompt, params) }

    override fun chooseKeywordForPump(
        options: MutableList<String>,
        sa: SpellAbility,
        prompt: String,
        tgtCard: Card?,
    ): String = tap("chooseKeywordForPump", context(sa, prompt, tgtCard)) { super.chooseKeywordForPump(options, sa, prompt, tgtCard) }

    override fun chooseCardsForCost(
        optionList: CardCollectionView,
        sa: SpellAbility,
        cpl: CostPartWithList,
        amount: Int,
        isOptional: Boolean,
        prompt: String,
    ): CardCollectionView =
        tap("chooseCardsForCost", context(sa, prompt)) {
            super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt)
        }

    override fun orderBlockers(
        attacker: Card,
        blockers: CardCollection,
    ): CardCollection = tap("orderBlockers", context(source = attacker)) { super.orderBlockers(attacker, blockers) }

    override fun orderBlocker(
        attacker: Card,
        blocker: Card,
        oldBlockers: CardCollection,
    ): CardCollection =
        tap("orderBlocker", context(source = attacker, prompt = blocker.name)) {
            super.orderBlocker(attacker, blocker, oldBlockers)
        }

    override fun orderAttackers(
        blocker: Card,
        attackers: CardCollection,
    ): CardCollection = tap("orderAttackers", context(source = blocker)) { super.orderAttackers(blocker, attackers) }

    override fun orderMoveToZoneList(
        cards: CardCollectionView,
        destinationZone: ZoneType,
        source: SpellAbility,
    ): CardCollectionView =
        tap("orderMoveToZoneList", context(source, destinationZone.name)) {
            super.orderMoveToZoneList(cards, destinationZone, source)
        }

    override fun orderSimultaneousSa(activePlayerSAs: MutableList<SpellAbility>): MutableList<SpellAbility> =
        tap(
            "orderSimultaneousSa",
            SimRefDecisionContext(prompt = activePlayerSAs.size.toString()),
        ) { super.orderSimultaneousSa(activePlayerSAs) }

    override fun chooseTargetsFor(currentAbility: SpellAbility): Boolean =
        tap("chooseTargetsFor", context(currentAbility)) {
            super.chooseTargetsFor(currentAbility)
        }

    override fun chooseNewTargetsFor(
        ability: SpellAbility,
        filter: Predicate<GameObject>,
        optional: Boolean,
    ): TargetChoices? = tap("chooseNewTargetsFor", context(ability)) { super.chooseNewTargetsFor(ability, filter, optional) }

    override fun chooseCardsPile(
        sa: SpellAbility,
        pile1: CardCollectionView,
        pile2: CardCollectionView,
        faceUp: String,
    ): Boolean = tap("chooseCardsPile", context(sa, faceUp)) { super.chooseCardsPile(sa, pile1, pile2, faceUp) }

    override fun chooseSingleCardForZoneChange(
        destination: ZoneType,
        origin: MutableList<ZoneType>,
        sa: SpellAbility,
        fetchList: CardCollection,
        delayedReveal: DelayedReveal,
        selectPrompt: String,
        isOptional: Boolean,
        decider: Player,
    ): Card? =
        tap("chooseSingleCardForZoneChange", context(sa, selectPrompt)) {
            super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider)
        }

    override fun chooseCardsForZoneChange(
        destination: ZoneType,
        origin: MutableList<ZoneType>,
        sa: SpellAbility,
        fetchList: CardCollection,
        min: Int,
        max: Int,
        delayedReveal: DelayedReveal,
        selectPrompt: String,
        decider: Player,
    ): MutableList<Card>? =
        tap("chooseCardsForZoneChange", context(sa, selectPrompt)) {
            super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider)
        }

    override fun chooseSingleCardFace(
        sa: SpellAbility,
        faces: MutableList<ICardFace>,
        message: String,
    ): ICardFace = tap("chooseSingleCardFace", context(sa, message)) { super.chooseSingleCardFace(sa, faces, message) }

    override fun chooseSingleCardState(
        sa: SpellAbility,
        states: MutableList<CardState>,
        message: String,
        params: MutableMap<String, Any>?,
    ): CardState = tap("chooseSingleCardState", context(sa, message)) { super.chooseSingleCardState(sa, states, message, params) }

    override fun chooseCardsForSplice(
        sa: SpellAbility,
        cards: MutableList<Card>,
    ): MutableList<Card> = tap("chooseCardsForSplice", context(sa)) { super.chooseCardsForSplice(sa, cards) }

    override fun chooseNumberForKeywordCost(
        sa: SpellAbility,
        cost: forge.game.cost.Cost,
        keyword: KeywordInterface,
        prompt: String,
        max: Int,
    ): Int = tap("chooseNumberForKeywordCost", context(sa, prompt)) { super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max) }

    override fun chooseNumberForCostReduction(
        sa: SpellAbility,
        min: Int,
        max: Int,
    ): Int = tap("chooseNumberForCostReduction", context(sa)) { super.chooseNumberForCostReduction(sa, min, max) }

    override fun orderCosts(costs: MutableList<CostPart>): MutableList<CostPart> = tap("orderCosts") { super.orderCosts(costs) }

    private fun <T> tap(
        callback: String,
        context: SimRefDecisionContext = SimRefDecisionContext(),
        block: () -> T,
    ): T {
        ledger.record(player, callback, seatOf, context)
        return block()
    }

    private fun context(
        sa: SpellAbility?,
        prompt: String? = null,
        card: Card? = null,
    ): SimRefDecisionContext =
        SimRefDecisionContext(
            source = (card ?: sa?.hostCard)?.name,
            api = sa?.api?.name,
            prompt = prompt?.takeIf { it.isNotBlank() },
        )

    private fun context(
        source: Card? = null,
        prompt: String? = null,
    ): SimRefDecisionContext =
        SimRefDecisionContext(
            source = source?.name,
            prompt = prompt?.takeIf { it.isNotBlank() },
        )
}

private data class SimRefRowSummary(
    val tag: String,
    val deck: String,
    val seed: Long,
    val durationMs: Long,
    val turn: Int,
    val gameOver: Boolean,
    val totalCallbacks: Int,
    val completionReason: String,
    val outcome: SimRefFinalOutcome,
)

internal data class SimRefFinalOutcome(
    val winnerSeat: Int? = null,
    val loserSeat: Int? = null,
    val lifeBySeat: Map<String, Int> = emptyMap(),
)

internal data class SimRefDecisionReport(
    val row: SimClientRow,
    val decisions: List<SimRefDecision>,
    val durationMs: Long,
    val gameOver: Boolean,
    val turn: Int,
    val completionReason: String,
    val exceptionMessage: String?,
    val exceptionStackTop: String?,
    val outcome: SimRefFinalOutcome,
    val logs: CollectedLogs,
)

internal fun simRefDecisionsJson(report: SimRefDecisionReport): String {
    val counts =
        report.decisions
            .groupingBy { it.callback }
            .eachCount()
            .toSortedMap()
    return buildString {
        append('{')
        append("\"schemaVersion\":1,")
        append("\"deck\":${simJsonString(report.row.runLabel)},")
        report.row.opponentRunLabel?.let { append("\"opponentDeck\":${simJsonString(it)},") }
        append("\"seed\":${report.row.seed},")
        append("\"generatedAt\":${simJsonString(LocalDateTime.now().toString())},")
        append("\"durationMs\":${report.durationMs},")
        append("\"turn\":${report.turn},")
        append("\"gameOver\":${report.gameOver},")
        append("\"winnerSeat\":${report.outcome.winnerSeat ?: "null"},")
        append("\"loserSeat\":${report.outcome.loserSeat ?: "null"},")
        append("\"finalLifeBySeat\":${mapToJson(report.outcome.lifeBySeat)},")
        append("\"completionReason\":${simJsonString(report.completionReason)},")
        append("\"exceptionMessage\":${nullableSimJsonString(report.exceptionMessage)},")
        append("\"exceptionStackTop\":${nullableSimJsonString(report.exceptionStackTop)},")
        append("\"warnsByLogger\":${mapToJson(report.logs.warnsByLogger)},")
        append("\"errorsByType\":${mapToJson(report.logs.errorsByType)},")
        append("\"errorSamples\":${stringsToJson(report.logs.errorSamples)},")
        append("\"callbackCounts\":${mapToJson(counts)},")
        append("\"decisions\":")
        append(
            report.decisions.joinToString(",", "[", "]") { d ->
                "{\"index\":${d.index},\"seat\":${d.seat},\"turn\":${d.turn}," +
                    "\"phase\":${simJsonString(d.phase)},\"callback\":${simJsonString(d.callback)}," +
                    "\"source\":${nullableSimJsonString(d.source)},\"api\":${nullableSimJsonString(d.api)}," +
                    "\"prompt\":${nullableSimJsonString(d.prompt)}}"
            },
        )
        append('}')
    }
}

private fun nullableSimJsonString(value: String?): String = value?.let(::simJsonString) ?: "null"

private fun stringsToJson(values: List<String>): String = values.joinToString(",", "[", "]") { simJsonString(it) }

private fun simRefSummaryJson(summaries: List<SimRefRowSummary>): String =
    summaries.joinToString(",", "[", "]") { s ->
        "{\"tag\":${simJsonString(s.tag)},\"deck\":${simJsonString(s.deck)},\"seed\":${s.seed}," +
            "\"durationMs\":${s.durationMs},\"turn\":${s.turn},\"gameOver\":${s.gameOver}," +
            "\"winnerSeat\":${s.outcome.winnerSeat ?: "null"},\"loserSeat\":${s.outcome.loserSeat ?: "null"}," +
            "\"finalLifeBySeat\":${mapToJson(s.outcome.lifeBySeat)}," +
            "\"completionReason\":${simJsonString(s.completionReason)},\"totalCallbacks\":${s.totalCallbacks}}"
    }
