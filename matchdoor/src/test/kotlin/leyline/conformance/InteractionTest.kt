package leyline.conformance

import forge.game.Game
import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import leyline.IntegrationTag
import leyline.bridge.InstanceId
import leyline.bridge.SeatId

/**
 * Base class for session-tier interaction tests (MatchFlowHarness).
 *
 * Parallel to [SubsystemTest] (board/bridge tier). Never mix in one file.
 * Auto-wires IntegrationTag and harness lifecycle (shutdown after each test).
 *
 * ```
 * class FooInteractionTest : InteractionTest({
 *     test("ability resolves and deals damage") {
 *         startPuzzle("""
 *             [metadata]
 *             ...
 *             [state]
 *             ...
 *         """.trimIndent())
 *
 *         activateAbility("Card Name")
 *         selectTargets(listOf(2))
 *         passUntilResolved()
 *         ai.life shouldBe 4
 *     }
 * })
 * ```
 */
abstract class InteractionTest(body: InteractionTest.() -> Unit) : FunSpec() {

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

    /** Start a puzzle game from inline `.pzl` text. */
    fun startPuzzle(
        puzzleText: String,
        seed: Long = 42L,
        validating: Boolean = false,
    ): MatchFlowHarness {
        val h = MatchFlowHarness(seed = seed, validating = validating)
        _harness = h
        h.connectAndKeepPuzzleText(puzzleText)
        cachePlayerRefs()
        return h
    }

    /** Start a normal game (mulligan + keep). */
    fun startGame(
        seed: Long = 42L,
        deckList: String? = null,
        validating: Boolean = true,
    ): MatchFlowHarness {
        val h = MatchFlowHarness(seed = seed, deckList = deckList, validating = validating)
        _harness = h
        h.connectAndKeep()
        cachePlayerRefs()
        return h
    }

    // --- Game actions ---

    fun activateAbility(cardName: String, abilityIndex: Int = 0) =
        harness.activateAbility(cardName, abilityIndex)

    fun activateAbilityFromHand(cardName: String, abilityIndex: Int = 0) =
        harness.activateAbilityFromHand(cardName, abilityIndex)

    fun selectTargets(targetInstanceIds: List<Int>) =
        harness.selectTargets(targetInstanceIds)

    fun passPriority() = harness.passPriority()

    fun passUntil(maxPasses: Int = 20, stopWhen: MatchFlowHarness.() -> Boolean) =
        harness.passUntil(maxPasses, stopWhen)

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

    fun castSpellByName(cardName: String, zone: ZoneType = ZoneType.Hand) =
        harness.castSpellByName(cardName, zone)

    fun resolveSpell(cardName: String) = harness.resolveSpell(cardName)

    // --- Card lookup ---

    /** Resolve instanceId to card name via bridge. Fails clearly if card not found. */
    fun cardName(instanceId: Int): String {
        val cardId = harness.bridge.getForgeCardId(InstanceId(instanceId))
            ?: error("No ForgeCardId for instanceId $instanceId")
        val card = harness.game().findById(cardId.value)
            ?: error("No card for forgeCardId ${cardId.value}")
        return card.name
    }

    /** Find instanceId for a card by name in a list of candidate instanceIds. */
    fun findInstanceId(candidateIds: List<Int>, name: String): Int = candidateIds.firstOrNull { cardName(it) == name }
        ?: error("Card '$name' not found in candidates: $candidateIds")

    // --- State queries ---

    fun phase() = harness.phase()
    fun turn() = harness.turn()
    fun isGameOver() = harness.isGameOver()
    fun game(): Game = harness.game()
}
