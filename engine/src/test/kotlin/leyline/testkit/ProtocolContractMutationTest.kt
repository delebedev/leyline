package leyline.testkit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.acceptance.AcceptancePaths
import leyline.acceptance.AcceptanceSuiteLoader
import leyline.acceptance.MatchdoorAcceptanceExecutor
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CounterType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

/**
 * Regression checks that selected authored contracts reject altered message streams.
 * Each case verifies the unmodified scenario output before applying mutations, so a
 * broken baseline cannot make a rejection pass. Contract discovery belongs to the
 * conformance runner; these examples run in the integration lane.
 */
class ProtocolContractMutationTest :
    FunSpec({
        tags(IntegrationTag)

        fun regression(
            file: String,
            check: (ProtocolContract, List<GREToClientMessage>) -> Unit,
        ) {
            val contract = ProtocolContract.load(AcceptancePaths.resolve("conformance/contracts/$file"))
            test("${contract.name} rejects altered output") {
                val scenario = AcceptanceSuiteLoader.load(contract.suite).scenarios.single { it.id == contract.scenario }
                MatchdoorAcceptanceExecutor().runScenario(scenario) { messages ->
                    withClue(contract.name) {
                        contract.verify(messages)
                        check(contract, messages)
                    }
                } shouldBe scenario.steps.size
            }
        }

        regression("depart-the-realm-foretell.yaml") { contract, messages ->
            for ((name, mutant) in listOf(
                "wrong foretell destination" to
                    messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                        if (it.detailString("category") == "Foretell") it.withIntDetail("zone_dest", 33) else it
                    },
                "wrong special action" to
                    messages.mutatingAnnotation(AnnotationType.UserActionTaken) {
                        if (it.detailInt("actionType") == 7) it.withIntDetail("abilityGrpId", 0) else it
                    },
                "wrong face-down reason" to
                    messages.mutatingAnnotation(AnnotationType.FaceDown, persistent = true) {
                        it.withIntDetail("REASON", 6)
                    },
                "wrong suppressed identity" to
                    messages.mutatingAnnotation(AnnotationType.SuppressedPowerAndToughness, persistent = true) {
                        it.toBuilder().setAffectorId(0).build()
                    },
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
            }
        }
        regression("miscalculation-cycling.yaml") { contract, messages ->
            for ((name, mutant) in listOf(
                "wrong activation source" to
                    messages.mutatingAnnotation(AnnotationType.AbilityInstanceCreated) {
                        if (it.detailInt("source_zone") == 31) it.withIntDetail("source_zone", 28) else it
                    },
                "wrong draw owner" to
                    messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                        if (it.detailString("category") == "Draw") it.toBuilder().setAffectorId(0).build() else it
                    },
                "wrong resolution ability" to
                    messages.mutatingAnnotation(
                        AnnotationType.ResolutionStart,
                    ) { it.withIntDetail("grpid", 0) },
                "missing retirement" to
                    messages.mutatingAnnotation(AnnotationType.AbilityInstanceDeleted) {
                        it.toBuilder().clearAffectedIds().build()
                    },
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
            }
            val index =
                messages.indexOfFirst {
                    it.gameStateMessage.annotationsList.any { a ->
                        AnnotationType.AbilityInstanceCreated in a.typeList && a.detailInt("source_zone") == 31
                    }
                }
            val created =
                messages[index].gameStateMessage.annotationsList.single {
                    AnnotationType.AbilityInstanceCreated in it.typeList && it.detailInt("source_zone") == 31
                }
            val early =
                created
                    .toBuilder()
                    .setId(99999)
                    .clearType()
                    .addType(AnnotationType.AbilityInstanceDeleted)
                    .clearDetails()
                    .build()
            val message =
                messages[index]
                    .toBuilder()
                    .setGameStateMessage(
                        messages[index].gameStateMessage.toBuilder().addAnnotations(early),
                    ).build()
            withClue("premature retirement") { shouldThrow<AssertionError> { contract.verify(messages.replacing(index, message)) } }
        }
        regression("stock-up-bottom-order.yaml") { contract, messages ->
            val selection = messages.indexOfFirst { it.hasSelectNReq() }
            val ordering = messages.indexOfFirst { it.hasOrderReq() }
            val prompt = messages[selection]
            val order = messages[ordering]
            for ((name, mutant) in listOf(
                "wrong selection bounds" to
                    messages.replacing(selection, prompt.toBuilder().setSelectNReq(prompt.selectNReq.toBuilder().setMaxSel(1)).build()),
                "wrong selection source" to
                    messages.replacing(selection, prompt.toBuilder().setSelectNReq(prompt.selectNReq.toBuilder().setSourceId(0)).build()),
                "wrong ordering domain" to
                    messages.replacing(ordering, order.toBuilder().setOrderReq(order.orderReq.toBuilder().clearIds()).build()),
                "wrong selected transfer" to
                    messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                        if (it.detailString("category") == "Put") it.withIntDetail("zone_dest", 33) else it
                    },
                "repeated selection" to (messages.take(ordering) + prompt + messages.drop(ordering)),
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
            }
        }

        regression("lightning-bolt.yaml", ::checkDamageMutations)
        regression("rabbit-battery-target-selection.yaml", ::checkTargetMutations)
        regression("llanowar-elves.yaml", ::checkManaMutations)
        regression("novice-inspector.yaml") { contract, messages ->
            checkTokenMutations(contract, messages)
            val row = messages.allPersistentAnnotations().single { AnnotationType.TriggeringObject in it.typeList }
            shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) }
            checkRowLifetimeMutations(contract, messages, row)
        }
        regression("usher-of-the-fallen.yaml") { contract, messages ->
            checkTokenMutations(contract, messages)
            val stillAvailable =
                messages.mutatingAnnotation(AnnotationType.AbilityExhausted, persistent = true) {
                    it.withIntDetail("UsesRemaining", 1)
                }
            shouldThrow<AssertionError> { contract.verify(stillAvailable) }
        }
        regression("temple-garden.yaml") { contract, messages ->
            val index = messages.indexOfFirst { it.hasOptionalActionMessage() }
            val prompt = messages[index]
            for ((name, mutant) in listOf(
                "wrong optional source" to
                    prompt.toBuilder().setOptionalActionMessage(prompt.optionalActionMessage.toBuilder().setSourceId(0)).build(),
                "wrong optional prompt" to prompt.toBuilder().setPrompt(prompt.prompt.toBuilder().setPromptId(0)).build(),
                "wrong incoming identity" to
                    prompt
                        .toBuilder()
                        .setPrompt(
                            prompt.prompt.toBuilder().setParameters(
                                0,
                                prompt.prompt
                                    .getParameters(0)
                                    .toBuilder()
                                    .setNumberValue(0),
                            ),
                        ).build(),
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(index, mutant)) } }
            }
            val wrongPayment = messages.mutatingAnnotation(AnnotationType.ModifiedLife) { it.withIntDetail("life", -1) }
            withClue("wrong life payment") { shouldThrow<AssertionError> { contract.verify(wrongPayment) } }
            val wrongAbility =
                messages.mutatingAnnotation(
                    AnnotationType.ReplacementEffect_803b,
                    persistent = true,
                ) { it.withIntDetail("grpid", 0) }
            withClue("wrong replacement ability") { shouldThrow<AssertionError> { contract.verify(wrongAbility) } }
            val row = messages.allPersistentAnnotations().single { AnnotationType.ReplacementEffect_803b in it.typeList }
            withClue(
                "missing replacement retirement",
            ) { shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) } }
            checkRowLifetimeMutations(contract, messages, row)
        }
        regression("lunarch-veteran.yaml") { contract, messages ->
            val wrongZone =
                messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                    if (it.detailString("category") == "CastSpell" &&
                        it.detailInt("zone_src") == 33
                    ) {
                        it.withIntDetail("zone_src", 31)
                    } else {
                        it
                    }
                }
            val wrongIdentity = messages.mutatingAnnotation(AnnotationType.ObjectIdChanged) { it.withIntDetail("new_id", 0) }
            withClue("wrong disturb source zone") { shouldThrow<AssertionError> { contract.verify(wrongZone) } }
            withClue("wrong disturb identity") { shouldThrow<AssertionError> { contract.verify(wrongIdentity) } }
        }
        regression("signaling-roar.yaml") { contract, messages ->
            val wrongZone =
                messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                    if (it.detailString("category") == "Resolve") {
                        it.withIntDetail("zone_dest", 33)
                    } else {
                        it
                    }
                }
            val wrongIdentity = messages.mutatingAnnotation(AnnotationType.ObjectIdChanged) { it.withIntDetail("orig_id", 0) }
            withClue("wrong Omen destination") { shouldThrow<AssertionError> { contract.verify(wrongZone) } }
            withClue("wrong Omen identity") { shouldThrow<AssertionError> { contract.verify(wrongIdentity) } }
        }
        regression("siege-defense-counters.yaml", ::checkCounterMutations)
        regression("case-gateway-express.yaml") { contract, messages ->
            val threshold =
                messages.mutatingAnnotation(
                    AnnotationType.AbilityWordActive,
                    persistent = true,
                ) { it.withIntDetail("threshold", 4) }
            val designation =
                messages.mutatingAnnotation(
                    AnnotationType.Designation,
                    persistent = true,
                ) { it.withIntDetail("DesignationType", 0) }
            val source = messages.mutatingAnnotation(AnnotationType.AbilityInstanceCreated) { it.toBuilder().setAffectorId(0).build() }
            val progress =
                messages.mutatingAnnotation(AnnotationType.AbilityWordActive, persistent = true) {
                    if (it.detailInt("value") == 3) it.withIntDetail("value", 2) else it
                }
            val tracker = messages.allPersistentAnnotations().first { AnnotationType.AbilityWordActive in it.typeList }
            val index =
                messages.indexOfFirst {
                    it.hasGameStateMessage() &&
                        it.gameStateMessage.annotationsList.any { a ->
                            AnnotationType.AbilityInstanceCreated in a.typeList &&
                                a.affectorId == tracker.affectorId
                        }
                }
            val frame = messages[index]
            val createdIndex =
                frame.gameStateMessage.annotationsList.indexOfFirst {
                    AnnotationType.AbilityInstanceCreated in it.typeList &&
                        it.affectorId == tracker.affectorId
                }
            val duplicate =
                frame
                    .toBuilder()
                    .setGameStateMessage(
                        frame.gameStateMessage.toBuilder().addAnnotations(
                            createdIndex + 1,
                            frame.gameStateMessage.getAnnotations(createdIndex),
                        ),
                    ).build()
            for ((name, mutant) in listOf(
                "threshold" to threshold,
                "designation" to designation,
                "source" to source,
                "progress" to progress,
                "duplicate trigger" to messages.replacing(index, duplicate),
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
            }
        }
        regression("quantum-riddler-delayed-exile.yaml") { contract, messages ->
            val index =
                messages.indexOfFirst {
                    it.hasGameStateMessage() &&
                        it.gameStateMessage.gameObjectsList.any { obj -> obj.type == GameObjectType.TriggerHolder }
                }
            val message = messages[index]
            val objectIndex = message.gameStateMessage.gameObjectsList.indexOfFirst { it.type == GameObjectType.TriggerHolder }
            val holder = message.gameStateMessage.getGameObjects(objectIndex)
            val badParent =
                message
                    .toBuilder()
                    .setGameStateMessage(
                        message.gameStateMessage.toBuilder().setGameObjects(objectIndex, holder.toBuilder().setParentId(0)),
                    ).build()
            val badSource =
                message
                    .toBuilder()
                    .setGameStateMessage(
                        message.gameStateMessage.toBuilder().setGameObjects(objectIndex, holder.toBuilder().setObjectSourceGrpId(0)),
                    ).build()
            val wrongDestination =
                messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                    if (it.detailString("category") == "Warp") it.withIntDetail("zone_dest", 33) else it
                }
            val row = messages.allPersistentAnnotations().first { AnnotationType.DelayedTriggerAffectees in it.typeList }
            for ((name, mutant) in listOf(
                "holder parent" to messages.replacing(index, badParent),
                "holder source" to messages.replacing(index, badSource),
                "exile destination" to wrongDestination,
                "pending retirement" to messages.withoutRowDeletion(row.id),
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
            }
        }
        regression("brutal-cathar-night-transform.yaml", ::checkTransformMutations)
        regression("hidden-courtyard-discovered-spell-cast.yaml", ::checkDiscoverMutations)
        regression("vren-ward.yaml") { contract, messages ->
            val rows = messages.allPersistentAnnotations().filter { AnnotationType.TriggeringObject in it.typeList }.distinctBy { it.id }
            val permanent =
                messages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.gameObjectsList }.first {
                    it.type ==
                        GameObjectType.Card &&
                        it.zoneId == 28 &&
                        it.controllerSeatId == 2
                }
            val created = messages.annotationsOfType(AnnotationType.AbilityInstanceCreated).first { it.affectorId == permanent.instanceId }
            val row = rows.single { it.affectorId == created.affectedIdsList.single() }
            val birth = messages.indexOfFirst { it.hasGameStateMessage() && row in it.gameStateMessage.persistentAnnotationsList }
            val retired = messages.annotationsOfType(AnnotationType.AbilityInstanceDeleted).single { row.affectorId in it.affectedIdsList }
            val early = GREToClientMessage.newBuilder().setGameStateMessage(GameStateMessage.newBuilder().addAnnotations(retired)).build()
            val premature = messages.toMutableList().also { it.add(birth + 1, early) }
            val wrongSource =
                messages.mutatingAnnotation(AnnotationType.TriggeringObject, persistent = true) {
                    it.toBuilder().setAffectorId(0).build()
                }
            for ((name, mutant) in listOf(
                "premature ability retirement" to premature,
                "wrong trigger source" to wrongSource,
                "missing trigger-source retirement" to messages.withoutRowDeletion(row.id),
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
            }
        }
    })

