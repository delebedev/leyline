package leyline.mechanics.annihilator

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.acceptance.AcceptancePaths
import leyline.bridge.types.InstanceId
import leyline.testkit.ProtocolContract
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.tooling.headless.HeadlessResponseMode
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class AnnihilatorLifecycleTest :
    SessionTest({
        fun puzzle(permanents: String) =
            """
            ActivePlayer=AI
            ActivePhase=Main1
            HumanLife=40
            AILife=20
            humanbattlefield=$permanents
            humanlibrary=Plains;Plains;Plains
            aibattlefield=Emrakul, the Aeons Torn
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        session(
            "human defender chooses six sacrifices before blockers and combat continues",
            puzzle = puzzle("Healer's Hawk;Plains;Island;Swamp;Mountain;Forest;Wastes;Wastes"),
            turns = 4,
            aiScript = listOf(ScriptedAction.Attack(listOf("Emrakul, the Aeons Torn"))),
            fullControl = true,
            responseMode = HeadlessResponseMode.PolicyVisible,
        ) {
            passUntil { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val promptMessage = allMessages.last { it.hasSelectNReq() }
            val selection = promptMessage.selectNReq
            val annotationsBeforeChoice = allMessages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.annotationsList }
            val abilityIid = annotationsBeforeChoice.last { AnnotationType.AbilityInstanceCreated in it.typeList }.affectedIdsList.single()
            annotationsBeforeChoice.count { AnnotationType.ResolutionStart in it.typeList && it.affectorId == abilityIid } shouldBe 1
            annotationsBeforeChoice.count { AnnotationType.ResolutionComplete in it.typeList && it.affectorId == abilityIid } shouldBe 0
            val blocker = human.battlefield.card("Healer's Hawk")
            val blockerIid = human.battlefield.iid("Healer's Hawk")
            val before = human.getZone(ZoneType.Battlefield).cards.toList()
            val selectedIds = selection.idsList.filter { it != blockerIid }.takeLast(6)
            val selectedForgeIds = selectedIds.map { bridge.getForgeCardId(InstanceId(it))!!.value }.toSet()
            val sacrificed = before.filter { it.id in selectedForgeIds }
            val retained = before.filter { it.id !in selectedForgeIds }
            assertSoftly {
                promptMessage.systemSeatIdsList shouldBe listOf(HUMAN_SEAT)
                selection.minSel shouldBe 6
                selection.maxSel shouldBe 6
                selection.idsCount shouldBe 8
                sacrificed.size shouldBe 6
                phase() shouldBe "COMBAT_DECLARE_ATTACKERS"
                allMessages.any { it.hasDeclareBlockersReq() }.shouldBeFalse()
                human.getZone(ZoneType.Graveyard).size() shouldBe 0
            }

            respondToSelectN(selectedIds)
            passUntil { allMessages.any { it.hasDeclareBlockersReq() } }.shouldBeTrue()
            val contract = ProtocolContract.load(AcceptancePaths.resolve("conformance/contracts/emrakul-sacrifice-choice.yaml"))
            contract.verify(allMessages)
            val completed =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .single { AnnotationType.ResolutionComplete in it.typeList && it.affectorId == abilityIid }
            val started = annotationsBeforeChoice.single { AnnotationType.ResolutionStart in it.typeList && it.affectorId == abilityIid }
            val delayedStart =
                allMessages.map { message ->
                    if (!message.hasGameStateMessage()) {
                        message
                    } else {
                        val gsm = message.gameStateMessage
                        val rows = gsm.annotationsList.filter { it != started }.toMutableList()
                        if (completed in rows) rows.add(0, started)
                        message.toBuilder().setGameStateMessage(gsm.toBuilder().clearAnnotations().addAllAnnotations(rows)).build()
                    }
                }
            val prematureCompletion =
                allMessages.map { message ->
                    if (!message.hasGameStateMessage() || started !in message.gameStateMessage.annotationsList) {
                        message
                    } else {
                        val gsm = message.gameStateMessage
                        message.toBuilder().setGameStateMessage(gsm.toBuilder().addAnnotations(completed)).build()
                    }
                }
            val finalSacrifice =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .last {
                        AnnotationType.ZoneTransfer_af5a in it.typeList &&
                            it.affectorId == abilityIid &&
                            it.detailsList.any { detail -> detail.key == "category" && "Sacrifice" in detail.valueStringList }
                    }
            val finalIdChange =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .single {
                        AnnotationType.ObjectIdChanged in it.typeList &&
                            it.detailsList.any { detail ->
                                detail.key == "new_id" &&
                                    finalSacrifice.affectedIdsList.single() in detail.valueInt32List
                            }
                    }
            val lateSacrifice =
                allMessages.map { message ->
                    if (!message.hasGameStateMessage()) {
                        message
                    } else {
                        val gsm = message.gameStateMessage
                        val rows = gsm.annotationsList.filter { it != finalIdChange && it != finalSacrifice }.toMutableList()
                        val completionIndex = rows.indexOf(completed)
                        if (completionIndex >= 0) rows.addAll(completionIndex + 1, listOf(finalIdChange, finalSacrifice))
                        message.toBuilder().setGameStateMessage(gsm.toBuilder().clearAnnotations().addAllAnnotations(rows)).build()
                    }
                }
            assertSoftly {
                shouldThrow<AssertionError> { contract.verify(lateSacrifice) }
                shouldThrow<AssertionError> { contract.verify(delayedStart) }
                shouldThrow<AssertionError> { contract.verify(prematureCompletion) }
            }
            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.toList() shouldContainExactlyInAnyOrder sacrificed
                human.getZone(ZoneType.Battlefield).cards.toList() shouldContainExactlyInAnyOrder retained
                blocker.zone.zoneType shouldBe ZoneType.Battlefield
                allMessages
                    .last { it.hasDeclareBlockersReq() }
                    .declareBlockersReq.blockersList
                    .any { it.blockerInstanceId == blockerIid }
                    .shouldBeTrue()
            }
            declareBlockers(emptyMap())
            assertSoftly {
                passUntil { phase() == "MAIN2" }.shouldBeTrue()
                human.life shouldBe 25
                isGameOver().shouldBeFalse()
            }
        }

        session(
            "fewer than six permanents are all sacrificed without a choice and combat continues",
            puzzle = puzzle("Plains;Island;Swamp"),
            turns = 4,
            aiScript = listOf(ScriptedAction.Attack(listOf("Emrakul, the Aeons Torn"))),
            fullControl = true,
            responseMode = HeadlessResponseMode.PolicyVisible,
        ) {
            val permanents = human.getZone(ZoneType.Battlefield).cards.toList()
            assertSoftly {
                permanents.size shouldBe 3
                passUntil { phase() == "MAIN2" }.shouldBeTrue()
                human.getZone(ZoneType.Battlefield).size() shouldBe 0
                human.getZone(ZoneType.Graveyard).cards.toList() shouldContainExactlyInAnyOrder permanents
                allMessages.any { it.hasSelectNReq() }.shouldBeFalse()
                human.life shouldBe 25
                isGameOver().shouldBeFalse()
            }
        }
    })
