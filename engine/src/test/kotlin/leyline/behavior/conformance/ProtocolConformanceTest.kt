package leyline.behavior.conformance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.IntegrationTag
import leyline.acceptance.AcceptanceSuiteLoader
import leyline.acceptance.MatchdoorAcceptanceExecutor
import leyline.testkit.ProtocolContract
import leyline.testkit.allPersistentAnnotations
import leyline.testkit.annotation
import leyline.testkit.detail
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

class ProtocolConformanceTest :
    FunSpec({
        tags(IntegrationTag, ConformanceTag)
        val paths = ProtocolContract.files()
        require(paths.isNotEmpty()) { "no protocol contracts" }
        for (path in paths) {
            val contract = ProtocolContract.load(path)
            test(contract.name) {
                val scenario = AcceptanceSuiteLoader.load(contract.suite).scenarios.single { it.id == contract.scenario }
                MatchdoorAcceptanceExecutor().runScenario(scenario) { messages ->
                    withClue(contract.name) {
                        contract.verify(messages)
                        when (path.fileName.toString()) {
                            "lightning-bolt.yaml" -> checkDamageMutations(contract, messages)
                            "rabbit-battery-target-selection.yaml" -> checkTargetMutations(contract, messages)
                        }
                    }
                } shouldBe scenario.steps.size
            }
        }
    })

private fun checkDamageMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val frameIndex =
        messages.indexOfFirst {
            it.hasGameStateMessage() &&
                it.gameStateMessage.annotationsList.any { a -> AnnotationType.DamageDealt_af5a in a.typeList }
        }
    val frame = messages[frameIndex]
    val damage = frame.gameStateMessage.annotation(AnnotationType.DamageDealt_af5a)
    val wrongDamage =
        damage
            .toBuilder()
            .setDetails(
                damage.detailsList.indexOfFirst { it.key == "damage" },
                damage.detail("damage")!!.toBuilder().setValueInt32(0, 4),
            ).build()
    for ((name, mutant) in listOf(
        "duplicate damage" to
            frame.toBuilder().setGameStateMessage(frame.gameStateMessage.toBuilder().addAnnotations(damage)).build(),
        "wrong amount" to
            frame
                .toBuilder()
                .setGameStateMessage(
                    frame.gameStateMessage.toBuilder().setAnnotations(
                        frame.gameStateMessage.annotationsList.indexOf(damage),
                        wrongDamage,
                    ),
                ).build(),
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(frameIndex, mutant)) } }
    }
    val row = messages.allPersistentAnnotations().single { AnnotationType.TargetSpec in it.typeList }
    val birth = messages.indexOfFirst { it.hasGameStateMessage() && row in it.gameStateMessage.persistentAnnotationsList }
    val premature =
        messages.mapIndexed { index, message ->
            if (!message.hasGameStateMessage()) {
                message
            } else {
                val deletions = message.gameStateMessage.diffDeletedPersistentAnnotationIdsList.filter { it != row.id }
                message
                    .toBuilder()
                    .setGameStateMessage(
                        message.gameStateMessage
                            .toBuilder()
                            .clearDiffDeletedPersistentAnnotationIds()
                            .addAllDiffDeletedPersistentAnnotationIds(
                                deletions + if (index == birth) listOf(row.id) else emptyList(),
                            ),
                    ).build()
            }
        }
    withClue("premature target retirement") { shouldThrow<AssertionError> { contract.verify(premature) } }
}

private fun checkTargetMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val index = messages.indexOfFirst { it.hasSelectTargetsReq() }
    val prompt = messages[index]
    for ((name, mutant) in listOf(
        "empty target groups" to
            prompt.toBuilder().setSelectTargetsReq(prompt.selectTargetsReq.toBuilder().clearTargets()).build(),
        "wrong source" to prompt.toBuilder().setSelectTargetsReq(prompt.selectTargetsReq.toBuilder().setSourceId(0)).build(),
        "wrong undo flag" to prompt.toBuilder().setAllowUndo(false).build(),
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(index, mutant)) } }
    }
    val row = messages.allPersistentAnnotations().single { AnnotationType.TargetSpec in it.typeList }
    val missingRetirement =
        messages.map { message ->
            if (!message.hasGameStateMessage()) {
                message
            } else {
                message
                    .toBuilder()
                    .setGameStateMessage(
                        message.gameStateMessage
                            .toBuilder()
                            .clearDiffDeletedPersistentAnnotationIds()
                            .addAllDiffDeletedPersistentAnnotationIds(
                                message.gameStateMessage.diffDeletedPersistentAnnotationIdsList.filter { it != row.id },
                            ),
                    ).build()
            }
        }
    withClue("missing target retirement") { shouldThrow<AssertionError> { contract.verify(missingRetirement) } }
}

private fun List<GREToClientMessage>.replacing(
    index: Int,
    message: GREToClientMessage,
): List<GREToClientMessage> = toMutableList().also { it[index] = message }
