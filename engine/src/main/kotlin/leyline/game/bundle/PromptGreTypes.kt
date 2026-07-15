package leyline.game.bundle

import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage

/**
 * GRE message types that carry a prompt — i.e. the server is asking the
 * client to produce a response. Emitting one of these advances the
 * [MessageCounter.lastPromptGsId] horizon used by staleness checks in
 * [leyline.match.ActionPerformer] and [leyline.match.CombatHandler].
 *
 * Auto-marking happens inside the `makeGRE` helpers in [BundleBuilder] and
 * [leyline.match.MatchSession] / [leyline.match.FamiliarSession] via
 * [markIfPrompt]. Adding a new prompt-bearing GRE type? Add it here and
 * [BundleBuilderTest] / [leyline.match.MatchSessionMakeGreTest] pin the
 * behaviour.
 *
 * Excluded on purpose:
 * - `*Resp` types (server→client acks)
 * - `GameStateMessage` / `QueuedGameStateMessage` (state diffs, not prompts)
 * - `IntermissionReq` (terminal — client response goes to a separate path,
 *   not a staleness-checked handler)
 * - `EdictalMessage`, `TimerStateMessage`, `UIMessage`, `MatchCompletedEvent`
 *   (informational; no awaited response)
 */
val PROMPT_GRE_TYPES: Set<GREMessageType> =
    setOf(
        GREMessageType.ActionsAvailableReq_695e,
        GREMessageType.SelectTargetsReq_695e,
        GREMessageType.SelectNreq,
        GREMessageType.GroupReq_695e,
        GREMessageType.SearchReq_695e,
        GREMessageType.OrderReq_695e,
        GREMessageType.DeclareAttackersReq_695e,
        GREMessageType.DeclareBlockersReq_695e,
        GREMessageType.CastingTimeOptionsReq_695e,
        GREMessageType.PayCostsReq_695e,
        GREMessageType.PromptReq,
        GREMessageType.OptionalActionMessage_695e,
        GREMessageType.AssignDamageReq_695e,
        GREMessageType.NumericInputReq_695e,
        GREMessageType.MulliganReq_aa0d,
        GREMessageType.ChooseStartingPlayerReq_695e,
    )

/** Mark prompt correlation ids on [counter] when [type] is in [PROMPT_GRE_TYPES]. */
fun markIfPrompt(
    counter: MessageCounter,
    type: GREMessageType,
    gsId: Int,
    msgId: Int,
) {
    if (type in PROMPT_GRE_TYPES) {
        counter.markPromptGsId(gsId)
        counter.markPromptMsgId(msgId)
    }
}

/** Mark every prompt contained in a match-service wrapper. */
fun markPrompts(
    counter: MessageCounter,
    message: MatchServiceToClientMessage,
) {
    if (!message.hasGreToClientEvent()) return
    for (prompt in message.greToClientEvent.greToClientMessagesList) {
        markIfPrompt(counter, prompt.type, prompt.gameStateId, prompt.msgId)
    }
}
