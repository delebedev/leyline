package leyline.tooling.headless

import forge.card.CardStateName
import leyline.bridge.handoff.PromptRecord
import leyline.copilot.ConsultResponse
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

    /** Ask the implementation-owned decision brain for a semantic consult. */
    fun advise(
        prompt: GREToClientMessage,
        mode: HeadlessAdviceMode = HeadlessAdviceMode.Live,
    ): ConsultResponse

    /** Resolve semantic card/ability identity without exposing runtime handles. */
    fun query(query: MatchQuery): MatchQueryResult

    override fun close()
}

enum class HeadlessAdviceMode { Live, Snapshot }

/** Semantic lookups needed by tooling; runtime registries stay behind [HeadlessMatch]. */
sealed interface MatchQuery {
    data class CardName(
        val grpId: Int,
    ) : MatchQuery

    data class CardGrpId(
        val cardName: String,
    ) : MatchQuery

    data class KeywordAbilityGrpId(
        val cardGrpId: Int,
        val keywordAbilityId: Int,
    ) : MatchQuery

    data class ActionMatchesAlternative(
        val instanceId: Int,
        val grpId: Int,
        val abilityGrpId: Int,
        val alternativeGrpId: Int,
        val keywordAbilityId: Int,
    ) : MatchQuery
}

sealed interface MatchQueryResult {
    data class CardName(
        val value: String?,
    ) : MatchQueryResult

    data class CardGrpId(
        val value: Int?,
    ) : MatchQueryResult

    data class KeywordAbilityGrpId(
        val value: Int?,
    ) : MatchQueryResult

    data class ActionMatchesAlternative(
        val value: Boolean,
    ) : MatchQueryResult
}

/** Match startup is data, not a collection of lifecycle calls. */
data class MatchSpec(
    val seed: Long = 42L,
    val deckList: String? = null,
    val opponentDeckList: String? = null,
    val puzzleText: String? = null,
    val puzzleResource: String? = null,
    val aiScript: List<ScriptedAction>? = null,
    /** Optional client prompt deadline used by deterministic interaction tests. */
    val promptTimeoutMs: Long? = null,
    val validating: Boolean = true,
    val validation: InvariantSelection = defaultHeadlessValidation(validating),
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
    data class PlayLand(
        val name: String? = null,
    ) : MatchIntent

    data class CastSpell(
        val cardName: String,
        val zone: SpellZone = SpellZone.Hand,
        val alternativeGrpId: Int? = null,
    ) : MatchIntent

    data class ActivateAbility(
        val cardName: String,
        val zone: SpellZone = SpellZone.Battlefield,
        val abilityIndex: Int = 0,
        val selectedColor: ManaColor? = null,
    ) : MatchIntent

    data class ActivateMana(
        val cardName: String,
        val abilityIndex: Int = 0,
        val selectedColor: ManaColor? = null,
    ) : MatchIntent

    data class AddIntrinsicKeyword(
        val instanceId: Int,
        val keyword: String,
    ) : MatchIntent

    data class AddStaticAbility(
        val instanceId: Int,
        val script: String,
    ) : MatchIntent

    data object PassPriority : MatchIntent

    data class Action(
        val action: wotc.mtgo.gre.external.messaging.Messages.Action,
    ) : MatchIntent

    data class Attackers(
        val instanceIds: List<Int>,
        val damageRecipients: Map<Int, wotc.mtgo.gre.external.messaging.Messages.DamageRecipient> = emptyMap(),
    ) : MatchIntent

    data class AttackersWithoutRecipients(
        val instanceIds: List<Int>,
    ) : MatchIntent

    data class DeselectAttackers(
        val instanceIds: List<Int>,
    ) : MatchIntent

    data class ToggleAttackers(
        val instanceIds: List<Int>,
        val alternatives: Map<Int, Int> = emptyMap(),
        val damageRecipients: Map<Int, wotc.mtgo.gre.external.messaging.Messages.DamageRecipient> = emptyMap(),
    ) : MatchIntent

    data object NoAttackers : MatchIntent

    data object AllAttackers : MatchIntent

    data object SubmitAttackers : MatchIntent

    data class Blockers(
        val assignments: Map<Int, Int>,
    ) : MatchIntent

    /** Update an iterative blocker selection without submitting the declaration. */
    data class ToggleBlockers(
        val assignments: Map<Int, Int>,
    ) : MatchIntent

    /** Remove one blocker assignment from an iterative declaration. */
    data class DeselectBlocker(
        val blockerInstanceId: Int,
    ) : MatchIntent

    data object NoBlockers : MatchIntent

    data object SubmitBlockers : MatchIntent

    data class DamageAssignment(
        val assigners: List<Pair<Int, List<Pair<Int, Int>>>>,
    ) : MatchIntent

    data class Targets(
        val instanceIds: List<Int>,
    ) : MatchIntent

    data class TargetsIterative(
        val instanceIds: List<Int>,
    ) : MatchIntent

    data class UnselectTargets(
        val instanceIds: List<Int>,
    ) : MatchIntent

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

    data class SelectN(
        val instanceIds: List<Int>,
    ) : MatchIntent

    data class Order(
        val instanceIds: List<Int>,
    ) : MatchIntent

    data class Search(
        val instanceIds: List<Int>,
    ) : MatchIntent

    data class EffectCost(
        val instanceIds: List<Int>,
    ) : MatchIntent

    data class GatherCounters(
        val gatherings: List<Pair<Int, Int>>,
    ) : MatchIntent

    data class ModalChoice(
        val selectedGrpIds: List<Int>,
    ) : MatchIntent

    data class OptionalCost(
        val ctoId: Int,
    ) : MatchIntent

    data class AlternateCost(
        val ctoId: Int,
        val optionIndex: Int,
    ) : MatchIntent

    data class ManaTypeChoices(
        val choicesByCtoId: List<Pair<Int, ManaColor>>,
    ) : MatchIntent

    data class OptionalAction(
        val accept: Boolean,
    ) : MatchIntent

    data class NumericInput(
        val value: Int,
    ) : MatchIntent

    data object Flush : MatchIntent

    data object Concede : MatchIntent

    data object HoldNextOptionalAction : MatchIntent

    data object DeclineNextOptionalAction : MatchIntent

    data class Settings(
        val stops: List<wotc.mtgo.gre.external.messaging.Messages.Stop>,
    ) : MatchIntent

    data class AutoPass(
        val option: wotc.mtgo.gre.external.messaging.Messages.AutoPassOption,
    ) : MatchIntent
}

