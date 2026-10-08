package leyline.testkit

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.acceptance.AcceptancePaths
import leyline.acceptance.AcceptanceSuiteLoader
import leyline.acceptance.MatchdoorAcceptanceExecutor
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantChecker
import leyline.game.bundle.RuntimeAccumulator
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AttackState
import wotc.mtgo.gre.external.messaging.Messages.BlockState
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
        val regressions = mutableMapOf<ProtocolContract, (ProtocolContract, List<GREToClientMessage>) -> Unit>()

        fun regression(
            file: String,
            check: (ProtocolContract, List<GREToClientMessage>) -> Unit,
        ) {
            val contract = ProtocolContract.load(AcceptancePaths.resolve("conformance/contracts/$file"))
            regressions[contract] = check
        }

        for (file in listOf("combat-attack-main2.yaml", "combat-trade-attacker.yaml", "combat-trade-blocker.yaml")) {
            regression(file, ::checkCombatMutations)
        }
        regression("core-cleanup-discard.yaml", ::checkCleanupDiscardMutations)
        regression("core-life-total-loss.yaml", ::checkLifeTotalLossMutations)

        regression("kaito-phasing-chronology.yaml") { contract, messages ->
            val wrongOrder =
                messages.map { message ->
                    message.mutatingGameState { gsm ->
                        val rows = gsm.annotationsList
                        if (rows.none { AnnotationType.PhasedIn in it.typeList }) {
                            gsm
                        } else {
                            val phases = rows.filter { AnnotationType.PhaseOrStepModified in it.typeList }
                            val rest = rows.filterNot { AnnotationType.PhaseOrStepModified in it.typeList }
                            gsm.clearAnnotations().addAllAnnotations(phases + rest)
                        }
                    }
                }
            val duplicate =
                messages.map { message ->
                    message.mutatingGameState { gsm ->
                        gsm.annotationsList.singleOrNull { AnnotationType.PhasedIn in it.typeList }?.let(gsm::addAnnotations)
                        gsm
                    }
                }
            shouldThrow<AssertionError> { contract.verify(wrongOrder) }
            shouldThrow<AssertionError> { contract.verify(duplicate) }
        }

        regression("rabbit-battery.yaml") { contract, messages ->
            for ((name, mutant) in listOf(
                "wrong attach action" to
                    messages.mutatingAnnotation(AnnotationType.UserActionTaken) {
                        if (it.detailInt("actionType") == 2 &&
                            it.detailInt("abilityGrpId") == 243
                        ) {
                            it.withIntDetail("abilityGrpId", 0)
                        } else {
                            it
                        }
                    },
                "wrong attachment target" to
                    messages.mutatingAnnotation(AnnotationType.AttachmentCreated) {
                        it
                            .toBuilder()
                            .clearAffectedIds()
                            .addAffectedIds(0)
                            .build()
                    },
                "wrong attachment source" to
                    messages.mutatingAnnotation(AnnotationType.AttachmentCreated) {
                        it.toBuilder().setAffectorId(0).build()
                    },
                "wrong layer source" to
                    messages.mutatingAnnotation(AnnotationType.LayeredEffectCreated) {
                        it.toBuilder().setAffectorId(0).build()
                    },
                "wrong target wording" to
                    messages.mutatingAnnotation(AnnotationType.TargetSpec, persistent = true) {
                        it.withIntDetail("promptId", 10)
                    },
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
            }
        }
        regression("depart-the-realm-foretold-cast.yaml") { contract, messages ->
            for ((name, mutant) in listOf(
                "wrong alternative cost" to
                    messages.mutatingAnnotation(AnnotationType.CastingTimeOption, persistent = true) {
                        it.withIntDetail("alternateCostGrpId", 0)
                    },
                "wrong target wording" to
                    messages.mutatingAnnotation(AnnotationType.TargetSpec, persistent = true) {
                        it.withIntDetail("promptId", 10)
                    },
                "wrong return destination" to
                    messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                        if (it.detailString("category") == "Return") it.withIntDetail("zone_dest", 31) else it
                    },
                "wrong return source" to
                    messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                        if (it.detailString("category") == "Return") it.toBuilder().setAffectorId(0).build() else it
                    },
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
            }
            val row = messages.allPersistentAnnotations().first { AnnotationType.CastingTimeOption in it.typeList }
            shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) }
            checkRowLifetimeMutations(contract, messages, row)
            checkDuplicateCastingOption(contract, messages, row)
        }
        regression("quantum-riddler-warp-entry-draw.yaml", ::checkInitialWarpMutations)
        regression("hidden-courtyard-discover-skip-link.yaml", ::checkDiscoverLinkMutations)
        regression("quantum-riddler-exile-cast.yaml") { contract, messages ->
            val exileId =
                messages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .first { it.type == GameObjectType.Card && it.zoneId == 29 && it.controllerSeatId == 1 }
                    .instanceId
            val withoutPrediction =
                messages.map { message ->
                    message.mutatingActions { action ->
                        if (action.actionType == ActionType.Cast && action.instanceId == exileId) {
                            action.toBuilder().clearAutoTapSolution().build()
                        } else {
                            action
                        }
                    }
                }
            shouldThrow<AssertionError> { contract.verify(withoutPrediction) }
            for ((name, mutant) in listOf(
                "wrong cast permission" to
                    messages.mutatingAnnotation(AnnotationType.UserActionTaken) {
                        if (it.detailInt("abilityGrpId") == 371) it.withIntDetail("abilityGrpId", 0) else it
                    },
                "wrong option permission" to
                    messages.mutatingAnnotation(AnnotationType.CastingTimeOption, persistent = true) {
                        if (it.detailsList.any { detail ->
                                detail.key == "castAbilityGrpId" && 371 in detail.valueInt32List
                            }
                        ) {
                            it.withIntDetail("castAbilityGrpId", 0)
                        } else {
                            it
                        }
                    },
                "wrong draw owner" to
                    messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                        if (it.detailString("category") == "Draw") it.toBuilder().setAffectorId(0).build() else it
                    },
                "wrong trigger source" to
                    messages.mutatingAnnotation(AnnotationType.TriggeringObject, persistent = true) {
                        it
                            .toBuilder()
                            .clearAffectedIds()
                            .addAffectedIds(0)
                            .build()
                    },
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
            }
            val row =
                messages.allPersistentAnnotations().first {
                    AnnotationType.CastingTimeOption in it.typeList &&
                        it.detailsList.any { detail -> detail.key == "castAbilityGrpId" && 371 in detail.valueInt32List }
                }
            shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) }
            checkRetirementMutations(contract, messages, row)
            checkDuplicateCastingOption(contract, messages, row)
            checkIdenticalRepublication(contract, messages, row)
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
            val wrongOffer =
                messages.map { message ->
                    message.mutatingActions { action ->
                        if (action.actionType == ActionType.Activate_add3) action.toBuilder().setAbilityGrpId(0).build() else action
                    }
                }
            withClue("offered Cycling identity differs from activation") { shouldThrow<AssertionError> { contract.verify(wrongOffer) } }
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

        regression("lightning-bolt.yaml") { contract, messages ->
            checkDamageMutations(contract, messages)
            checkSpellTransitionMutations(messages)
        }
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
        regression("origin-spider-man-final-chapter.yaml") { contract, messages ->
            val wrongSource =
                messages
                    .mutatingAnnotation(AnnotationType.Counter_803b, persistent = true) {
                        if (it.detailInt("count") == 3) {
                            it
                                .toBuilder()
                                .clearAffectedIds()
                                .addAffectedIds(0)
                                .build()
                        } else {
                            it
                        }
                    }.mutatingAnnotation(AnnotationType.AbilityInstanceDeleted) {
                        it.toBuilder().setAffectorId(0).build()
                    }.mutatingAnnotation(AnnotationType.ObjectIdChanged) { it.withIntDetail("orig_id", 0) }
            val earlySacrifice =
                messages.map { message ->
                    message.mutatingGameState { gsm ->
                        val rows = gsm.annotationsList
                        val sacrifice =
                            rows.singleOrNull {
                                AnnotationType.ZoneTransfer_af5a in it.typeList &&
                                    it.detailString("category") == "Sacrifice"
                            }
                        if (sacrifice != null) {
                            val reordered = listOf(sacrifice) + (rows - sacrifice)
                            gsm.clearAnnotations().addAllAnnotations(reordered)
                        }
                        gsm
                    }
                }
            withClue("wrong Saga source") { shouldThrow<AssertionError> { contract.verify(wrongSource) } }
            withClue("sacrifice before final chapter resolution") { shouldThrow<AssertionError> { contract.verify(earlySacrifice) } }
        }
        regression("signaling-roar.yaml") { contract, messages ->
            for (type in listOf(AnnotationType.ResolutionStart, AnnotationType.TokenCreated)) {
                val duplicate =
                    messages.map { message ->
                        message.mutatingGameState { gsm ->
                            gsm.annotationsList.singleOrNull { type in it.typeList }?.let(gsm::addAnnotations)
                            gsm
                        }
                    }
                withClue("duplicate Omen $type") { shouldThrow<AssertionError> { contract.verify(duplicate) } }
            }
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

            // Local implementation guarantee, separate from the contract's delayed-exile shape.
            fun checkNoTemporaryPermanent(stream: List<GREToClientMessage>) {
                stream.allPersistentAnnotations().none {
                    AnnotationType.TemporaryPermanent in it.typeList && row.affectedIdsList.single() in it.affectedIdsList
                } shouldBe true
            }
            checkNoTemporaryPermanent(messages)
            val temporary =
                messages.mutatingAnnotation(AnnotationType.DelayedTriggerAffectees, persistent = true) {
                    it.toBuilder().addType(AnnotationType.TemporaryPermanent).build()
                }
            shouldThrow<AssertionError> { checkNoTemporaryPermanent(temporary) }
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
        regression("brutal-cathar-entry-exile.yaml", ::checkEntryTriggerMutations)
        regression("deep-cavern-bat-target-look-exile.yaml", ::checkPrivateHandMutations)
        for ((identity, cases) in regressions.entries.groupBy { it.key.suite to it.key.scenario }) {
            test("${identity.first}/${identity.second} rejects altered output") {
                val scenario = AcceptanceSuiteLoader.load(identity.first).scenarios.single { it.id == identity.second }
                MatchdoorAcceptanceExecutor().runScenario(scenario) { messages ->
                    ProtocolContract.verifyAll(cases.map { it.key }, messages)
                    val results = cases.map { (contract, check) -> contract.name to runCatching { check(contract, messages) } }
                    assertSoftly {
                        for ((name, result) in results) withClue(name) { result.exceptionOrNull() shouldBe null }
                    }
                } shouldBe scenario.steps.size
            }
        }
    })

