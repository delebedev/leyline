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
 *         val aiPlayer = ai  // capture before actions that might end the game
 *         activateAbility("Card Name")
 *         selectTargets(listOf(2))
 *         passUntil(maxPasses = 5) { aiPlayer.life < 5 }
 *     }
 * })
 * ```
 */
abstract class InteractionTest(body: InteractionTest.() -> Unit) : FunSpec() {

    private var _harness: MatchFlowHarness? = null

    /** Current harness — available after [startPuzzle] or [startGame]. */
    val harness: MatchFlowHarness get() = _harness ?: error("Call startPuzzle() or startGame() first")

    val human: Player get() = harness.bridge.getPlayer(SeatId(1))!!
    val ai: Player get() = harness.bridge.getPlayer(SeatId(2))!!

    init {
        tags(IntegrationTag)
        afterEach {
            _harness?.shutdown()
            _harness = null
        }
        body()
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