private fun checkTransformMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val front =
        messages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.gameObjectsList }.first {
            it.type == GameObjectType.Card && it.zoneId == 28 && it.controllerSeatId == 1 && it.othersideGrpId != 0
        }
    val index =
        messages.indexOfFirst {
            it.hasGameStateMessage() &&
                it.gameStateMessage.gameObjectsList.any { obj ->
                    obj.instanceId == front.instanceId && obj.grpId == front.othersideGrpId
                }
        }
    val frame = messages[index]
    val objectIndex = frame.gameStateMessage.gameObjectsList.indexOfFirst { it.instanceId == front.instanceId }
    val transformed = frame.gameStateMessage.getGameObjects(objectIndex)
    for ((name, mutant) in listOf(
        "wrong back face" to transformed.toBuilder().setGrpId(front.grpId).build(),
        "wrong reciprocal face" to transformed.toBuilder().setOthersideGrpId(0).build(),
        "reallocated transform" to transformed.toBuilder().setInstanceId(0).build(),
        "wrong transform zone" to transformed.toBuilder().setZoneId(29).build(),
    )) {
        val changed = frame.toBuilder().setGameStateMessage(frame.gameStateMessage.toBuilder().setGameObjects(objectIndex, mutant)).build()
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(index, changed)) } }
    }
}