private fun checkCombatMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val mutants = mutableListOf<Pair<String, List<GREToClientMessage>>>()
    for ((name, mutate) in listOf<Pair<String, (AnnotationInfo) -> AnnotationInfo>>(
        "wrong damage source" to { it.toBuilder().setAffectorId(0).build() },
        "wrong damage recipient" to {
            it
                .toBuilder()
                .clearAffectedIds()
                .addAffectedIds(0)
                .build()
        },
        "wrong damage type" to { it.withIntDetail("type", 2) },
        "wrong damage amount" to { it.withIntDetail("damage", 0) },
    )) {
        mutants += name to messages.mutatingAnnotation(AnnotationType.DamageDealt_af5a, mutate = mutate)
    }
    val damages =
        messages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.annotationsList }.filter {
            AnnotationType.DamageDealt_af5a in
                it.typeList
        }
    for (damage in damages) {
        mutants += "missing damage from ${damage.affectorId}" to
            messages.map { message ->
                message.mutatingGameState { gsm ->
                    val kept = gsm.annotationsList.filterNot { it.id == damage.id }
                    gsm.clearAnnotations().addAllAnnotations(kept)
                }
            }
    }
    mutants += "wrong offered combat identity" to
        messages.map { message ->
            when {
                message.hasDeclareAttackersReq() ->
                    message
                        .toBuilder()
                        .setDeclareAttackersReq(
                            message.declareAttackersReq.toBuilder().setAttackers(
                                0,
                                message.declareAttackersReq
                                    .getAttackers(0)
                                    .toBuilder()
                                    .setAttackerInstanceId(0),
                            ),
                        ).build()
                message.hasDeclareBlockersReq() ->
                    message
                        .toBuilder()
                        .setDeclareBlockersReq(
                            message.declareBlockersReq.toBuilder().setBlockers(
                                0,
                                message.declareBlockersReq
                                    .getBlockers(0)
                                    .toBuilder()
                                    .setBlockerInstanceId(0)
                                    .clearAttackerInstanceIds()
                                    .addAttackerInstanceIds(0),
                            ),
                        ).build()
                else -> message
            }
        }
    mutants += "stale selected combat state reference" to
        messages.map { message ->
            val selected =
                (
                    message.hasDeclareAttackersReq() &&
                        message.declareAttackersReq.attackersList.any { it.hasSelectedDamageRecipient() }
                ) ||
                    (
                        message.hasDeclareBlockersReq() &&
                            message.declareBlockersReq.blockersList.any { it.selectedAttackerInstanceIdsCount > 0 }
                    )
            if (selected) message.toBuilder().setGameStateId(0).build() else message
        }
    mutants += "missing provisional combat state" to
        messages.map { message ->
            message.mutatingGameState { gsm ->
                val objects =
                    gsm.gameObjectsList.map { obj ->
                        obj
                            .toBuilder()
                            .apply {
                                if (obj.attackState ==
                                    AttackState.Declared_a3a9
                                ) {
                                    clearAttackState()
                                }
                                if (obj.blockState == BlockState.Declared_aa2d) clearBlockState()
                            }.build()
                    }
                gsm.clearGameObjects().addAllGameObjects(objects)
            }
        }
    if (damages.size == 2) checkCombatDeathMutations(contract, messages, damages)
    for ((name, mutant) in mutants) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
    }
}

