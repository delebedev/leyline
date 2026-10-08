package leyline.testkit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStage
import wotc.mtgo.gre.external.messaging.Messages.OptionContext

internal fun checkCleanupDiscardMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val choiceIndex = messages.indexOfFirst { it.hasSelectNReq() }
    val choice = messages[choiceIndex]
    val promptMutants =
        listOf(
            "cost context" to
                choice
                    .toBuilder()
                    .setSelectNReq(
                        choice.selectNReq.toBuilder().setOptionContext(
                            OptionContext.Payment,
                        ),
                    ).build(),
            "wrong state reference" to choice.toBuilder().setGameStateId(0).build(),
            "wrong candidate count" to
                choice
                    .toBuilder()
                    .setSelectNReq(
                        choice.selectNReq
                            .toBuilder()
                            .clearIds()
                            .addAllIds(choice.selectNReq.idsList.drop(1)),
                    ).build(),
        )
    for ((name, mutant) in promptMutants) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(choiceIndex, mutant)) } }
    }
    val discardMessage =
        messages.indexOfFirst { message ->
            message.hasGameStateMessage() &&
                message.gameStateMessage.annotationsList.any {
                    AnnotationType.ZoneTransfer_af5a in it.typeList && it.detailString("category") == "Discard"
                }
        }
    val wrongSelection =
        messages[discardMessage].mutatingGameState { gsm ->
            val rows =
                gsm.annotationsList.map {
                    if (AnnotationType.ObjectIdChanged in it.typeList) it.withIntDetail("orig_id", 0) else it
                }
            gsm.clearAnnotations().addAllAnnotations(rows)
        }
    shouldThrow<AssertionError> { contract.verify(messages.replacing(discardMessage, wrongSelection)) }
    val wrongDestination =
        messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
            if (it.detailString("category") == "Discard") it.withIntDetail("zone_dest", 29) else it
        }
    shouldThrow<AssertionError> { contract.verify(wrongDestination) }
}

internal fun checkLifeTotalLossMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val wrongReason =
        messages.mutatingAnnotation(AnnotationType.LossOfGame_af5a, persistent = true) {
            val reason = it.detailsList.indexOfFirst { detail -> detail.key == "reason" }
            it.toBuilder().setDetails(reason, it.getDetails(reason).toBuilder().setValueString(0, "SBA_Poison")).build()
        }
    val wrongLoser =
        messages.mutatingAnnotation(AnnotationType.LossOfGame_af5a, persistent = true) {
            it.toBuilder().setAffectedIds(0, 1).build()
        }
    val duplicate =
        messages.map { message ->
            message.mutatingGameState { gsm ->
                gsm.persistentAnnotationsList.singleOrNull { AnnotationType.LossOfGame_af5a in it.typeList }?.let(
                    gsm::addPersistentAnnotations,
                )
                gsm
            }
        }
    val wrongState =
        messages.map { message ->
            message.mutatingGameState { gsm ->
                if (gsm.persistentAnnotationsList.any { AnnotationType.LossOfGame_af5a in it.typeList }) {
                    gsm.gameInfo =
                        gsm.gameInfo
                            .toBuilder()
                            .setStage(GameStage.Play_a920)
                            .build()
                }
                gsm
            }
        }
    for ((name, mutant) in listOf(
        "wrong cause" to wrongReason,
        "wrong loser" to wrongLoser,
        "duplicate loss" to duplicate,
        "nonterminal state" to wrongState,
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
    }
}
