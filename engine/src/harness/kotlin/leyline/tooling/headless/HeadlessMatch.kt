package leyline.tooling.headless

import leyline.game.bundle.InvariantSelection
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage

/**
 * Semantic seam for an in-process match used by tests and local tooling.
 *
 * The implementation owns session wiring, prompt correlation, response
 * encoding, output draining, validation, and teardown. Callers submit intent
 * values and inspect immutable observations instead of reaching into the
 * bridge, session, sinks, or Forge objects.
 */
interface HeadlessMatch : AutoCloseable {
    fun start(): MatchResult

    fun submit(intent: MatchIntent): MatchResult

    fun advance(goal: AdvanceGoal): MatchResult

    fun observe(): MatchObservation

    fun checkpoint(): MatchCheckpoint

    fun messagesSince(checkpoint: MatchCheckpoint): List<GREToClientMessage>

    fun diagnostics(label: String, messageTail: Int = 15): String

    override fun close()
}

/** Match startup is data, not a collection of lifecycle calls. */
data class MatchSpec(
    val seed: Long = 42L,
    val deckList: String? = null,
    val opponentDeckList: String? = null,
    val puzzleText: String? = null,
    val puzzleResource: String? = null,
    val aiScript: List<ScriptedAction>? = null,
    val validating: Boolean = true,
    val validation: InvariantSelection = MatchFlowHarness.defaultValidation(validating),
    val validationStrict: Boolean = true,
    val responseMode: HeadlessResponseMode = HeadlessResponseMode.AutoForTests,
) {
    init {
        require(puzzleText == null || puzzleResource == null) {
            "Give at most one puzzle source: inline text or classpath resource"
        }
        require(deckList == null || (puzzleText == null && puzzleResource == null)) {
            "deckList applies to a normal game, not a puzzle"
        }
    }
}

/** One semantic action or prompt answer submitted at the headless seam. */
sealed interface MatchIntent {
    data class PlayLand(val name: String? = null) : MatchIntent

    data class CastSpell(
        val cardName: String,
        val zone: SpellZone = SpellZone.Hand,
    ) : MatchIntent

    data class ActivateAbility(
        val cardName: String,
        val zone: SpellZone = SpellZone.Battlefield,
        val abilityIndex: Int = 0,
        val selectedColor: ManaColor? = null,
    ) : MatchIntent

    data object PassPriority : MatchIntent

    data class Action(val action: wotc.mtgo.gre.external.messaging.Messages.Action) : MatchIntent

    data class Attackers(
        val instanceIds: List<Int>,
        val damageRecipients: Map<Int, wotc.mtgo.gre.external.messaging.Messages.DamageRecipient> = emptyMap(),
    ) : MatchIntent

    data object NoAttackers : MatchIntent

    data object AllAttackers : MatchIntent

    data object SubmitAttackers : MatchIntent

    data class Blockers(val assignments: Map<Int, Int>) : MatchIntent

    data object NoBlockers : MatchIntent

    data object SubmitBlockers : MatchIntent

    data class DamageAssignment(
        val assigners: List<Pair<Int, List<Pair<Int, Int>>>>,
    ) : MatchIntent

    data class Targets(val instanceIds: List<Int>) : MatchIntent

    data class TargetsIterative(val instanceIds: List<Int>) : MatchIntent

    data object SubmitTargets : MatchIntent

    data object CancelAction : MatchIntent

    data class Group(
        val awayInstanceIds: List<Int>,
        val allInstanceIds: List<Int>,
    ) : MatchIntent

    data class Scry(
        val bottomInstanceIds: List<Int>,
        val allInstanceIds: List<Int>,
    ) : MatchIntent

    data class SelectN(val instanceIds: List<Int>) : MatchIntent

    data class Order(val instanceIds: List<Int>) : MatchIntent

    data class Search(val instanceIds: List<Int>) : MatchIntent

    data class EffectCost(val instanceIds: List<Int>) : MatchIntent

    data class GatherCounters(val gatherings: List<Pair<Int, Int>>) : MatchIntent

    data class ModalChoice(val selectedGrpIds: List<Int>) : MatchIntent

    data class OptionalCost(val ctoId: Int) : MatchIntent

    data class AlternateCost(val ctoId: Int, val optionIndex: Int) : MatchIntent

    data class ManaTypeChoices(val choicesByCtoId: List<Pair<Int, ManaColor>>) : MatchIntent

    data class OptionalAction(val accept: Boolean) : MatchIntent

    data class NumericInput(val value: Int) : MatchIntent
}

enum class SpellZone {
    Battlefield,
    Hand,
    Graveyard,
    Exile,
}

/** A bounded engine progression request. */
sealed interface AdvanceGoal {
    data class Until(val maxPasses: Int = 20) : AdvanceGoal

    data class UntilTurn(val turn: Int, val maxPasses: Int = 30) : AdvanceGoal

    data class ThroughCombat(val startTurn: Int? = null, val maxPasses: Int = 15) : AdvanceGoal

    data class Resolved(val maxPasses: Int = 10) : AdvanceGoal

    data class Phase(val name: String, val turn: Int? = null) : AdvanceGoal

    data object Main1 : AdvanceGoal

    data class Combat(val turn: Int? = null) : AdvanceGoal

    data class Main2(val turn: Int? = null) : AdvanceGoal

    data object TriggerAutoPass : AdvanceGoal
}

/** Immutable cursor into the observed GRE stream. */
@JvmInline
value class MatchCheckpoint internal constructor(val index: Int)

/** Result of a submission or advancement, including the resulting observation. */
data class MatchResult(
    val accepted: Boolean,
    val observation: MatchObservation,
    val consumedPromptMsgIds: List<Int> = emptyList(),
)

/** Immutable client-visible state at a headless synchronization point. */
data class MatchObservation(
    val messages: List<GREToClientMessage>,
    val rawMessages: List<MatchServiceToClientMessage>,
    val client: ClientStateSnapshot,
    val phase: String?,
    val turn: Int?,
    val aiTurn: Boolean,
    val gameOver: Boolean,
    val latestPromptGsId: Int,
    val latestPromptMsgId: Int,
)

/** Immutable projection of the client accumulator. */
data class ClientStateSnapshot(
    val objects: Map<Int, wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo>,
    val zones: Map<Int, wotc.mtgo.gre.external.messaging.Messages.ZoneInfo>,
    val players: Map<Int, wotc.mtgo.gre.external.messaging.Messages.PlayerInfo>,
    val turnInfo: wotc.mtgo.gre.external.messaging.Messages.TurnInfo?,
    val actions: wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq?,
    val latestGsId: Int,
    val messageCount: Int,
    val gsIdHistory: List<Int>,
) {
    fun actionInstanceIdsMissingFromObjects(): List<Int> {
        val req = actions ?: return emptyList()
        return req.actionsList
            .asSequence()
            .map { it.instanceId }
            .filter { it != 0 && it !in 1..2 && it !in objects && it !in zones && zones.values.none { zone -> it in zone.objectInstanceIdsList } }
            .toList()
    }
}