private fun checkCombatDeathMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
    damages: List<AnnotationInfo>,
) {
    val mutants = mutableListOf<Pair<String, List<GREToClientMessage>>>()
    for (key in listOf("orig_id", "new_id")) {
        mutants += "wrong death $key" to
            messages.mutatingAnnotation(AnnotationType.ObjectIdChanged) { it.withIntDetail(key, 0) }
    }
    val sources = damages.map { it.affectorId }.toSet()
    val deathRows =
        messages
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.annotationsList }
            .filter {
                (AnnotationType.ObjectIdChanged in it.typeList && it.detailInt("orig_id") in sources) ||
                    (AnnotationType.ZoneTransfer_af5a in it.typeList && it.detailString("category") == "SBA_Damage")
            }
    val firstDamage = messages.indexOfFirst { it.hasGameStateMessage() && damages.first() in it.gameStateMessage.annotationsList }
    mutants += "death before damage" to
        messages.mapIndexed { index, message ->
            message.mutatingGameState { gsm ->
                val rows = gsm.annotationsList.filterNot { it in deathRows }
                val early = if (index == firstDamage) deathRows + rows else rows
                gsm.clearAnnotations().addAllAnnotations(early)
            }
        }
    val reversedDamage =
        messages.map { message ->
            message.mutatingGameState { gsm ->
                val damage =
                    gsm.annotationsList
                        .filter { AnnotationType.DamageDealt_af5a in it.typeList }
                        .reversed()
                        .iterator()
                val rows = gsm.annotationsList.map { if (AnnotationType.DamageDealt_af5a in it.typeList) damage.next() else it }
                gsm.clearAnnotations().addAllAnnotations(rows)
            }
        }
    contract.verify(reversedDamage)
    val reorderedDeaths =
        deathRows
            .chunked(2)
            .reversed()
            .flatten()
            .iterator()
    val reversedDeaths =
        messages.map { message ->
            message.mutatingGameState { gsm ->
                val rows = gsm.annotationsList.map { if (it in deathRows) reorderedDeaths.next() else it }
                gsm.clearAnnotations().addAllAnnotations(rows)
            }
        }
    contract.verify(reversedDeaths)
    for ((name, mutant) in mutants) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
    }
}