enum class SpellZone {
    Battlefield,
    Hand,
    Graveyard,
    Exile,
}

/** A bounded engine progression request. */
sealed interface AdvanceGoal {
    data class Until(
        val maxPasses: Int = 20,
    ) : AdvanceGoal

    data class UntilTurn(
        val turn: Int,
        val maxPasses: Int = 30,
    ) : AdvanceGoal

    data class ThroughCombat(
        val startTurn: Int? = null,
        val maxPasses: Int = 15,
    ) : AdvanceGoal

    data class Resolved(
        val maxPasses: Int = 10,
    ) : AdvanceGoal

    data class Phase(
        val name: String,
        val turn: Int? = null,
    ) : AdvanceGoal

    data object Main1 : AdvanceGoal

    data class Combat(
        val turn: Int? = null,
    ) : AdvanceGoal

    data class Main2(
        val turn: Int? = null,
    ) : AdvanceGoal

    data object TriggerAutoPass : AdvanceGoal
}

/** Immutable cursor into the observed GRE stream. */
@JvmInline
value class MatchCheckpoint internal constructor(
    val index: Int,
)

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
    val cards: List<HeadlessCard>,
    val stackSize: Int?,
    /** Engine stack-object count used to determine whether a spell/ability remains unresolved. */
    val stackObjectsSize: Int? = null,
    val pendingAction: Boolean,
    val pendingActionKind: String?,
    val blockingInteraction: String?,
    val pendingCostSelection: Boolean = false,
    val validationViolations: List<String>,
    val validationViolationsByCheck: Map<String, Int>,
    val promptHistory: List<PromptRecord> = emptyList(),
    val consumedPromptMsgIds: List<Int> = emptyList(),
    val enabledStops: Set<String> = emptySet(),
    val poisonCountersBySeat: Map<Int, Int> = emptyMap(),
)

/** Immutable card view used by tooling and acceptance assertions. */
data class HeadlessCard(
    val instanceId: Int,
    val name: String,
    val seat: Int,
    val zone: String,
    val power: Int? = null,
    val toughness: Int? = null,
    val planeswalker: Boolean = false,
    val faceDown: Boolean = false,
    val isToken: Boolean = false,
    val isCopy: Boolean = false,
    val isLand: Boolean = false,
    val isTapped: Boolean = false,
    val damage: Int = 0,
    val counters: Map<String, Int> = emptyMap(),
    val abilityIds: List<Int> = emptyList(),
    val grpId: Int = 0,
    val objectSourceGrpId: Int = 0,
    val cardTypes: Set<String> = emptySet(),
    val subtypes: Set<String> = emptySet(),
    val keywords: Set<String> = emptySet(),
    val isSaddled: Boolean = false,
    val isSuspected: Boolean = false,
    val currentStateName: CardStateName? = null,
    val sVars: Set<String> = emptySet(),
    val hasSickness: Boolean = false,
    val attachedToInstanceId: Int? = null,
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
            .filter {
                it != 0 &&
                    it !in 1..2 &&
                    it !in objects &&
                    it !in zones &&
                    zones.values.none { zone -> it in zone.objectInstanceIdsList }
            }.toList()
    }
}

fun HeadlessMatch.cardNameByGrpId(grpId: Int): String? = (query(MatchQuery.CardName(grpId)) as MatchQueryResult.CardName).value

fun HeadlessMatch.cardGrpId(cardName: String): Int? = (query(MatchQuery.CardGrpId(cardName)) as MatchQueryResult.CardGrpId).value

fun HeadlessMatch.keywordAbilityGrpId(
    cardGrpId: Int,
    keywordAbilityId: Int,
): Int? = (query(MatchQuery.KeywordAbilityGrpId(cardGrpId, keywordAbilityId)) as MatchQueryResult.KeywordAbilityGrpId).value

fun HeadlessMatch.keywordAbilityGrpId(
    cardName: String,
    keywordAbilityId: Int,
): Int? = cardGrpId(cardName)?.let { keywordAbilityGrpId(it, keywordAbilityId) }

fun HeadlessMatch.actionMatchesAlternative(
    action: wotc.mtgo.gre.external.messaging.Messages.Action,
    keywordAbilityId: Int,
): Boolean =
    (
        query(
            MatchQuery.ActionMatchesAlternative(
                instanceId = action.instanceId,
                grpId = action.grpId,
                abilityGrpId = action.abilityGrpId,
                alternativeGrpId = action.alternativeGrpId,
                keywordAbilityId = keywordAbilityId,
            ),
        ) as MatchQueryResult.ActionMatchesAlternative
    ).value

fun HeadlessMatch.enabledStops(seat: Int = 1): Set<String> = if (seat == 1) observe().enabledStops else emptySet()

fun HeadlessMatch.diagnostics(
    label: String,
    messageTail: Int = 15,
): String = HeadlessMatchRuntime.diagnostics(this, label, messageTail)