private fun checkDiscoverMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val promptIndex = messages.indexOfFirst { it.hasActionsAvailableReq() && it.prompt.promptId == 1134 }
    val prompt = messages[promptIndex]
    val cast = prompt.actionsAvailableReq.getActions(0)
    for ((name, action) in listOf(
        "wrong offered exile identity" to cast.toBuilder().setInstanceId(0).build(),
        "wrong offered ability" to cast.toBuilder().setAbilityGrpId(0).build(),
    )) {
        val changed = prompt.toBuilder().setActionsAvailableReq(prompt.actionsAvailableReq.toBuilder().setActions(0, action)).build()
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(promptIndex, changed)) } }
    }
    val wrongIdentity =
        messages.mutatingAnnotation(AnnotationType.ObjectIdChanged) {
            if (it.detailInt("orig_id") == cast.instanceId) it.withIntDetail("new_id", 0) else it
        }
    withClue("wrong stack reallocation") { shouldThrow<AssertionError> { contract.verify(wrongIdentity) } }
    val row = messages.allPersistentAnnotations().first { AnnotationType.CastingTimeOption in it.typeList }
    val prematureOption =
        GREToClientMessage
            .newBuilder()
            .setGameStateMessage(
                GameStateMessage.newBuilder().addPersistentAnnotations(
                    row.toBuilder().setId(
                        row.id + 1_000_000,
                    ),
                ),
            ).build()
    val beforeCast = messages.toMutableList().also { it.add(promptIndex + 1, prematureOption) }
    withClue("option before accepted cast") { shouldThrow<AssertionError> { contract.verify(beforeCast) } }
    val birth = messages.indexOfFirst { it.hasGameStateMessage() && row in it.gameStateMessage.persistentAnnotationsList }
    val earlyDelete =
        GREToClientMessage
            .newBuilder()
            .setGameStateMessage(
                GameStateMessage.newBuilder().addDiffDeletedPersistentAnnotationIds(row.id),
            ).build()
    val retiredEarly = messages.toMutableList().also { it.add(birth + 1, earlyDelete) }
    withClue("premature option retirement") { shouldThrow<AssertionError> { contract.verify(retiredEarly) } }
    withClue("missing option retirement") { shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) } }
}