private fun checkPrivateHandMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val choice = messages.first { it.hasSelectNReq() }.selectNReq
    val selectedId = choice.getIds(0)
    val lookIndex =
        messages.indexOfFirst { message ->
            message.hasGameStateMessage() &&
                message.gameStateMessage.gameObjectsList.any {
                    it.instanceId == selectedId &&
                        it.type == GameObjectType.Card &&
                        it.visibility == wotc.mtgo.gre.external.messaging.Messages.Visibility.Private
                }
        }
    check(lookIndex >= 0) { "Selected hand card must have a private view" }
    val leakedRow =
        AnnotationBuilder
            .cardRevealed(InstanceId(choice.sourceId), InstanceId(selectedId), ZoneIds.P2_HAND)
            .toBuilder()
            .setId(999_999)
            .build()
    for ((name, mutant) in listOf(
        "missing sole opponent selection" to messages.filterNot { it.hasSelectTargetsReq() },
        "wrong hand choice envelope" to
            messages.map { message ->
                if (message.hasSelectNReq()) {
                    message.toBuilder().setPrompt(message.prompt.toBuilder().setPromptId(1243)).build()
                } else {
                    message
                }
            },
        "selected card view becomes public" to
            messages.map { message ->
                message.mutatingGameState { gsm ->
                    val objects =
                        gsm.gameObjectsList.map { obj ->
                            if (obj.instanceId == selectedId && obj.type == GameObjectType.Card) {
                                obj.toBuilder().setVisibility(wotc.mtgo.gre.external.messaging.Messages.Visibility.Public).build()
                            } else {
                                obj
                            }
                        }
                    gsm.clearGameObjects().addAllGameObjects(objects)
                }
            },
        "look creates persistent public reveal" to
            messages.mapIndexed { index, message ->
                if (index == lookIndex) message.mutatingGameState { it.addPersistentAnnotations(leakedRow) } else message
            },
        "choice offers a different card identity" to
            messages.map { message ->
                if (message.hasSelectNReq()) {
                    message.toBuilder().setSelectNReq(message.selectNReq.toBuilder().setIds(0, 0)).build()
                } else {
                    message
                }
            },
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
    }
}

