package leyline.testkit

import forge.game.Game
import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import leyline.IntegrationTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.bundle.InvariantSelection
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GroupReq
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.OrderReq
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

/**
 * Base class for session-tier interaction tests (MatchFlowHarness).
 *
 * Parallel to [BoardTest] (board/bridge tier). Never mix in one file.
 * Auto-wires IntegrationTag and harness lifecycle (shutdown after each test).
 *
 * ```
 * class FooSessionTest : SessionTest({
 *     test("ability resolves and deals damage") {
 *         startPuzzle("""
 *             [metadata]
 *             ...
 *             [state]
 *             ...
 *         """.trimIndent())
 *
 *         activateAbility("Card Name")
 *         selectTargets(listOf(OPPONENT_SEAT))
 *         passUntilResolved()
 *         ai.life shouldBe 4
 *     }
 * })
 * ```
 */
// `abstract` keeps Kotest's auto-discovery from trying to instantiate the base
// class directly (no zero-arg constructor — only the `body` lambda variant).
@Suppress("UnnecessaryAbstractClass")
abstract class SessionTest(
    body: SessionTest.() -> Unit,
) : FunSpec() {
    companion object {
        /** Seat ID for the human player (tests always use seat 1). */
        const val HUMAN_SEAT = 1

        /** Seat ID for the AI / opponent. */
        const val OPPONENT_SEAT = 2
    }

    private var _harness: MatchFlowHarness? = null

    /** Current harness — available after [startPuzzle] or [startGame]. */
    val harness: MatchFlowHarness get() = _harness ?: error("Call startPuzzle() or startGame() first")

    /** Human player — cached at setup time, safe to use after game actions. */
    lateinit var human: Player
        private set

    /** AI player — cached at setup time, safe to use after game actions. */
    lateinit var ai: Player
        private set

    init {
        tags(IntegrationTag)
        afterEach {
            _harness?.shutdown()
            _harness = null
        }
        body()
    }

    private fun cachePlayerRefs() {
        human = harness.bridge.getPlayer(SeatId(1))!!
        ai = harness.bridge.getPlayer(SeatId(2))!!
    }

    // --- Setup ---

    /**
     * Start a puzzle game. [state] is the `[state]` block body only; metadata is
     * synthesized from [name]/[goal]/[turns]. For puzzles needing non-default
     * metadata keys, use [startPuzzleRaw].
     */
    fun startPuzzle(
        state: String,
        name: String = "test",
        goal: String = "Win",
        turns: Int = 1,
        seed: Long = 42L,
        validating: Boolean = false,
        aiScript: List<leyline.tooling.headless.ScriptedAction>? = null,
        validation: InvariantSelection = MatchFlowHarness.defaultValidation(validating),
    ): MatchFlowHarness = startPuzzleRaw(buildPuzzleText(state, name, goal, turns), seed, validating, aiScript, validation)

    /** Start a puzzle from full `.pzl` text (metadata + state). Escape hatch. */
    fun startPuzzleRaw(
        puzzleText: String,
        seed: Long = 42L,
        validating: Boolean = false,
        aiScript: List<leyline.tooling.headless.ScriptedAction>? = null,
        validation: InvariantSelection = MatchFlowHarness.defaultValidation(validating),
    ): MatchFlowHarness {
        val h = MatchFlowHarness(seed = seed, validating = validating, validation = validation)
        _harness = h
        h.connectAndKeepPuzzleText(puzzleText, aiScript)
        cachePlayerRefs()
        return h
    }

    private fun buildPuzzleText(
        state: String,
        name: String,
        goal: String,
        turns: Int,
    ): String =
        buildString {
            appendLine("[metadata]")
            appendLine("Name:$name")
            appendLine("Goal:$goal")
            appendLine("Turns:$turns")
            appendLine()
            appendLine("[state]")
            append(state.trimIndent())
        }

    /** Start a normal game (mulligan + keep). */
    fun startGame(
        seed: Long = 42L,
        deckList: String? = null,
        validating: Boolean = true,
        aiScript: List<leyline.tooling.headless.ScriptedAction>? = null,
        validation: InvariantSelection = MatchFlowHarness.defaultValidation(validating),
    ): MatchFlowHarness {
        val h = MatchFlowHarness(seed = seed, deckList = deckList, validating = validating, validation = validation)
        _harness = h
        h.connectAndKeep(aiScript)
        cachePlayerRefs()
        return h
    }

    /** Start a puzzle game from a classpath resource (e.g. `puzzles/bolt-face.pzl`). */
    fun startPuzzleFile(
        resourcePath: String,
        seed: Long = 42L,
        validating: Boolean = false,
        validation: InvariantSelection = MatchFlowHarness.defaultValidation(validating),
    ): MatchFlowHarness {
        val h = MatchFlowHarness(seed = seed, validating = validating, validation = validation)
        _harness = h
        h.connectAndKeepPuzzle(resourcePath)
        cachePlayerRefs()
        return h
    }

    // --- Game actions ---

    fun activateAbility(
        cardName: String,
        abilityIndex: Int = 0,
    ) = harness.activateAbility(cardName, abilityIndex)

    fun activateAbilityFromHand(
        cardName: String,
        abilityIndex: Int = 0,
    ) = harness.activateAbilityFromHand(cardName, abilityIndex)

    fun activateAbilityFromGraveyard(
        cardName: String,
        abilityIndex: Int = 0,
    ) = harness.activateAbilityFromGraveyard(cardName, abilityIndex)

    fun selectTargets(targetInstanceIds: List<Int>) = harness.selectTargets(targetInstanceIds)

    fun selectTargetsIterative(targetInstanceIds: List<Int>) = harness.selectTargetsIterative(targetInstanceIds)

    fun submitTargets() = harness.submitTargets()

    fun cancelAction() = harness.cancelAction()

    fun castCreature() = harness.castCreature()

    fun activateMana(
        cardName: String,
        abilityIndex: Int = 0,
        selectedColor: ManaColor? = null,
    ) = harness.activateMana(cardName, abilityIndex, selectedColor)

    fun humanBattlefieldCreatures(): List<Pair<Int, String>> = harness.humanBattlefieldCreatures()

    fun passPriority() = harness.passPriority()

    fun passUntil(
        maxPasses: Int = 20,
        stopWhen: () -> Boolean,
    ) = harness.passUntil(maxPasses) { stopWhen() }

    fun passUntilTurn(
        targetTurn: Int,
        maxPasses: Int = 30,
    ) = harness.passUntilTurn(targetTurn, maxPasses)

    fun installScriptedAi(script: List<leyline.tooling.headless.ScriptedAction>) = harness.installScriptedAi(script)

    fun playLand(name: String? = null): Boolean = harness.playLand(name)

    fun isAiTurn(): Boolean = harness.isAiTurn()

    fun triggerAutoPass() = harness.triggerAutoPass()

    /**
     * Pass priority until the stack is empty. Use after cast + target to resolve.
     * Always passes at least once (the stack may already be empty before the
     * action's effect lands — auto-pass can resolve during selectTargets/drainSink).
     */
    fun passUntilResolved(maxPasses: Int = 10) {
        repeat(maxPasses) {
            if (harness.isGameOver()) return
            passPriority()
            if (game().stackZone.size() == 0) return
        }
    }

    fun passThroughCombat(
        startTurn: Int = harness.turn(),
        maxPasses: Int = 15,
    ) = harness.passThroughCombat(startTurn, maxPasses)

    // --- Combat: declare attackers ---

    fun declareAttackers(attackerInstanceIds: List<Int>) = harness.declareAttackers(attackerInstanceIds)

    fun declareNoAttackers() = harness.declareNoAttackers()

    fun declareAllAttackers() = harness.declareAllAttackers()

    fun submitAttackers() = harness.submitAttackers()

    fun toggleAttackers(
        attackerInstanceIds: List<Int>,
        attackerAlternatives: Map<Int, Int> = emptyMap(),
    ): List<GREToClientMessage> = harness.toggleAttackers(attackerInstanceIds, attackerAlternatives)

    // --- Combat: declare blockers ---

    fun declareBlockers(assignments: Map<Int, Int>) = harness.declareBlockers(assignments)

    fun declareNoBlockers() = harness.declareNoBlockers()

    fun submitBlockers() = harness.submitBlockers()

    fun toggleBlockers(assignments: Map<Int, Int>): List<GREToClientMessage> = harness.toggleBlockers(assignments)

    fun deselectBlocker(blockerInstanceId: Int): List<GREToClientMessage> = harness.deselectBlocker(blockerInstanceId)

    // --- Combat: assign damage ---

    fun assignDamage(assigners: List<Pair<Int, List<Pair<Int, Int>>>>) = harness.assignDamage(assigners)

    fun castSpellByName(
        cardName: String,
        zone: ZoneType = ZoneType.Hand,
        alternativeGrpId: Int = 0,
    ) = harness.castSpellByName(cardName, zone, alternativeGrpId)

    fun castFromGraveyard(cardName: String) = harness.castFromGraveyard(cardName)

    fun castFromExile(cardName: String) = harness.castFromExile(cardName)

    fun resolveSpell(cardName: String) = harness.resolveSpell(cardName)

    // --- Cast-until-prompt flows ---

    fun castSpellUntilGroupReq(cardName: String): GroupReq = harness.castSpellUntilGroupReq(cardName)

    fun castSpellUntilSelectNReq(cardName: String): SelectNReq = harness.castSpellUntilSelectNReq(cardName)

    fun castSpellUntilOrderReq(cardName: String): OrderReq = harness.castSpellUntilOrderReq(cardName)

    fun castSpellUntilCastingTimeOptionsReq(cardName: String): CastingTimeOptionsReq = harness.castSpellUntilCastingTimeOptionsReq(cardName)

    // --- Prompt responses ---

    fun respondToGroupReq(
        awayInstanceIds: List<Int>,
        allInstanceIds: List<Int>,
    ) = harness.respondToGroupReq(awayInstanceIds, allInstanceIds)

    fun respondToScry(
        bottomInstanceIds: List<Int>,
        allInstanceIds: List<Int>,
    ) = harness.respondToScry(bottomInstanceIds, allInstanceIds)

    fun respondToSelectN(selectedInstanceIds: List<Int>) = harness.respondToSelectN(selectedInstanceIds)

    fun respondToOrder(orderedInstanceIds: List<Int>) = harness.respondToOrder(orderedInstanceIds)

    fun respondToEffectCost(selectedInstanceIds: List<Int>) = harness.respondToEffectCost(selectedInstanceIds)

    fun respondToOptionalCost(ctoId: Int) = harness.respondToOptionalCost(ctoId)
    // --- Message inspection ---

    val allMessages: List<GREToClientMessage> get() = harness.allMessages

    fun messageSnapshot(): Int = harness.messageSnapshot()

    fun messagesSince(snapshot: Int): List<GREToClientMessage> = harness.messagesSince(snapshot)

    fun gameStateMessagesSince(snapshot: Int): List<GameStateMessage> = harness.gameStateMessagesSince(snapshot)

    fun annotationsSince(snapshot: Int): List<AnnotationInfo> = harness.annotationsSince(snapshot)

    /**
     * Snapshot the message stream, run [block], return the slice of messages
     * produced. Reduces `messageSnapshot()` / `messagesSince(snap)` to one
     * line and lets typed prompt expectations replace raw `any { hasFooReq() }`
     * scans. Raw access remains via [MessageSlice.messages].
     */
    fun after(block: () -> Unit): MessageSlice {
        val snap = messageSnapshot()
        block()
        return MessageSlice(messagesSince(snap))
    }

    // --- Convenience: last-of-kind prompt ---

    fun lastSelectNReq(): SelectNReq = harness.allMessages.last { it.hasSelectNReq() }.selectNReq

    fun lastCastingTimeOptionsReq(): CastingTimeOptionsReq =
        harness.allMessages.last { it.hasCastingTimeOptionsReq() }.castingTimeOptionsReq

    fun lastGroupReq(): GroupReq = harness.allMessages.last { it.hasGroupReq() }.groupReq

    // --- Accumulator ---

    fun assertAccumulatorConsistent(context: String) = harness.accumulator.assertConsistent(context)

    // --- Card lookup ---

    /** Resolve instanceId to card name via bridge. Fails clearly if card not found. */
    fun cardName(instanceId: Int): String {
        val cardId =
            harness.bridge.getForgeCardId(InstanceId(instanceId))
                ?: error("No ForgeCardId for instanceId $instanceId")
        val card =
            harness.game().findById(cardId.value)
                ?: error("No card for forgeCardId ${cardId.value}")
        return card.name
    }

    /** Find instanceId for a card by name in a list of candidate instanceIds. */
    fun findInstanceId(
        candidateIds: List<Int>,
        name: String,
    ): Int =
        candidateIds.firstOrNull { cardName(it) == name }
            ?: error("Card '$name' not found in candidates: $candidateIds")

    /**
     * Find instanceId of a card by name in the given [player]'s [zone].
     * Defaults to the human's battlefield.
     */
    fun instanceIdOf(
        cardName: String,
        player: Player = human,
        zone: ZoneType = ZoneType.Battlefield,
    ): Int {
        val card =
            player.getZone(zone).cards.firstOrNull { it.name == cardName }
                ?: error("Card '$cardName' not found in ${player.name} $zone")
        return harness.bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
    }

    /**
     * Resolve an instanceId back to its [forge.game.card.Card] via the bridge.
     * Returns null if the mapping is stale (card zoned out or iid reallocated).
     */
    fun cardByIid(iid: Int): forge.game.card.Card? {
        val cardId = harness.bridge.getForgeCardId(InstanceId(iid)) ?: return null
        return harness.game().findById(cardId.value)
    }

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
     * instanceId — same lookup as [instanceIdOf], with the zone made positional
     * so call sites read like a path: `human.battlefield.iid("Walking Corpse")`.
     */
    fun PlayerZone.iid(cardName: String): Int = instanceIdOf(cardName, player, zone)

    /**
     * Resolve a [forge.game.card.Card] already in hand to its instanceId via
     * the bridge. The zone is informational only here — the bridge keys by
     * Forge card id, not zone — but keeping the call shape consistent with
     * [iid] (`human.battlefield.iid(card)`) makes test sites read uniformly.
     */
    fun PlayerZone.iid(card: forge.game.card.Card): Int = harness.bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value

    /**
     * Resolve multiple cards by name in one go — `human.battlefield.iids("A", "B", "C")`.
     * Convenience over `listOf(iid("A"), iid("B"), iid("C"))` when the test
     * passes a target list straight to `selectTargets`.
     */
    fun PlayerZone.iids(vararg cardNames: String): List<Int> = cardNames.map { iid(it) }

    // --- State queries ---

    fun phase() = harness.phase()

    fun turn() = harness.turn()

    fun isGameOver() = harness.isGameOver()

    fun game(): Game = harness.game()
}
