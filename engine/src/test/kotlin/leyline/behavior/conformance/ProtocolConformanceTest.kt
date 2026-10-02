package leyline.behavior.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.IntegrationTag
import leyline.acceptance.AcceptanceSuiteLoader
import leyline.acceptance.MatchdoorAcceptanceExecutor
import leyline.testkit.allPersistentAnnotations
import leyline.testkit.annotation
import leyline.testkit.annotationsOfType
import leyline.testkit.detail
import leyline.testkit.detailIntList
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

class ProtocolConformanceTest :
    FunSpec({
        tags(IntegrationTag, ConformanceTag)

        test("spell targeting and resolution conform, and contradictory damage fails") {
            runScenario("warmup", "land-spell-face") { messages ->
                verifyBolt(messages)
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
                    withClue(name) { shouldThrow<AssertionError> { verifyBolt(messages.replacing(frameIndex, mutant)) } }
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
                withClue("premature target retirement") { shouldThrow<AssertionError> { verifyBolt(premature) } }
            }
        }

        test("reconfigure targeting conforms, and malformed groups or identity fail") {
            runScenario("mechanics-warmup", "reconfigure-attach-unattach") { messages ->
                verifyReconfigure(messages)
                val index = messages.indexOfFirst { it.hasSelectTargetsReq() }
                val prompt = messages[index]
                for ((name, mutant) in listOf(
                    "empty target groups" to
                        prompt.toBuilder().setSelectTargetsReq(prompt.selectTargetsReq.toBuilder().clearTargets()).build(),
                    "wrong source" to prompt.toBuilder().setSelectTargetsReq(prompt.selectTargetsReq.toBuilder().setSourceId(0)).build(),
                    "wrong undo flag" to prompt.toBuilder().setAllowUndo(false).build(),
                )) {
                    withClue(name) { shouldThrow<AssertionError> { verifyReconfigure(messages.replacing(index, mutant)) } }
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
                withClue("missing target retirement") { shouldThrow<AssertionError> { verifyReconfigure(missingRetirement) } }
            }
        }
    })

private fun runScenario(
    suite: String,
    id: String,
    verify: (List<GREToClientMessage>) -> Unit,
) {
    val scenario = AcceptanceSuiteLoader.load(suite).scenarios.single { it.id == id }
    MatchdoorAcceptanceExecutor().runScenario(scenario) { messages ->
        withClue("$suite/$id") { verify(messages) }
    } shouldBe scenario.steps.size
}

private fun verifyBolt(messages: List<GREToClientMessage>) {
    val frames = messages.gameStateMessages()
    val cast =
        messages.annotationsOfType(AnnotationType.ZoneTransfer_af5a).single {
            it.detail("category")?.valueStringList ==
                listOf("CastSpell")
        }
    val spell = cast.affectedIdsList.single()
    val castIndex = frames.indexOfFirst { cast in it.annotationsList }
    val submitted = messages.annotationsOfType(AnnotationType.PlayerSubmittedTargets).single { spell in it.affectedIdsList }
    val submittedIndex = frames.indexOfFirst { submitted in it.annotationsList }
    val target = messages.allPersistentAnnotations().single { AnnotationType.TargetSpec in it.typeList && it.affectorId == spell }
    val resolutionIndex =
        frames.indexOfFirst { gsm ->
            gsm.annotationsList.any {
                AnnotationType.ResolutionStart in it.typeList &&
                    it.affectorId == spell
            }
        }
    val resolution = frames[resolutionIndex]
    val damage = messages.annotationsOfType(AnnotationType.DamageDealt_af5a)
    damage.size shouldBe 1
    assertSoftly {
        cast.detailIntList("zone_src") shouldBe listOf(31)
        cast.detailIntList("zone_dest") shouldBe listOf(27)
        cast.detailsList.map { it.key }.sorted() shouldBe listOf("category", "zone_dest", "zone_src")
        frames[castIndex].gameObjectsList.count { it.instanceId == spell && it.zoneId == 27 } shouldBe 1
        val allocated = frames[castIndex].annotation(AnnotationType.ObjectIdChanged)
        val selecting = frames[castIndex].annotation(AnnotationType.PlayerSelectingTargets)
        allocated.detailIntList("new_id") shouldBe listOf(spell)
        selecting.affectedIdsList shouldBe listOf(spell)
        selecting.detailsCount shouldBe 0
        frames[castIndex].annotationsList.indexOf(cast) shouldBeGreaterThan frames[castIndex].annotationsList.indexOf(allocated)
        frames[castIndex].annotationsList.indexOf(selecting) shouldBeGreaterThan frames[castIndex].annotationsList.indexOf(cast)
        submittedIndex shouldBeGreaterThan castIndex
        submitted.detailsCount shouldBe 0
        val mana = frames[submittedIndex].annotationsList.single { AnnotationType.ManaPaid in it.typeList && spell in it.affectedIdsList }
        mana.detailsList.map { it.key }.sorted() shouldBe listOf("color", "id")
        val action =
            frames[submittedIndex].annotationsList.single {
                AnnotationType.UserActionTaken in it.typeList &&
                    spell in it.affectedIdsList
            }
        action.affectedIdsList shouldBe listOf(spell)
        action.affectorId shouldBe submitted.affectorId
        action.detailsList.map { it.key }.sorted() shouldBe listOf("abilityGrpId", "actionType")
        action.detailIntList("actionType") shouldBe listOf(1)
        action.detailIntList("abilityGrpId") shouldBe listOf(0)
        frames[submittedIndex].persistentAnnotationsList shouldContain target
        target.affectedIdsList shouldBe listOf(2)
        target.detailsList.map { it.key }.sorted() shouldBe listOf("abilityGrpId", "index", "promptId", "promptParameters")
        target.detailIntList("index") shouldBe listOf(1)
        target.detailIntList("promptId") shouldBe listOf(11869)
        resolutionIndex shouldBeGreaterThan submittedIndex
        resolution.annotationsList.filter { it.affectorId == spell }.flatMap { it.typeList }.filter {
            it in
                listOf(AnnotationType.ResolutionStart, AnnotationType.DamageDealt_af5a, AnnotationType.ResolutionComplete)
        } shouldBe
            listOf(AnnotationType.ResolutionStart, AnnotationType.DamageDealt_af5a, AnnotationType.ResolutionComplete)
        damage.single().affectorId shouldBe spell
        damage.single().affectedIdsList shouldBe target.affectedIdsList
        damage
            .single()
            .detailsList
            .map { it.key }
            .sorted() shouldBe listOf("damage", "markDamage", "type")
        for ((key, value) in mapOf("damage" to 3, "type" to 2, "markDamage" to 1)) {
            damage.single().detail(key)?.type shouldBe KeyValuePairValueType.Int32
            damage.single().detailIntList(key) shouldBe listOf(value)
        }
        val reallocated = resolution.annotation(AnnotationType.ObjectIdChanged)
        reallocated.detailIntList("orig_id") shouldBe listOf(spell)
        val moved = resolution.annotation(AnnotationType.ZoneTransfer_af5a)
        moved.affectedIdsList shouldBe reallocated.detailIntList("new_id")
        moved.detail("category")?.valueStringList shouldBe listOf("Resolve")
        moved.detailIntList("zone_src") shouldBe listOf(27)
        moved.detailIntList("zone_dest") shouldBe listOf(33)
    }
    verifyRetirement(messages, target, resolution)
}

private fun verifyReconfigure(messages: List<GREToClientMessage>) {
    val promptIndex = messages.indexOfFirst { it.hasSelectTargetsReq() }
    promptIndex shouldBeGreaterThanOrEqual 0
    val prompt = messages[promptIndex]
    val request = prompt.selectTargetsReq
    request.targetsCount shouldBe 1
    val group = request.targetsList.single()
    val selecting = messages.annotationsOfType(AnnotationType.PlayerSelectingTargets).single()
    val ability = selecting.affectedIdsList.single()
    val created = messages.annotationsOfType(AnnotationType.AbilityInstanceCreated).single { ability in it.affectedIdsList }
    val createdIndex = messages.indexOfFirst { it.hasGameStateMessage() && created in it.gameStateMessage.annotationsList }
    val submitted = messages.annotationsOfType(AnnotationType.PlayerSubmittedTargets).single()
    val submittedIndex = messages.indexOfFirst { it.hasGameStateMessage() && submitted in it.gameStateMessage.annotationsList }
    val target = messages.allPersistentAnnotations().single { AnnotationType.TargetSpec in it.typeList }
    val resolution =
        messages.gameStateMessages().single { gsm ->
            gsm.annotationsList.any {
                AnnotationType.ResolutionComplete in it.typeList &&
                    it.affectorId == ability
            }
        }
    assertSoftly {
        promptIndex shouldBeGreaterThan createdIndex
        messages[createdIndex].gameStateMessage.annotationsList shouldContain selecting
        created.detailIntList("source_zone") shouldBe listOf(28)
        created.detailsList.map { it.key } shouldBe listOf("source_zone")
        selecting.detailsCount shouldBe 0
        prompt.prompt.promptId shouldBe 10
        prompt.allowCancel shouldBe AllowCancel.Abort
        prompt.allowUndo shouldBe true
        request.sourceId shouldBe ability
        group.targetIdx shouldBe 1
        group.minTargets shouldBe 1
        group.maxTargets shouldBe 1
        group.targetsList.map { it.targetInstanceId } shouldContain target.affectedIdsList.single()
        target.detailIntList("abilityGrpId") shouldBe listOf(group.targetingAbilityGrpId)
        submittedIndex shouldBeGreaterThan promptIndex
        submitted.affectedIdsList shouldBe listOf(ability)
        submitted.detailsCount shouldBe 0
        messages[submittedIndex].gameStateMessage.persistentAnnotationsList shouldContain target
        target.affectorId shouldBe ability
        target.detailIntList("index") shouldBe listOf(1)
    }
    verifyRetirement(messages, target, resolution)
}

private fun verifyRetirement(
    messages: List<GREToClientMessage>,
    row: AnnotationInfo,
    resolution: GameStateMessage,
) {
    val frames = messages.gameStateMessages()
    frames.sumOf { frame -> frame.diffDeletedPersistentAnnotationIdsList.count { it == row.id } } shouldBe 1
    val deletedAt = frames.indexOfFirst { row.id in it.diffDeletedPersistentAnnotationIdsList }
    deletedAt shouldBeGreaterThanOrEqual frames.indexOf(resolution)
}

private fun List<GREToClientMessage>.replacing(
    index: Int,
    message: GREToClientMessage,
): List<GREToClientMessage> = toMutableList().also { it[index] = message }