private fun checkEntryTriggerMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val target =
        messages
            .persistentAnnotationsOfType(AnnotationType.TargetSpec)
            .distinctBy { it.id }
            .single { it.detailInt("promptId") == 1014 }
    val targetId = target.affectedIdsList.single()
    val changed = messages.annotationsOfType(AnnotationType.ObjectIdChanged).single { it.detailInt("orig_id") == targetId }
    val newTargetId = changed.detailInt("new_id")
    val wrongTarget =
        messages
            .mutatingAnnotation(AnnotationType.TargetSpec, persistent = true) {
                if (it.id == target.id) {
                    it
                        .toBuilder()
                        .clearAffectedIds()
                        .addAffectedIds(0)
                        .build()
                } else {
                    it
                }
            }.mutatingAnnotation(AnnotationType.ObjectIdChanged) {
                if (it == changed) {
                    it
                        .withIntDetail("orig_id", 0)
                        .withIntDetail("new_id", 1)
                        .toBuilder()
                        .clearAffectedIds()
                        .addAffectedIds(0)
                        .build()
                } else {
                    it
                }
            }.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                if (it.detailString("category") == "Exile" && newTargetId in it.affectedIdsList) {
                    it
                        .toBuilder()
                        .clearAffectedIds()
                        .addAffectedIds(1)
                        .build()
                } else {
                    it
                }
            }.mutatingAnnotation(AnnotationType.DisplayCardUnderCard, persistent = true) {
                if (newTargetId in it.affectedIdsList) {
                    it
                        .toBuilder()
                        .clearAffectedIds()
                        .addAffectedIds(1)
                        .build()
                } else {
                    it
                }
            }
    val lateDay =
        messages.map { message ->
            message.mutatingGameState { gsm ->
                val rows = gsm.annotationsList
                val day = rows.singleOrNull { AnnotationType.GainDesignation in it.typeList }
                val created = rows.indexOfFirst { AnnotationType.AbilityInstanceCreated in it.typeList }
                if (day != null && created >= 0) {
                    val reordered = rows.filterNot { it == day }.toMutableList()
                    reordered.add(reordered.indexOfFirst { AnnotationType.AbilityInstanceCreated in it.typeList } + 1, day)
                    gsm.clearAnnotations().addAllAnnotations(reordered)
                } else {
                    gsm
                }
            }
        }
    for ((name, mutant) in listOf(
        "correlated target outside offered candidates" to wrongTarget,
        "entry trigger precedes initial Day" to lateDay,
        "stale entry source zone" to
            messages.mutatingAnnotation(AnnotationType.AbilityInstanceCreated) {
                if (target.affectorId in it.affectedIdsList) it.withIntDetail("source_zone", ZoneIds.STACK) else it
            },
        "wrong target definition" to
            messages.map { message ->
                if (message.hasSelectTargetsReq()) {
                    message.toBuilder().setSelectTargetsReq(message.selectTargetsReq.toBuilder().setAbilityGrpId(0)).build()
                } else {
                    message
                }
            },
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
    }
}

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
        val changed = frame.mutatingGameState { it.setGameObjects(objectIndex, mutant) }
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