private fun checkCounterMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    for (type in listOf(AnnotationType.CounterAdded, AnnotationType.CounterRemoved)) {
        val stringType =
            messages.mutatingAnnotation(type) {
                it
                    .toBuilder()
                    .setDetails(
                        it.detailsList.indexOfFirst { detail -> detail.key == "counter_type" },
                        it
                            .detail("counter_type")!!
                            .toBuilder()
                            .setType(KeyValuePairValueType.String)
                            .clearValueInt32()
                            .addValueString("Defense"),
                    ).build()
            }
        val wrongType = messages.mutatingAnnotation(type) { it.withIntDetail("counter_type", CounterType.P1P1.number) }
        val wrongAmount = messages.mutatingAnnotation(type) { it.withIntDetail("transaction_amount", 2) }
        val wrongCard = messages.mutatingAnnotation(type) { it.toBuilder().setAffectedIds(0, 0).build() }
        val duplicate =
            GREToClientMessage
                .newBuilder()
                .setGameStateMessage(
                    GameStateMessage.newBuilder().addAnnotations(messages.annotationsOfType(type).single()),
                ).build()
        for ((name, mutant) in listOf(
            "string type" to stringType,
            "wrong type" to wrongType,
            "wrong amount" to wrongAmount,
            "wrong card" to wrongCard,
            "duplicate delta" to messages + duplicate,
        )) {
            withClue("$type $name") { shouldThrow<AssertionError> { contract.verify(mutant) } }
        }
    }
    for (count in listOf(3, 0)) {
        val wrongCount =
            messages.mutatingAnnotation(AnnotationType.Counter_803b, persistent = true) {
                if (it.detailInt("count") == count) it.withIntDetail("count", count + 1) else it
            }
        withClue("wrong persistent count $count") { shouldThrow<AssertionError> { contract.verify(wrongCount) } }
    }
    val wrongPersistentType =
        messages.mutatingAnnotation(AnnotationType.Counter_803b, persistent = true) {
            it.withIntDetail("counter_type", CounterType.P1P1.number)
        }
    val extraPersistentCard =
        messages.mutatingAnnotation(AnnotationType.Counter_803b, persistent = true) { it.toBuilder().addAffectedIds(0).build() }
    withClue("wrong persistent counter type") { shouldThrow<AssertionError> { contract.verify(wrongPersistentType) } }
    withClue("extra persistent counter target") { shouldThrow<AssertionError> { contract.verify(extraPersistentCard) } }
    val initialRow =
        messages.allPersistentAnnotations().first {
            AnnotationType.Counter_803b in it.typeList && it.detailInt("counter_type") == CounterType.Defense.number
        }
    withClue("missing initial counter retirement") {
        shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(initialRow.id)) }
    }
}

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
    checkRowLifetimeMutations(contract, messages, row)
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
    checkRowLifetimeMutations(contract, messages, row)
}

private fun checkRowLifetimeMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
    row: AnnotationInfo,
) {
    val birth = messages.indexOfFirst { it.hasGameStateMessage() && row in it.gameStateMessage.persistentAnnotationsList }
    val frame = messages[birth]

    fun rowMessage(value: AnnotationInfo): GREToClientMessage =
        frame.toBuilder().setGameStateMessage(GameStateMessage.newBuilder().addPersistentAnnotations(value)).build()
    val update =
        rowMessage(
            row
                .toBuilder()
                .clearAffectedIds()
                .addAffectedIds(0)
                .build(),
        )
    val changed = messages.toMutableList().also { it.add(birth + 1, update) }
    withClue("contradictory row update") { shouldThrow<AssertionError> { contract.verify(changed) } }
    withClue("retired row reintroduced") { shouldThrow<AssertionError> { contract.verify(messages + rowMessage(row)) } }
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
