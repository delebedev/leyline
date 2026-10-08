package leyline.testkit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AutoTapSolution
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

class ProtocolContractActionTest :
    FunSpec({
        tags(UnitTag)

        test("selected offers require their own prediction and reject contradictory first offers") {
            val contract = ProtocolContract.parse(offerContract)
            contract.verify(listOf(offers(predictedOffer.toBuilder().setInstanceId(20).build(), predictedOffer)))
            val inactive =
                GREToClientMessage
                    .newBuilder()
                    .setActionsAvailableReq(
                        ActionsAvailableReq.newBuilder().addInactiveActions(predictedOffer),
                    ).build()
            for (messages in listOf(
                listOf(offers(predictedOffer.toBuilder().clearAutoTapSolution().build(), predictedOffer)),
                listOf(offers(predictedOffer.toBuilder().setAbilityGrpId(0).build(), predictedOffer)),
                listOf(inactive),
            )) {
                shouldThrow<AssertionError> { contract.verify(messages) }
            }
        }

        test("existential offer windows permit early unfunded offers but require a matching prediction inside the horizon") {
            val contract = ProtocolContract.parse(windowContract)
            val early = offers(predictedOffer.toBuilder().clearAutoTapSolution().build())
            val funded = offers(predictedOffer)
            contract.verify(listOf(startFrame, early, funded, endFrame))
            for (messages in listOf(
                listOf(startFrame, early, endFrame),
                listOf(startFrame, early, offers(predictedOffer.toBuilder().setInstanceId(20).build()), endFrame),
                listOf(funded, startFrame, early, endFrame),
                listOf(startFrame, early, endFrame, funded),
            )) {
                shouldThrow<AssertionError> { contract.verify(messages) }
            }
        }

        test("malformed presence and action schemas fail loading") {
            for (clause in listOf("present: []", "present: [unknown.field]", "present: [1]", "present: null")) {
                shouldThrowAny { ProtocolContract.parse(offerContract.replace("present: [raw.autoTapSolution]", clause)) }
            }
            for ((old, replacement) in listOf(
                "type: Action" to "type: UnknownAction",
                "lane: action" to "lane: prompt",
                "op: offer" to "op: create",
            )) {
                shouldThrowAny { ProtocolContract.parse(offerContract.replace(old, replacement)) }
            }
            for (clause in listOf(
                "exists: null",
                "exists: {}",
                "exists: {type: Action}\n    absent: {type: Action}",
                "exists: {type: Action}\n    exactly: 1",
            )) {
                shouldThrowAny { ProtocolContract.parse(windowContract.substringBefore("    exists:") + "    $clause") }
            }
        }
    })

private val predictedOffer =
    Action
        .newBuilder()
        .setActionType(ActionType.Cast)
        .setInstanceId(10)
        .setAbilityGrpId(371)
        .setAutoTapSolution(AutoTapSolution.getDefaultInstance())
        .build()

private fun offers(vararg actions: Action): GREToClientMessage =
    GREToClientMessage
        .newBuilder()
        .setActionsAvailableReq(ActionsAvailableReq.newBuilder().addAllActions(actions.toList()))
        .build()

private fun annotationFrame(type: AnnotationType): GREToClientMessage =
    GREToClientMessage
        .newBuilder()
        .setGameStateMessage(GameStateMessage.newBuilder().addAnnotations(AnnotationInfo.newBuilder().addType(type).setAffectorId(10)))
        .build()

private val startFrame = annotationFrame(AnnotationType.ResolutionStart)
private val endFrame = annotationFrame(AnnotationType.ResolutionComplete)
private val offerContract =
    """
    name: predicted offer
    scenario: {suite: warmup, id: land-spell-face}
    frames:
      - id: offer
        events:
          - id: offer
            type: Action
            lane: action
            op: offer
            where: {fields: {raw.instanceId: 10}}
            fields: {raw.actionType: Cast, raw.abilityGrpId: 371}
            present: [raw.autoTapSolution]
    """.trimIndent()
private val windowContract =
    """
    name: bounded predicted offer
    scenario: {suite: warmup, id: land-spell-face}
    frames:
      - id: start
        events:
          - id: start
            type: ResolutionStart
      - id: end
        events:
          - id: end
            type: ResolutionComplete
    windows:
      - id: predicted
        after: start
        before: end
        exists:
          type: Action
          lane: action
          op: offer
          fields: {raw.actionType: Cast, raw.abilityGrpId: 371}
          equals: {raw.instanceId: start.affectorId}
          present: [raw.autoTapSolution]
    """.trimIndent()