private fun checkSpellTransitionMutations(messages: List<GREToClientMessage>) {
    InvariantChecker().also { it.processAll(messages) }.violations.shouldBeEmpty()
    val castIndex =
        messages.indexOfFirst { message ->
            message.hasGameStateMessage() &&
                message.gameStateMessage.annotationsList.any {
                    AnnotationType.ZoneTransfer_af5a in it.typeList && it.detailString("category") == "CastSpell"
                }
        }
    val frame = messages[castIndex].gameStateMessage
    val change = frame.annotation(AnnotationType.ObjectIdChanged)
    val oldId = change.detailInt("orig_id")
    val newId = change.detailInt("new_id")
    val prior = RuntimeAccumulator().also { state -> messages.take(castIndex + 1).forEach(state::process) }
    val hand = prior.zones.getValue(ZoneIds.P1_HAND)
    val stack = prior.zones.getValue(ZoneIds.STACK)
    val mutants =
        listOf(
            "mismatched pair" to
                frame
                    .toBuilder()
                    .setAnnotations(
                        frame.annotationsList.indexOf(change),
                        change.withIntDetail("new_id", newId + 100000),
                    ).build(),
            "unmatched successor cannot hide missing projection" to
                frame
                    .toBuilder()
                    .clearGameObjects()
                    .addAllGameObjects(frame.gameObjectsList.filter { it.instanceId != newId })
                    .clearZones()
                    .addAllZones(frame.zonesList.filter { it.zoneId != ZoneIds.STACK })
                    .addZones(
                        stack.toBuilder().clearObjectInstanceIds().addAllObjectInstanceIds(
                            stack.objectInstanceIdsList.filter {
                                it !=
                                    newId
                            },
                        ),
                    ).addAnnotations(change.withIntDetail("orig_id", newId).withIntDetail("new_id", newId + 100000))
                    .build(),
            "old identity still in hand" to
                frame
                    .toBuilder()
                    .clearZones()
                    .addAllZones(frame.zonesList.filter { it.zoneId != ZoneIds.P1_HAND })
                    .addZones(hand.toBuilder().addObjectInstanceIds(oldId))
                    .build(),
            "missing stack object" to
                frame
                    .toBuilder()
                    .clearGameObjects()
                    .addAllGameObjects(frame.gameObjectsList.filter { it.instanceId != newId })
                    .build(),
            "missing stack membership" to
                frame
                    .toBuilder()
                    .clearZones()
                    .addAllZones(frame.zonesList.filter { it.zoneId != ZoneIds.STACK })
                    .addZones(
                        stack.toBuilder().clearObjectInstanceIds().addAllObjectInstanceIds(
                            stack.objectInstanceIdsList.filter { it != newId },
                        ),
                    ).build(),
        )
    for ((name, mutant) in mutants) {
        withClue(name) {
            val altered = messages.replacing(castIndex, messages[castIndex].toBuilder().setGameStateMessage(mutant).build())
            InvariantChecker()
                .also {
                    it.processAll(
                        altered,
                    )
                }.violations
                .map { it.check }
                .shouldContain(InvariantCheck.ZoneTransitionIdentity.id)
        }
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
            frame.mutatingGameState { it.addAnnotations(damage) },
        "wrong amount" to
            frame.mutatingGameState {
                it.setAnnotations(frame.gameStateMessage.annotationsList.indexOf(damage), wrongDamage)
            },
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(frameIndex, mutant)) } }
    }
    val row = messages.allPersistentAnnotations().single { AnnotationType.TargetSpec in it.typeList }
    val birth = messages.indexOfFirst { it.hasGameStateMessage() && row in it.gameStateMessage.persistentAnnotationsList }
    val premature =
        messages.mapIndexed { index, message ->
            message.mutatingGameState { gsm ->
                val deletions = gsm.diffDeletedPersistentAnnotationIdsList.filter { it != row.id }
                gsm.clearDiffDeletedPersistentAnnotationIds().addAllDiffDeletedPersistentAnnotationIds(
                    deletions + if (index == birth) listOf(row.id) else emptyList(),
                )
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

private fun checkDuplicateCastingOption(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
    row: AnnotationInfo,
) {
    val birth = messages.indexOfFirst { it.hasGameStateMessage() && row in it.gameStateMessage.persistentAnnotationsList }
    val duplicate =
        row
            .toBuilder()
            .setId(9999)
            .addDetails(
                row
                    .getDetails(0)
                    .toBuilder()
                    .setKey("castAbilityGrpId")
                    .clearValueInt32()
                    .addValueInt32(0),
            ).build()
    val extra = messages[birth].toBuilder().setGameStateMessage(GameStateMessage.newBuilder().addPersistentAnnotations(duplicate)).build()
    val mutant = messages.toMutableList().also { it.add(birth + 1, extra) }
    withClue("second casting-option row with a different identity and shape") {
        shouldThrow<AssertionError> { contract.verify(mutant) }
    }
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
        val changed = frame.mutatingGameState { it.setGameObjects(tokenIndex, mutant) }
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(index, changed)) } }
    }
}

