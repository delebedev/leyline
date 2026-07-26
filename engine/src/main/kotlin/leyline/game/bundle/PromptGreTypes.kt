package leyline.game.bundle

import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * GRE message types that carry a prompt — i.e. the server is asking the
 * client to produce a response. Interactive delivery advances the
 * [leyline.match.MatchOwner] horizon used by staleness checks.
 *
 * Adding a new prompt-bearing GRE type requires adding it here; owner-state
 * and protocol-enum tests pin the complete set.
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
