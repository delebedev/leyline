package leyline.tooling.simclient

import leyline.game.state.GameBridge
import leyline.match.MatchSession
import leyline.tooling.headless.ClientAccumulator
import leyline.tooling.headless.HeadlessMatch
import leyline.tooling.headless.MatchFlowHarness
import leyline.tooling.headless.MatchIntent
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Private simclient adapter. It translates policy decisions to the semantic
 * headless seam; the runtime handles below are retained only for Forge-AI and
 * log-collection integrations that have no semantic value equivalent yet.
 */
internal class SimClientHeadlessAdapter(
    val match: HeadlessMatch,
) {
    private val runtime: MatchFlowHarness get() = match as MatchFlowHarness

    val bridge: GameBridge get() = runtime.bridge
    val session: MatchSession get() = runtime.session
    val accumulator: ClientAccumulator get() = runtime.accumulator
    val validatingSink: leyline.tooling.headless.ValidatingMessageSink? get() = runtime.validatingSink
    val allMessages: List<GREToClientMessage> get() = match.observe().messages

    fun connectAndKeep() = runtime.connectAndKeep()

    fun connectAndKeepPuzzleText(text: String) = runtime.connectAndKeepPuzzleText(text)

    fun shutdown() = match.close()

    fun isGameOver() = match.observe().gameOver

    fun isAiTurn() = match.observe().aiTurn

    fun hasPendingAction() = match.observe().pendingAction

    fun turn() = match.observe().turn ?: 0

    fun passPriority() = match.submit(MatchIntent.PassPriority)

    fun triggerAutoPass() = match.submit(MatchIntent.Flush)

    fun submitAction(action: Action) = match.submit(MatchIntent.Action(action))

    fun selectTargets(ids: List<Int>) = match.submit(MatchIntent.Targets(ids))

    fun submitTargets() = match.submit(MatchIntent.SubmitTargets)

    fun respondToSelectN(ids: List<Int>) = match.submit(MatchIntent.SelectN(ids))

    fun respondToOrder(ids: List<Int>) = match.submit(MatchIntent.Order(ids))

    fun respondToSearch(ids: List<Int>) = match.submit(MatchIntent.Search(ids))

    fun respondToEffectCost(ids: List<Int>) = match.submit(MatchIntent.EffectCost(ids))

    fun respondToGroupReq(away: List<Int>, all: List<Int>) = match.submit(MatchIntent.Group(away, all))

    fun respondToScry(bottom: List<Int>, all: List<Int>) = match.submit(MatchIntent.Scry(bottom, all))

    fun respondModalChoice(ids: List<Int>) = match.submit(MatchIntent.ModalChoice(ids))

    fun respondToOptionalCost(ctoId: Int) = match.submit(MatchIntent.OptionalCost(ctoId))

    fun respondToAlternateCost(ctoId: Int, optionIndex: Int) = match.submit(MatchIntent.AlternateCost(ctoId, optionIndex))

    fun respondToManaTypeChoices(choices: List<Pair<Int, ManaColor>>) = match.submit(MatchIntent.ManaTypeChoices(choices))

    fun respondToOptionalAction(accept: Boolean) = match.submit(MatchIntent.OptionalAction(accept))

    fun respondToNumericInput(value: Int) = match.submit(MatchIntent.NumericInput(value))

    fun declareAllAttackers() = match.submit(MatchIntent.AllAttackers)

    fun declareAttackers(ids: List<Int>) = match.submit(MatchIntent.Attackers(ids))

    fun submitAttackers() = match.submit(MatchIntent.SubmitAttackers)

    fun declareBlockers(assignments: Map<Int, Int>) = match.submit(MatchIntent.Blockers(assignments))

    fun declareNoBlockers() = match.submit(MatchIntent.NoBlockers)

    fun submitBlockers() = match.submit(MatchIntent.SubmitBlockers)

    fun assignDamage(assigners: List<Pair<Int, List<Pair<Int, Int>>>>) = match.submit(MatchIntent.DamageAssignment(assigners))

    fun cancelAction() = match.submit(MatchIntent.CancelAction)

    fun drainSink() = match.submit(MatchIntent.Flush)

    fun concede() = match.submit(MatchIntent.Concede)

    fun promptHistory() = match.observe().let { runtime.bridge.promptBridge(leyline.bridge.types.SeatId(1)).history }

    fun takeConsumedPromptMsgIds() = match.observe().consumedPromptMsgIds
}