internal fun List<GREToClientMessage>.mutatingAnnotation(
    type: AnnotationType,
    persistent: Boolean = false,
    mutate: (AnnotationInfo) -> AnnotationInfo,
): List<GREToClientMessage> =
    map { message ->
        message.mutatingGameState { gsm ->
            val annotations = if (persistent) gsm.persistentAnnotationsList else gsm.annotationsList
            val changed = annotations.map { if (type in it.typeList) mutate(it) else it }
            if (persistent) {
                gsm.clearPersistentAnnotations().addAllPersistentAnnotations(changed)
            } else {
                gsm.clearAnnotations().addAllAnnotations(changed)
            }
        }
    }

internal fun AnnotationInfo.withIntDetail(
    key: String,
    value: Int,
): AnnotationInfo =
    toBuilder().setDetails(detailsList.indexOfFirst { it.key == key }, detail(key)!!.toBuilder().setValueInt32(0, value)).build()

private fun List<GREToClientMessage>.withoutRowDeletion(id: Int): List<GREToClientMessage> =
    map { message ->
        message.mutatingGameState { gsm ->
            val remaining = gsm.diffDeletedPersistentAnnotationIdsList.filter { it != id }
            gsm.clearDiffDeletedPersistentAnnotationIds().addAllDiffDeletedPersistentAnnotationIds(remaining)
        }
    }

internal fun GREToClientMessage.mutatingGameState(mutate: (GameStateMessage.Builder) -> GameStateMessage.Builder): GREToClientMessage =
    if (hasGameStateMessage()) toBuilder().setGameStateMessage(mutate(gameStateMessage.toBuilder())).build() else this

