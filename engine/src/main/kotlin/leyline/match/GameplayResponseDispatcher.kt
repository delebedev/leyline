package leyline.match

import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Dispatch one post-handshake gameplay response to its session handler. */
@Suppress("CyclomaticComplexMethod", "ElseCaseInsteadOfExhaustiveWhen")
internal fun dispatchGameplayResponse(
    receiver: ActionReceiver,
    message: ClientToGREMessage,
): Boolean {
    when (message.type) {
        ClientMessageType.PerformActionResp_097b -> receiver.onPerformAction(message)
        ClientMessageType.DeclareAttackersResp_097b,
        ClientMessageType.SubmitAttackersReq,
        -> receiver.onDeclareAttackers(message)
        ClientMessageType.DeclareBlockersResp_097b,
        ClientMessageType.SubmitBlockersReq,
        -> receiver.onDeclareBlockers(message)
        ClientMessageType.SelectTargetsResp_097b -> receiver.onSelectTargets(message)
        ClientMessageType.SubmitTargetsReq -> receiver.onSubmitTargets(message)
        ClientMessageType.EffectCostResp_097b -> receiver.onEffectCost(message)
        ClientMessageType.GroupResp_097b -> receiver.onGroupResp(message)
        ClientMessageType.CancelActionReq_097b -> receiver.onCancelAction(message)
        ClientMessageType.SelectNresp -> receiver.onSelectN(message)
        ClientMessageType.OrderResp_097b -> receiver.onOrderResp(message)
        ClientMessageType.CastingTimeOptionsResp_097b -> receiver.onCastingTimeOptions(message)
        ClientMessageType.SearchResp_097b -> receiver.onSearch(message)
        ClientMessageType.AssignDamageResp_097b -> receiver.onAssignDamage(message)
        ClientMessageType.OptionalActionResp -> receiver.onOptionalActionResp(message)
        ClientMessageType.NumericInputResp_097b -> receiver.onNumericInputResp(message)
        else -> return false
    }
    return true
}
