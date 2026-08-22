package leyline.tooling.headless

import forge.card.CardStateName
import leyline.bridge.handoff.PromptRecord
import leyline.copilot.ConsultResponse
import leyline.game.bundle.InvariantSelection
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
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
    val setup: List<MatchSetup> = emptyList(),
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

/** Declarative fixture mutation applied after a match has been created. */
sealed interface MatchSetup {
    data class AddKeyword(
        val cardName: String,
        val keyword: IntrinsicKeyword,
        val zone: SpellZone = SpellZone.Anywhere,
    ) : MatchSetup

    data class AddStaticAbility(
        val cardName: String,
        val ability: StaticAbilitySetup,
        val zone: SpellZone = SpellZone.Battlefield,
    ) : MatchSetup
}

enum class IntrinsicKeyword { FirstStrike, DoubleStrike }

enum class StaticAbilitySetup { VehicleCrewPowerWeight }

/** One semantic gameplay operation or prompt answer submitted at the seam. */
sealed interface MatchIntent {
    data class Play(
        val action: PlayAction,
    ) : MatchIntent

    data class Combat(
        val action: CombatAction,
    ) : MatchIntent

    data class Prompt(
        val response: PromptResponse,
    ) : MatchIntent

    data class Control(
        val action: ControlAction,
    ) : MatchIntent
}

sealed interface PlayAction {
    data class Land(
        val name: String? = null,
    ) : PlayAction

    data class Spell(
        val cardName: String,
        val zone: SpellZone = SpellZone.Hand,
        val alternativeGrpId: Int? = null,
    ) : PlayAction

    data class Ability(
        val cardName: String,
        val zone: SpellZone = SpellZone.Battlefield,
        val abilityIndex: Int = 0,
    ) : PlayAction

    data class ManaAbility(
        val cardName: String,
        val abilityIndex: Int = 0,
        val color: ManaColorChoice? = null,
    ) : PlayAction

    data class Selection(
        val action: ActionSelection,
    ) : PlayAction
}

data class ActionSelection(
    val kind: ActionKind,
    val instanceId: Int = 0,
    val abilityGrpId: Int = 0,
    val alternativeGrpId: Int = 0,
)

enum class ActionKind {
    Pass,
    Cast,
    Activate,
    ActivateMana,
    PlayLand,
    PlayMdfc,
    CastMdfc,
    CastAdventure,
    CastOmen,
    TurnFaceUp,
}

enum class ManaColorChoice {
    White,
    Blue,
    Black,
    Red,
    Green,
    Colorless,
    Phyrexian,
    Generic,
    X,
    Y,
    TwoGeneric,
    AnyColor,
    Snow,
}

sealed interface CombatAction {
    data class Attackers(
        val instanceIds: List<Int>,
        val damageRecipients: Map<Int, DamageRecipientChoice> = emptyMap(),
        val recipientMode: AttackerRecipientMode = AttackerRecipientMode.Default,
    ) : CombatAction

    data class ToggleAttackers(
        val instanceIds: List<Int>,
        val alternatives: Map<Int, Int> = emptyMap(),
        val damageRecipients: Map<Int, DamageRecipientChoice> = emptyMap(),
    ) : CombatAction

    data class DeselectAttackers(
        val instanceIds: List<Int>,
    ) : CombatAction

    data class Blockers(
        val assignments: Map<Int, Int>,
    ) : CombatAction

    data class ToggleBlockers(
        val assignments: Map<Int, Int>,
    ) : CombatAction

    data class DeselectBlocker(
        val blockerInstanceId: Int,
    ) : CombatAction

    data class DamageAssignment(
        val assigners: List<Pair<Int, List<Pair<Int, Int>>>>,
    ) : CombatAction

    data object NoAttackers : CombatAction

    data object AllAttackers : CombatAction

    data object SubmitAttackers : CombatAction

    data object NoBlockers : CombatAction

    data object SubmitBlockers : CombatAction
}

enum class AttackerRecipientMode { Default, Omit }

sealed interface DamageRecipientChoice {
    data object Opponent : DamageRecipientChoice

    data class Planeswalker(
        val instanceId: Int,
    ) : DamageRecipientChoice
}

sealed interface PromptResponse {
    data class Targets(
        val instanceIds: List<Int>,
        val iterative: Boolean = false,
        val targetIndex: Int? = null,
    ) : PromptResponse

    data class UnselectTargets(
        val instanceIds: List<Int>,
    ) : PromptResponse

    data object SubmitTargets : PromptResponse

    data class Group(
        val awayInstanceIds: List<Int>,
        val allInstanceIds: List<Int>,
    ) : PromptResponse

    data class Scry(
        val bottomInstanceIds: List<Int>,
        val allInstanceIds: List<Int>,
    ) : PromptResponse

    data class SelectN(
        val instanceIds: List<Int>,
    ) : PromptResponse

    data class Order(
        val instanceIds: List<Int>,
    ) : PromptResponse

    data class Search(
        val instanceIds: List<Int>,
    ) : PromptResponse

    data class EffectCost(
        val instanceIds: List<Int>,
    ) : PromptResponse

    data class GatherCounters(
        val gatherings: List<Pair<Int, Int>>,
    ) : PromptResponse

    data class ModalChoice(
        val selectedGrpIds: List<Int>,
    ) : PromptResponse

    data class OptionalCost(
        val ctoId: Int,
    ) : PromptResponse

    data class AlternateCost(
        val ctoId: Int,
        val optionIndex: Int,
    ) : PromptResponse

    data class ManaTypeChoices(
        val choicesByCtoId: List<Pair<Int, ManaColorChoice>>,
    ) : PromptResponse

    data class OptionalAction(
        val accept: Boolean,
    ) : PromptResponse

    data class NumericInput(
        val value: Int,
    ) : PromptResponse

    /** Select one source for a native mana-payment prompt, or finish it when [sourceInstanceId] is null. */
    data class ManaPayment(
        val sourceInstanceId: Int? = null,
        val repeatedSelectionInstanceIds: List<Int> = emptyList(),
    ) : PromptResponse

    data object Cancel : PromptResponse
}

sealed interface ControlAction {
    data object PassPriority : ControlAction

    data object Concede : ControlAction

    data object HoldNextOptionalAction : ControlAction

    data object DeclineNextOptionalAction : ControlAction

    data class Stops(
        val changes: List<StopChange>,
    ) : ControlAction

    data class AutoPass(
        val option: AutoPassChoice,
    ) : ControlAction
}

data class StopChange(
    val phase: String,
    val scope: StopScope,
    val enabled: Boolean,
)

enum class StopScope { Team, Opponents, AnyPlayer }

enum class AutoPassChoice { ResolveMyStackEffects }

enum class SpellZone {
    Anywhere,
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

/** Prompt that is still waiting for a semantic response at the observation boundary. */
sealed interface PendingInteraction {
    data class SelectN(
        val messageId: Int,
        val instanceIds: List<Int>,
        val min: Int,
        val max: Int,
    ) : PendingInteraction
}

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
    val blockingInteractionId: String? = null,
    val pendingInteraction: PendingInteraction? = null,
    val activeRevealVersion: Long? = null,
    val pendingRevealInteractionId: String? = null,
    val loopFailure: String? = null,
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
    val colors: Set<String> = emptySet(),
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