private fun GREToClientMessage.mutatingActions(mutate: (Action) -> Action): GREToClientMessage =
    if (hasActionsAvailableReq()) {
        val actions = actionsAvailableReq.actionsList.map(mutate)
        toBuilder().setActionsAvailableReq(actionsAvailableReq.toBuilder().clearActions().addAllActions(actions)).build()
    } else {
        this
    }

internal fun List<GREToClientMessage>.replacing(
    index: Int,
    message: GREToClientMessage,
): List<GREToClientMessage> = toMutableList().also { it[index] = message }

private fun checkInitialWarpMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    for ((name, type) in listOf("draw cause" to AnnotationType.ZoneTransfer_af5a, "trigger source" to AnnotationType.TriggeringObject)) {
        val changed =
            messages.mutatingAnnotation(type, persistent = type == AnnotationType.TriggeringObject) {
                if (type == AnnotationType.TriggeringObject || it.detailString("category") == "Draw") {
                    it.toBuilder().setAffectorId(0).build()
                } else {
                    it
                }
            }
        withClue(name) { shouldThrow<AssertionError> { contract.verify(changed) } }
    }
    val row = messages.allPersistentAnnotations().first { AnnotationType.TriggeringObject in it.typeList }
    checkRetirementMutations(contract, messages, row)
    checkIdenticalRepublication(contract, messages, row)
}

private fun checkIdenticalRepublication(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
    row: AnnotationInfo,
) {
    val birth = messages.indexOfFirst { it.hasGameStateMessage() && row in it.gameStateMessage.persistentAnnotationsList }
    val publication =
        GREToClientMessage
            .newBuilder()
            .setGameStateMessage(
                GameStateMessage
                    .newBuilder()
                    .setType(
                        wotc.mtgo.gre.external.messaging.Messages.GameStateType.Diff,
                    ).addPersistentAnnotations(row),
            ).build()
    contract.verify(messages.toMutableList().also { it.add(birth + 1, publication) })
}

private fun checkDiscoverLinkMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val wrongOwner =
        messages.mutatingAnnotation(AnnotationType.LinkInfo, persistent = true) {
            it.toBuilder().setAffectorId(0).build()
        }
    withClue("activation link owner") { shouldThrow<AssertionError> { contract.verify(wrongOwner) } }
    val skipped =
        messages
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.gameObjectsList }
            .first {
                it.type == GameObjectType.Card &&
                    it.zoneId == ZoneIds.EXILE &&
                    wotc.mtgo.gre.external.messaging.Messages.CardType.Land_a80b in it.cardTypesList
            }
    val wrongReallocation =
        messages.mutatingAnnotation(AnnotationType.ObjectIdChanged) {
            if (it.detailInt("new_id") == skipped.instanceId) it.withIntDetail("new_id", 0) else it
        }
    withClue("skipped card reallocation") { shouldThrow<AssertionError> { contract.verify(wrongReallocation) } }
    val row = messages.allPersistentAnnotations().first { AnnotationType.LinkInfo in it.typeList }
    checkRowLifetimeMutations(contract, messages, row)
}

private fun checkRetirementMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
    row: AnnotationInfo,
) {
    val birth = messages.indexOfFirst { it.hasGameStateMessage() && row in it.gameStateMessage.persistentAnnotationsList }
    val deleted =
        GREToClientMessage
            .newBuilder()
            .setGameStateMessage(
                GameStateMessage.newBuilder().addDiffDeletedPersistentAnnotationIds(row.id),
            ).build()
    withClue("early retirement") {
        shouldThrow<AssertionError> { contract.verify(messages.toMutableList().also { it.add(birth + 1, deleted) }) }
    }
    withClue("missing retirement") { shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) } }
    val publication = deleted.toBuilder().setGameStateMessage(GameStateMessage.newBuilder().addPersistentAnnotations(row)).build()
    withClue("retired row reappears") { shouldThrow<AssertionError> { contract.verify(messages + publication) } }
}
