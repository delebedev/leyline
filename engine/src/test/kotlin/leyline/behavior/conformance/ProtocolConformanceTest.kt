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
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

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
                            "llanowar-elves.yaml" -> checkManaMutations(contract, messages)
                            "novice-inspector.yaml" -> {
                                checkTokenMutations(contract, messages)
                                val row = messages.allPersistentAnnotations().single { AnnotationType.TriggeringObject in it.typeList }
                                shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) }
                            }
                            "usher-of-the-fallen.yaml" -> {
                                checkTokenMutations(contract, messages)
                                val stillAvailable =
                                    messages.mutatingAnnotation(AnnotationType.AbilityExhausted, persistent = true) {
                                        it.withIntDetail("UsesRemaining", 1)
                                    }
                                shouldThrow<AssertionError> { contract.verify(stillAvailable) }
                            }
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
        "wrong prompt parameter" to
            prompt
                .toBuilder()
                .setSelectTargetsReq(
                    prompt.selectTargetsReq.toBuilder().setTargets(
                        0,
                        prompt.selectTargetsReq.getTargets(0).toBuilder().setPrompt(
                            prompt.selectTargetsReq.getTargets(0).prompt.toBuilder().setParameters(
                                0,
                                prompt.selectTargetsReq
                                    .getTargets(0)
                                    .prompt
                                    .getParameters(0)
                                    .toBuilder()
                                    .setNumberValue(0),
                            ),
                        ),
                    ),
                ).build(),
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(index, mutant)) } }
    }
    val row = messages.allPersistentAnnotations().single { AnnotationType.TargetSpec in it.typeList }
    withClue("missing target retirement") { shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) } }
}

private fun checkManaMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val untapped = messages.mutatingAnnotation(AnnotationType.TappedUntappedPermanent) { it.withIntDetail("tapped", 0) }
    val wrongSource = messages.mutatingAnnotation(AnnotationType.ManaPaid) { it.toBuilder().setAffectorId(0).build() }
    val wrongAbility = messages.mutatingAnnotation(AnnotationType.UserActionTaken) { it.withIntDetail("abilityGrpId", 0) }
    for ((name, mutant) in listOf(
        "untapped source" to untapped,
        "wrong payment source" to wrongSource,
        "wrong mana ability" to wrongAbility,
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
    }
}

private fun checkTokenMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val index =
        messages.indexOfFirst {
            it.hasGameStateMessage() &&
                it.gameStateMessage.gameObjectsList.any { obj -> obj.type == GameObjectType.Token }
        }
    val frame = messages[index]
    val tokenIndex = frame.gameStateMessage.gameObjectsList.indexOfFirst { it.type == GameObjectType.Token }
    val token = frame.gameStateMessage.getGameObjects(tokenIndex)
    for ((name, mutant) in listOf(
        "wrong token parent" to token.toBuilder().setParentId(0).build(),
        "wrong token source" to token.toBuilder().setObjectSourceGrpId(0).build(),
    )) {
        val changed = frame.toBuilder().setGameStateMessage(frame.gameStateMessage.toBuilder().setGameObjects(tokenIndex, mutant)).build()
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(index, changed)) } }
    }
}

private fun List<GREToClientMessage>.mutatingAnnotation(
    type: AnnotationType,
    persistent: Boolean = false,
    mutate: (AnnotationInfo) -> AnnotationInfo,
): List<GREToClientMessage> =
    map { message ->
        if (!message.hasGameStateMessage()) {
            message
        } else {
            val gsm = message.gameStateMessage
            val annotations = if (persistent) gsm.persistentAnnotationsList else gsm.annotationsList
            val changed = annotations.map { if (type in it.typeList) mutate(it) else it }
            val builder = gsm.toBuilder()
            if (persistent) {
                builder.clearPersistentAnnotations().addAllPersistentAnnotations(
                    changed,
                )
            } else {
                builder.clearAnnotations().addAllAnnotations(changed)
            }
            message.toBuilder().setGameStateMessage(builder).build()
        }
    }

private fun AnnotationInfo.withIntDetail(
    key: String,
    value: Int,
): AnnotationInfo =
    toBuilder().setDetails(detailsList.indexOfFirst { it.key == key }, detail(key)!!.toBuilder().setValueInt32(0, value)).build()

private fun List<GREToClientMessage>.withoutRowDeletion(id: Int): List<GREToClientMessage> =
    map { message ->
        if (!message.hasGameStateMessage()) {
            message
        } else {
            message
                .toBuilder()
                .setGameStateMessage(
                    message.gameStateMessage.toBuilder().clearDiffDeletedPersistentAnnotationIds().addAllDiffDeletedPersistentAnnotationIds(
                        message.gameStateMessage.diffDeletedPersistentAnnotationIdsList.filter { it != id },
                    ),
                ).build()
        }
    }

private fun List<GREToClientMessage>.replacing(
    index: Int,
    message: GREToClientMessage,
): List<GREToClientMessage> = toMutableList().also { it[index] = message }
